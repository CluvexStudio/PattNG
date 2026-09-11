package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
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

    private val tunnels = Mutex()

    internal enum class Route {
        ACTIVE_SESSION,
        NEW_TUNNEL,
        SKIP,
    }

    suspend fun measure(context: Context, guid: String, profile: ProfileItem, url: String): Long? {
        val activeGuid = MmkvManager.getSelectServer()
        val active = activeGuid?.let(MmkvManager::decodeServerConfig)
        val sessionUp = withContext(Dispatchers.IO) { AetherCoreManager.acceptsConnections(AetherCoreManager.socksPort) }
        return when (route(guid, profile, activeGuid, active, sessionUp)) {
            Route.ACTIVE_SESSION -> withContext(Dispatchers.IO) { requestDelay(AetherCoreManager.socksPort, url) }
            Route.NEW_TUNNEL -> tunnels.withLock { throughNewTunnel(context, guid, profile, url) }
            Route.SKIP -> null
        }
    }

    internal fun route(
        guid: String,
        profile: ProfileItem,
        activeGuid: String?,
        active: ProfileItem?,
        sessionUp: Boolean,
    ): Route {
        if (!sessionUp || active == null || active.configType != EConfigType.AETHER) return Route.NEW_TUNNEL
        if (guid == activeGuid) return Route.ACTIVE_SESSION
        val shared = AetherIdentityManager.sharesIdentity(
            AetherProtocol.fromString(profile.aetherProtocol),
            AetherProtocol.fromString(active.aetherProtocol),
        )
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
