package com.snowyghost.ovenlight

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.util.Log
import com.snowyghost.ovenlight.core.Core
import com.snowyghost.ovenlight.core.Platform
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Go core's Android side: it starts the core and its local proxy, answers its
 * questions about the phone's network, and tells it when the default network changes.
 */
object TailnetCore : Platform {
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
            override fun onLost(network: Network) = reports.execute { Core.networkChanged("", "") }
        })
    }

    /**
     * Reports the current default network, as Ovenlight comes to the foreground, on the same
     * thread as the network callback's own reports, so they arrive in order.
     */
    fun reportNetwork() {
        reports.execute {
            val properties = connectivity.activeNetwork?.let(connectivity::getLinkProperties)
            if (properties == null) Core.networkChanged("", "") else report(properties)
        }
    }

    private val reports = Executors.newSingleThreadExecutor()

    private fun report(properties: LinkProperties) {
        val defaults = properties.routes.filter { it.isDefaultRoute && it.gateway != null }
        val gateway = (defaults.firstOrNull { it.gateway is Inet4Address } ?: defaults.firstOrNull())?.gateway
        Core.networkChanged(properties.interfaceName.orEmpty(), gateway?.hostAddress.orEmpty())
    }

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

    /** Only debug builds get lines, with sign-in links and keys already removed. */
    override fun log(line: String) {
        Log.d("OvenlightCore", line)
    }
}
