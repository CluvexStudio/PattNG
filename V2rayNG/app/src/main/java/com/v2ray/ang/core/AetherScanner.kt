package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.dto.AetherEndpoint
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherProtocol

data class AetherScanResult(
    val endpoint: AetherEndpoint,
    val innerHop: AetherEndpoint? = null,
)

object AetherScanner {

    private const val SCAN_TIMEOUT_MS = 8 * 60_000L

    private val masqueGateway = Regex("""selected MASQUE gateway (\S+)""")
    private val wireguardEndpoint = Regex("""selected WireGuard endpoint (\S+)""")
    private val goolHops = Regex("""using cloudflare edge (\S+) \(outer\) and (\S+) \(inner\)""")

    suspend fun scan(
        context: Context,
        profile: ProfileItem,
        onOutput: (String) -> Unit = {},
    ): AetherScanResult? {
        val protocol = AetherProtocol.fromString(profile.aetherProtocol)
        return AetherCoreManager.runUntil(
            context = context,
            arguments = AetherCoreManager.buildArguments(profile, 0, scan = true),
            timeoutMs = SCAN_TIMEOUT_MS,
            source = "aether-scan",
            onOutput = onOutput,
        ) { line -> parse(protocol, line) }
    }

    fun parse(protocol: AetherProtocol, line: String): AetherScanResult? = when (protocol) {
        AetherProtocol.GOOL -> goolHops.find(line)?.let { match ->
            val outer = endpointOf(match.groupValues[1])
            val inner = endpointOf(match.groupValues[2])
            if (outer != null && inner != null) AetherScanResult(outer, inner) else null
        }

        AetherProtocol.MASQUE -> masqueGateway.find(line)?.let { endpointOf(it.groupValues[1]) }?.let(::AetherScanResult)
        AetherProtocol.WIREGUARD -> wireguardEndpoint.find(line)?.let { endpointOf(it.groupValues[1]) }?.let(::AetherScanResult)
    }

    private fun endpointOf(text: String): AetherEndpoint? {
        AetherEndpoint.parse(text)?.let { return it }
        val separator = text.lastIndexOf(':')
        if (separator <= 0) return null
        return AetherEndpoint.of(text.substring(0, separator), text.substring(separator + 1))
    }
}
