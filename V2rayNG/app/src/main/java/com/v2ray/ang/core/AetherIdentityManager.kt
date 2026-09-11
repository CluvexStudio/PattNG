package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class AetherIdentity(
    val deviceId: String,
    val ipv4: String,
    val ipv6: String,
)

data class AetherIdentityStatus(
    val protocol: AetherProtocol,
    val primary: AetherIdentity?,
    val secondary: AetherIdentity? = null,
)

object AetherIdentityManager {

    const val BASE_FILE = "aether.toml"
    const val MASQUE_FILE = "aether-masque.toml"
    const val WIREGUARD_FILE = "aether-wg.toml"
    const val WIREGUARD_INNER_FILE = "aether-wg-secondary.toml"

    private const val WORK_DIR = "aether"
    private const val PREVIOUS_DIR = "aether-previous"
    private const val RENEW_TIMEOUT_MS = 2 * 60_000L

    private val identityField = Regex("""^(device_id|ipv4|ipv6)\s*=\s*"([^"]*)"$""")
    private val multilineDelimiter = Regex("\"\"\"|'''")
    private val identityReady = Regex("""identity ready: device=\S+""")
    private val goolIdentitiesReady = Regex("""outer device=\S+ .*\| inner device=\S+""")

    fun workDir(context: Context): File = File(context.filesDir, WORK_DIR)

    suspend fun status(context: Context, protocol: AetherProtocol): AetherIdentityStatus =
        withContext(Dispatchers.IO) { status(workDir(context), protocol) }

    suspend fun renew(
        context: Context,
        profile: ProfileItem,
        onOutput: (String) -> Unit,
    ): AetherIdentityStatus? {
        val protocol = AetherProtocol.fromString(profile.aetherProtocol)
        val workDir = workDir(context)
        val renewed = replaceIdentities(workDir, File(context.filesDir, PREVIOUS_DIR)) {
            AetherCoreManager.runUntil(
                context = context,
                arguments = AetherCoreManager.buildArguments(profile, 0, scan = true),
                timeoutMs = RENEW_TIMEOUT_MS,
                source = "aether-key",
                onOutput = onOutput,
            ) { line -> line.takeIf { isReady(protocol, it) } } != null
        }
        return if (renewed) withContext(Dispatchers.IO) { status(workDir, protocol) } else null
    }

    internal fun status(workDir: File, protocol: AetherProtocol): AetherIdentityStatus = when (protocol) {
        AetherProtocol.MASQUE -> AetherIdentityStatus(protocol, read(File(workDir, MASQUE_FILE)))
        AetherProtocol.WIREGUARD -> AetherIdentityStatus(protocol, read(File(workDir, WIREGUARD_FILE)))
        AetherProtocol.GOOL -> AetherIdentityStatus(
            protocol,
            read(File(workDir, WIREGUARD_FILE)),
            read(File(workDir, WIREGUARD_INNER_FILE)),
        )
    }

    internal fun parse(text: String): AetherIdentity? {
        val fields = mutableMapOf<String, String>()
        var insideMultiline = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (!insideMultiline) {
                identityField.matchEntire(line)?.destructured?.let { (key, value) -> fields.putIfAbsent(key, value) }
            }
            if (multilineDelimiter.findAll(line).count() % 2 == 1) {
                insideMultiline = !insideMultiline
            }
        }
        val deviceId = fields["device_id"]?.takeIf { it.isNotBlank() } ?: return null
        return AetherIdentity(deviceId, fields["ipv4"].orEmpty(), fields["ipv6"].orEmpty())
    }

    internal fun isReady(protocol: AetherProtocol, line: String): Boolean = when (protocol) {
        AetherProtocol.GOOL -> goolIdentitiesReady.containsMatchIn(line)
        AetherProtocol.MASQUE, AetherProtocol.WIREGUARD -> identityReady.containsMatchIn(line)
    }

    internal suspend fun replaceIdentities(
        workDir: File,
        previousDir: File,
        provision: suspend () -> Boolean,
    ): Boolean {
        val setAside = withContext(Dispatchers.IO) {
            previousDir.deleteRecursively()
            !workDir.exists() || workDir.renameTo(previousDir)
        }
        if (!setAside) return false

        var renewed = false
        try {
            withContext(Dispatchers.IO) { workDir.mkdirs() }
            renewed = provision()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                if (renewed) {
                    previousDir.deleteRecursively()
                } else {
                    workDir.deleteRecursively()
                    if (previousDir.exists()) previousDir.renameTo(workDir)
                }
            }
        }
        return renewed
    }

    private fun read(file: File): AetherIdentity? = try {
        if (file.isFile) parse(file.readText()) else null
    } catch (_: IOException) {
        null
    }
}
