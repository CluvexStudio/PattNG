package com.v2ray.ang.core

import android.content.Context
import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.AetherEndpoint
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherIpVersion
import com.v2ray.ang.enums.AetherObfuscation
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.AetherScanMode
import com.v2ray.ang.enums.AetherTransport
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

object AetherCoreManager {

    private const val BINARY_NAME = "libaether.so"
    private const val PROBE_TIMEOUT_MS = 1000

    private val logLevels = setOf("ERROR", "WARN", "INFO", "DEBUG", "TRACE")

    private val lifecycle = Executors.newSingleThreadExecutor { task ->
        Thread(task, "aether-core").apply { isDaemon = true }
    }

    val socksPort: Int get() = AppConfig.PORT_AETHER_SOCKS.toInt()

    @Volatile
    private var session: Session? = null

    val isRunning: Boolean get() = session != null

    fun isSupported(context: Context): Boolean = binary(context).canExecute()

    fun buildArguments(profile: ProfileItem, port: Int, scan: Boolean = false): List<String> {
        val protocol = AetherProtocol.fromString(profile.aetherProtocol)
        return buildList {
            addAll(listOf("--bind", "${AppConfig.LOOPBACK}:$port"))
            addAll(listOf("--protocol", protocol.type))
            addAll(listOf("--scan", AetherScanMode.fromString(profile.aetherScanMode).type))
            addAll(listOf("--noize", AetherObfuscation.fromString(profile.aetherObfuscation).type))
            addAll(listOf("--ip", AetherIpVersion.fromString(profile.aetherIpVersion).type))

            if (protocol == AetherProtocol.MASQUE &&
                AetherTransport.fromString(profile.aetherTransport) == AetherTransport.HTTP2
            ) {
                add("--h2")
                if (profile.aetherFragment == true) add("--fragment")
            }

            if (protocol == AetherProtocol.GOOL) {
                val outer = AetherEndpoint.parse(profile.aetherWiwOuter).takeUnless { scan }
                val inner = AetherEndpoint.parse(profile.aetherWiwInner).takeUnless { scan }
                outer?.let { addAll(listOf("--wiw-outer", it.toString())) }
                inner?.let { addAll(listOf("--wiw-inner", it.toString())) }
                if (outer == null && inner == null) add("--wiw-scan")
            } else if (!scan) {
                AetherEndpoint.of(profile.server, profile.serverPort)?.let { addAll(listOf("--peer", it.toString())) }
            }

            add(if (scan) "--no-quick-reconnect" else "--quick-reconnect")
            addAll(listOf("--log-level", "info"))
        }
    }

    internal fun startProcess(context: Context, arguments: List<String>): Process {
        val workDir = AetherIdentityManager.workDir(context).apply { mkdirs() }
        val builder = ProcessBuilder(listOf(binary(context).absolutePath) + arguments)
            .directory(workDir)
            .redirectErrorStream(true)
        builder.environment().apply {
            put("HOME", workDir.absolutePath)
            put("TMPDIR", context.cacheDir.absolutePath)
            put("AETHER_CONFIG", File(workDir, AetherIdentityManager.BASE_FILE).absolutePath)
            put("AETHER_MASQUE_CONFIG", File(workDir, AetherIdentityManager.MASQUE_FILE).absolutePath)
            put("AETHER_WG_CONFIG", File(workDir, AetherIdentityManager.WIREGUARD_FILE).absolutePath)
        }
        return builder.start()
    }

    internal suspend fun <T> withProcess(
        context: Context,
        arguments: List<String>,
        source: String,
        onOutput: (String) -> Unit,
        block: suspend (output: ReceiveChannel<String>) -> T?,
    ): T? = coroutineScope {
        val process = withContext(Dispatchers.IO) {
            try {
                startProcess(context, arguments)
            } catch (e: IOException) {
                LogUtil.e(AppConfig.TAG, "AetherCore: failed to launch $source", e)
                null
            }
        } ?: return@coroutineScope null

        val output = Channel<String>(Channel.UNLIMITED)
        launch(Dispatchers.IO) { forward(process, source, onOutput, output) }
        try {
            block(output)
        } finally {
            process.destroy()
        }
    }

    internal suspend fun <T : Any> runUntil(
        context: Context,
        arguments: List<String>,
        timeoutMs: Long,
        source: String,
        onOutput: (String) -> Unit,
        match: (String) -> T?,
    ): T? = withProcess(context, arguments, source, onOutput) { output ->
        withTimeoutOrNull(timeoutMs) { output.receiveAsFlow().mapNotNull(match).firstOrNull() }
    }

    @Synchronized
    fun start(context: Context, profile: ProfileItem, onExit: () -> Unit) {
        stop()
        val next = Session(onExit)
        session = next
        val appContext = context.applicationContext
        val arguments = buildArguments(profile, socksPort)
        lifecycle.execute { open(next, appContext, arguments) }
    }

    @Synchronized
    fun stop() {
        val current = session ?: return
        session = null
        lifecycle.execute { current.process?.destroy() }
    }

    fun isListening(): Boolean = isRunning && acceptsConnections(socksPort)

    internal fun acceptsConnections(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(AppConfig.LOOPBACK, port), PROBE_TIMEOUT_MS) }
        true
    } catch (_: IOException) {
        false
    }

    internal fun relay(line: String, source: String) {
        val text = line.trim()
        if (text.isEmpty()) return
        val message = "[$source] $text"
        when (outputPriority(text)) {
            Log.ERROR -> LogUtil.e(AppConfig.TAG, message)
            Log.WARN -> LogUtil.w(AppConfig.TAG, message)
            Log.DEBUG -> LogUtil.d(AppConfig.TAG, message)
            else -> LogUtil.i(AppConfig.TAG, message)
        }
    }

    internal fun outputPriority(line: String): Int {
        if (line.startsWith("Error:")) return Log.ERROR
        return when (logHeader(line)?.get(1)) {
            "ERROR" -> Log.ERROR
            "WARN" -> Log.WARN
            "DEBUG", "TRACE" -> Log.DEBUG
            else -> Log.INFO
        }
    }

    internal fun outputMessage(line: String): String {
        val text = line.trim()
        return if (logHeader(text) != null) text.substringAfter(']').trim() else text
    }

    private fun logHeader(line: String): List<String>? {
        if (!line.startsWith('[')) return null
        val headerEnd = line.indexOf(']').takeIf { it > 0 } ?: return null
        return line.substring(1, headerEnd)
            .split(' ')
            .filter(String::isNotEmpty)
            .takeIf { it.size >= 3 && it[1] in logLevels }
    }

    private fun binary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)

    private fun open(target: Session, context: Context, arguments: List<String>) {
        if (session !== target) return
        val process = try {
            startProcess(context, arguments)
        } catch (e: IOException) {
            LogUtil.e(AppConfig.TAG, "AetherCore: failed to launch the core", e)
            if (release(target)) target.onExit()
            return
        }
        target.process = process
        thread(name = "aether-core-output", isDaemon = true) { watch(target, process) }
    }

    private fun forward(process: Process, source: String, onOutput: (String) -> Unit, output: Channel<String>) {
        try {
            process.inputStream.bufferedReader().forEachLine { line ->
                relay(line, source)
                onOutput(line)
                output.trySend(line)
            }
        } catch (e: IOException) {
            LogUtil.d(AppConfig.TAG, "AetherCore: $source output closed: ${e.message}")
        } finally {
            output.close()
        }
    }

    private fun watch(target: Session, process: Process) {
        try {
            process.inputStream.bufferedReader().forEachLine { relay(it, "aether") }
        } catch (e: IOException) {
            LogUtil.d(AppConfig.TAG, "AetherCore: output closed: ${e.message}")
        }
        val exitCode = process.waitFor()
        if (!release(target)) return
        LogUtil.e(AppConfig.TAG, "AetherCore: the core exited on its own with code $exitCode")
        target.onExit()
    }

    @Synchronized
    private fun release(target: Session): Boolean {
        if (session !== target) return false
        session = null
        return true
    }

    private class Session(val onExit: () -> Unit) {
        var process: Process? = null
    }
}
