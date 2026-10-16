package com.snowyghost.ovenlight

import java.io.File
import okhttp3.Request

// Fakes the JVM tests and the device tests share (src/sharedTest is in both source sets).

/** Settings in memory, under the same keys as the phone's. */
class MemoryStore : KeyValueStore {
    private val values = mutableMapOf<String, Any>()
    override fun getInt(key: String, default: Int) = values[key] as? Int ?: default
    override fun putInt(key: String, value: Int) {
        values[key] = value
    }
    override fun getString(key: String) = values[key] as? String
    override fun putString(key: String, value: String) {
        values[key] = value
    }
    override fun getBoolean(key: String) = values[key] as? Boolean ?: false
    override fun putBoolean(key: String, value: Boolean) {
        values[key] = value
    }
    override fun remove(key: String) {
        values.remove(key)
    }
}

/** A node that reports whatever status the test sets. */
class FakeNode(val id: String, private val clients: FakeClients) : TailnetNode {
    var started = false
    var closed = false
    var loggedOut = false
    override fun start() {
        check(!closed) { "node closed" }
        clients.onStart()
        clients.startError?.let { throw Exception(it) }
        started = true
    }
    override fun waitUp(timeoutMillis: Long) {
        clients.upError?.let { throw Exception(it) }
    }
    override fun statusJSON(): String = clients.status[id] ?: clients.anyStatus ?: error("no status")
    override fun loginInteractive() {
        clients.loginRequests += 1
    }
    override fun logout() {
        loggedOut = true
    }
    override fun close() {
        closed = true
    }
}

/**
 * The Go core and its proxy, faked: nodes report the statuses set here (by membership, else
 * [anyStatus]), and requests answer by URL from [pages], or from [respond] when it's set.
 */
class FakeClients : TailnetClients {
    val status = mutableMapOf<String, String>()
    var anyStatus: String? = null
    val pages = mutableMapOf<String, FetchResponse>()
    var respond: ((Request) -> FetchResponse?)? = null
    val nodes = mutableListOf<FakeNode>()
    val forgotten = mutableListOf<String>()

    /** Each request's route key and URL. */
    val fetched = mutableListOf<Pair<String, String>>()
    val requests = mutableListOf<Request>()
    var upError: String? = null
    var startError: String? = null

    /** Runs inside each node's start, as the core's blocking call would. */
    var onStart: () -> Unit = {}
    var loginRequests = 0

    override fun newNode(id: String, directory: File, hostName: String, controlURL: String, authKey: String): TailnetNode {
        check(nodes.none { it.id == id && !it.closed }) { "membership $id already has an open node" }
        return FakeNode(id, this).also { nodes += it }
    }

    override fun session(routeKey: String, timeoutMillis: Long, totalMillis: Long): Send = { request ->
        fetched += routeKey to request.url.toString()
        requests += request
        respond?.invoke(request) ?: pages[request.url.toString()]
    }

    override fun forget(membershipID: String) {
        forgotten += membershipID
    }
}

/** A node's status as the Go core writes it, in the tailnet [suffix] names. */
internal fun status(backend: String, authURL: String = "", peers: String = "", self: String = "", suffix: String = "tailabc123.ts.net") =
    """{"BackendState":"$backend","AuthURL":"$authURL",$self"Peer":{$peers},"User":{"1":{"LoginName":"riley@example.com","DisplayName":"Riley"}},""" +
        """"CurrentTailnet":{"Name":"riley@example.com","MagicDNSSuffix":"$suffix"}}"""
