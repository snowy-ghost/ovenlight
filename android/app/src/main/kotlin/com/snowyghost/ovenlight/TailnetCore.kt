package com.snowyghost.ovenlight

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.util.Log
import com.snowyghost.ovenlight.core.Core
import com.snowyghost.ovenlight.core.Platform
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.net.NetworkInterface
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * The proxy's credential, for the proxy's own CONNECT challenge (OkHttp's preemptive one
 * included) and nothing else. OkHttp also asks a proxy authenticator about a 407 that the origin
 * sends inside the tunnel, and answering that would hand the secret to whatever server the
 * route reaches. A CONNECT that already carried it was refused, so it isn't sent again.
 */
class ProxyAuth(private val routeKey: String, private val secret: String) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        val request = response.request
        if (request.method != "CONNECT" || request.header("Proxy-Authorization") != null) return null
        return request.newBuilder().header("Proxy-Authorization", Credentials.basic(routeKey, secret)).build()
    }
}

/**
 * The Go core's Android side: it starts the core and its local proxy, answers its
 * questions about the phone's network, and tells it when the default network changes.
 */
object TailnetCore : Platform, TailnetClients {
    /** "127.0.0.1:<port>", for the WebView proxy override and OkHttp. */
    lateinit var proxyAddress: String
        private set
    lateinit var proxySecret: String
        private set
    lateinit var proxyRealm: String
        private set

    private lateinit var connectivity: ConnectivityManager

    fun start(context: Context) {
        Core.init(this, BuildConfig.DEBUG)
        proxyAddress = Core.proxyAddress()
        proxySecret = Core.proxySecret()
        proxyRealm = Core.proxyRealm()
        connectivity = context.getSystemService(ConnectivityManager::class.java)
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = reports.execute { report(properties) }
            override fun onLost(network: Network) = reports.execute { Core.networkChanged("", "", "") }
        })
    }

    /**
     * Reports the current default network, as Ovenlight comes to the foreground, on the same
     * thread as the network callback's own reports, so they arrive in order.
     */
    fun reportNetwork() {
        reports.execute {
            val properties = connectivity.activeNetwork?.let(connectivity::getLinkProperties)
            if (properties == null) Core.networkChanged("", "", "") else report(properties)
        }
    }

    private val reports = Executors.newSingleThreadExecutor()

    private fun report(properties: LinkProperties) {
        // A directly connected default route, as cellular often has, reports 0.0.0.0 or :: as
        // its gateway, which the port mapper would then probe: only a real next hop counts.
        val defaults = properties.routes.filter { it.isDefaultRoute && it.hasGateway() }
        val gateway = (defaults.firstOrNull { it.gateway is Inet4Address } ?: defaults.firstOrNull())?.gateway
        Core.networkChanged(properties.interfaceName.orEmpty(), gateway?.hostAddress.orEmpty(), nat64Prefix(properties))
    }

    /** The network's own NAT64 prefix, as "2001:db8:64::/96", which Android reports from API 30. */
    internal fun nat64Prefix(properties: LinkProperties): String =
        if (Build.VERSION.SDK_INT >= 30) properties.nat64Prefix?.toString().orEmpty() else ""

    /**
     * Never throws: Go calls it on its own threads, where gobind leaves an exception pending
     * rather than raising it. An interface that goes away while it's read, as during a Wi-Fi
     * drop, is skipped.
     */
    override fun interfacesJSON(): String {
        val list = JSONArray()
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: Exception) {
            return "[]"
        }
        for (ni in interfaces) {
            try {
                val addrs = JSONArray()
                for (a in ni.interfaceAddresses) {
                    val ip = a.address?.hostAddress ?: continue
                    addrs.put(JSONObject().put("ip", ip).put("prefixLen", a.networkPrefixLength.toInt()))
                }
                list.put(
                    JSONObject()
                        .put("name", ni.name)
                        .put("index", ni.index)
                        .put("mtu", ni.mtu)
                        .put("up", ni.isUp)
                        .put("loopback", ni.isLoopback)
                        .put("pointToPoint", ni.isPointToPoint)
                        .put("multicast", ni.supportsMulticast())
                        .put("addrs", addrs),
                )
            } catch (_: Exception) {
                continue
            }
        }
        return list.toString()
    }

    override fun newNode(id: String, directory: File, hostName: String, controlURL: String, authKey: String): TailnetNode {
        val node = Core.newNode(id, directory.path, hostName, controlURL, authKey)
        return object : TailnetNode {
            override fun start() = node.start()
            override fun waitUp(timeoutMillis: Long) = node.waitUp(timeoutMillis)
            override fun statusJSON(): String = node.statusJSON()
            override fun loginInteractive() = node.loginInteractive()
            override fun logout() = node.logout()
            override fun close() = node.close()
        }
    }

    override fun forget(membershipID: String) = Core.clearRoute("node-$membershipID")

    /**
     * Fetches through the proxy with [routeKey], answering its challenge up front. Null when
     * nothing answered; any HTTP answer, an error page included, is a response. Bodies are
     * capped, since every fetch here is a small page, manifest, API answer or feedback screenshot.
     */
    override fun session(routeKey: String, timeoutMillis: Long, totalMillis: Long): Send {
        val client = client(routeKey)
            .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .callTimeout(totalMillis, TimeUnit.MILLISECONDS)
            // Not even on a pooled connection that died: a change (an admin DELETE, say) may have
            // arrived. Callers that may ask again do (AdminClient's GETs, the claim, the polls).
            .retryOnConnectionFailure(false)
            .build()
        return { request -> client.fetch(request) }
    }

    /**
     * A client through the proxy with [routeKey], which answers its challenge up front. Every
     * OkHttp client in Ovenlight is made here, so only [ProxyAuth] ever sends the secret.
     */
    fun client(routeKey: String): OkHttpClient.Builder = http.newBuilder()
        .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyAddress.substringAfterLast(':').toInt())))
        .proxyAuthenticator(ProxyAuth(routeKey, proxySecret))

    // Redirects are followed, as iOS's sessions follow them (an app's start page that sends
    // `/` to `/login` still names the app), but never from https to http.
    private val http = OkHttpClient.Builder().cache(null).followSslRedirects(false).build()
    /** Above the connector's 5 MiB for a feedback screenshot, the largest answer Ovenlight reads. */
    private const val MAX_BODY = 6L shl 20

    private suspend fun OkHttpClient.fetch(request: Request): FetchResponse? = suspendCancellableCoroutine { done ->
        val call = newCall(request)
        done.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (done.isActive) done.resume(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val answer = response.use {
                    // Buffers up to the cap, or to the end of a shorter body.
                    val body = runCatching {
                        val source = it.body.source()
                        source.request(MAX_BODY)
                        source.buffer.readByteArray(minOf(source.buffer.size, MAX_BODY))
                    }.getOrDefault(ByteArray(0))
                    FetchResponse(it.code, body, it.headers.toMap())
                }
                if (done.isActive) done.resume(answer)
            }
        })
    }

    /** Only debug builds get lines, with sign-in links and keys already removed. */
    override fun log(line: String) {
        Log.d("OvenlightCore", line)
    }
}
