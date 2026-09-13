package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.AetherEndpoint
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.blackholeSink
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

object AetherDelayTester {

    private const val START_TIMEOUT_MS = 60_000L
    private const val REQUEST_TIMEOUT_MS = 12_000L
    private const val POLL_INTERVAL_MS = 250L
    private const val ATTEMPTS = 2

    /** Port every Cloudflare edge answers on TCP, whatever port the tunnel itself uses. */
    private const val EDGE_TCP_PORT = 443
    private const val REACH_TIMEOUT_MS = 1000

    /** Stored for "no result": the server row shows nothing instead of a stale or a failed number. */
    const val UNTESTED = 0L

    private val tunnels = Mutex()

    internal enum class Route {
        ACTIVE_SESSION,
        NEW_TUNNEL,
        SKIP,
    }

    suspend fun measure(context: Context, guid: String, profile: ProfileItem, url: String): Long {
        val activeGuid = MmkvManager.getSelectServer()
        val session = withContext(Dispatchers.IO) { liveSession(context, activeGuid) }
        return when (route(guid, profile, activeGuid, session)) {
            Route.ACTIVE_SESSION -> withContext(Dispatchers.IO) { requestDelay(AetherCoreManager.socksPort, url) }
            Route.NEW_TUNNEL -> tunnels.withLock { throughNewTunnel(context, guid, profile, url) }
            Route.SKIP -> {
                // A second tunnel on the live session's key would disturb it.
                LogUtil.i(AppConfig.TAG, "AetherTest: left untested, it shares the live session's key, guid=$guid")
                UNTESTED
            }
        }
    }

    /** The daemon's live Aether session: its protocol, and its arguments when its process could be read. */
    internal class LiveSession(val protocol: AetherProtocol, val arguments: List<String>?)

    /**
     * The daemon's live Aether session, or null without one. Its core process names the protocol
     * and the running profile, whether it is still scanning or already listening; when /proc
     * cannot be read, a listener on the session port together with a selected Aether profile
     * stands in for it.
     */
    private fun liveSession(context: Context, activeGuid: String?): LiveSession? {
        AetherCoreManager.sessionArguments(context)?.let { return LiveSession(AetherCoreManager.protocolOf(it), it) }
        if (!AetherCoreManager.acceptsConnections(AetherCoreManager.socksPort)) return null
        val active = activeGuid?.let(MmkvManager::decodeServerConfig)?.takeIf { it.configType == EConfigType.AETHER } ?: return null
        return LiveSession(AetherProtocol.fromString(active.aetherProtocol), arguments = null)
    }

    /**
     * The cheap probe behind "TCP ping": a TCP connect to the pinned edge address instead of a
     * full tunnel. It tells whether that edge is reachable, not whether the tunnel works, and a
     * profile left to the scanner has nothing to probe.
     */
    fun reachability(
        profile: ProfileItem,
        connect: (host: String, port: Int) -> Long = { host, port -> SpeedtestManager.socketConnectTime(host, port, REACH_TIMEOUT_MS) },
    ): Long {
        val host = probeHost(profile) ?: return UNTESTED
        return connect(host, EDGE_TCP_PORT)
    }

    internal fun probeHost(profile: ProfileItem): String? =
        if (AetherProtocol.fromString(profile.aetherProtocol) == AetherProtocol.GOOL) {
            AetherEndpoint.parse(profile.aetherWiwOuter)?.host
        } else {
            AetherEndpoint.of(profile.server, profile.serverPort)?.host
        }

    /**
     * Where a test goes: through the live session for the profile it runs, nowhere for another
     * profile whose key the session uses, and through a tunnel of its own otherwise. The running
     * profile is told by the session's arguments; without them, the selected profile stands in.
     */
    internal fun route(guid: String, profile: ProfileItem, activeGuid: String?, session: LiveSession?): Route {
        if (session == null) return Route.NEW_TUNNEL
        val running = session.arguments?.let { AetherCoreManager.runsProfile(it, profile) } ?: (guid == activeGuid)
        if (running) return Route.ACTIVE_SESSION
        val shared = AetherIdentityManager.sharesIdentity(AetherProtocol.fromString(profile.aetherProtocol), session.protocol)
        return if (shared) Route.SKIP else Route.NEW_TUNNEL
    }

    private suspend fun throughNewTunnel(context: Context, guid: String, profile: ProfileItem, url: String): Long {
        val port = withContext(Dispatchers.IO) { Utils.findRandomFreePort() }
        return AetherCoreManager.withProcess(
            context = context,
            arguments = AetherCoreManager.buildArguments(profile, port),
            source = "aether-test",
            onOutput = {},
        ) { output ->
            if (!awaitListening(port, output)) {
                LogUtil.w(AppConfig.TAG, "AetherTest: the tunnel did not come up, guid=$guid")
                return@withProcess -1L
            }
            val delay = withContext(Dispatchers.IO) { requestDelay(port, url) }
            if (delay < 0) LogUtil.w(AppConfig.TAG, "AetherTest: no answer through the tunnel, guid=$guid")
            delay
        } ?: -1L
    }

    private suspend fun awaitListening(port: Int, output: ReceiveChannel<String>): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(START_TIMEOUT_MS)
        while (System.nanoTime() < deadline) {
            while (output.tryReceive().isSuccess) Unit
            if (output.isClosedForReceive) return false
            if (withContext(Dispatchers.IO) { AetherCoreManager.acceptsConnections(port) }) return true
            delay(POLL_INTERVAL_MS)
        }
        return false
    }

    internal fun requestDelay(port: Int, url: String): Long {
        val request = try {
            Request.Builder().url(url).build()
        } catch (_: IllegalArgumentException) {
            return -1L
        }
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(AppConfig.LOOPBACK, port)))
            .callTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        return try {
            List(ATTEMPTS) { timedRequest(client, request) }.filterNotNull().minOrNull() ?: -1L
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun timedRequest(client: OkHttpClient, request: Request): Long? = try {
        val started = System.nanoTime()
        client.newCall(request).execute().use { response ->
            response.body.source().readAll(blackholeSink())
            if (response.code == 200 || response.code == 204) {
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            } else {
                null
            }
        }
    } catch (_: IOException) {
        null
    }
}
