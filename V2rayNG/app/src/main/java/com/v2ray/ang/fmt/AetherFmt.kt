package com.v2ray.ang.fmt

import com.v2ray.ang.dto.AetherEndpoint
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherIpVersion
import com.v2ray.ang.enums.AetherObfuscation
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.AetherScanMode
import com.v2ray.ang.enums.AetherTransport
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.idnHost
import com.v2ray.ang.util.Utils
import java.net.URI

object AetherFmt : FmtBase() {

    enum class EndpointProblem {
        INVALID_PEER,
        INVALID_HOP,
        SHARED_HOP,
    }

    fun parse(str: String): ProfileItem? {
        val config = ProfileItem.create(EConfigType.AETHER)

        val uri = URI(Utils.fixIllegalUrl(str))
        val queryParam = if (uri.rawQuery.isNullOrEmpty()) emptyMap() else getQueryParam(uri)
        val protocol = AetherProtocol.fromString(queryParam["protocol"])

        config.remarks = Utils.decodeURIComponent(uri.fragment.orEmpty()).ifEmpty { "Aether" }
        config.aetherProtocol = protocol.type
        config.aetherTransport = AetherTransport.fromString(queryParam["transport"]).type
        config.aetherScanMode = AetherScanMode.fromString(queryParam["scan"]).type
        config.aetherObfuscation = AetherObfuscation.fromString(queryParam["noize"]).type
        config.aetherIpVersion = AetherIpVersion.fromString(queryParam["ip"]).type
        config.aetherFragment = queryParam["fragment"] == "1"

        if (protocol == AetherProtocol.GOOL) {
            val outer = AetherEndpoint.parse(queryParam["outer"])
            val inner = AetherEndpoint.parse(queryParam["inner"])?.takeUnless { it.host == outer?.host }
            config.aetherWiwOuter = outer?.toString()
            config.aetherWiwInner = inner?.toString()
        } else {
            val endpoint = AetherEndpoint.of(uri.idnHost, uri.port.takeIf { it > 0 }?.toString())
            config.server = endpoint?.host
            config.serverPort = endpoint?.port?.toString()
        }

        return config
    }

    fun toUri(config: ProfileItem): String {
        val protocol = AetherProtocol.fromString(config.aetherProtocol)
        val query = linkedMapOf(
            "protocol" to protocol.type,
            "scan" to AetherScanMode.fromString(config.aetherScanMode).type,
            "noize" to AetherObfuscation.fromString(config.aetherObfuscation).type,
            "ip" to AetherIpVersion.fromString(config.aetherIpVersion).type,
        )
        if (protocol == AetherProtocol.MASQUE) {
            query["transport"] = AetherTransport.fromString(config.aetherTransport).type
            if (config.aetherFragment == true) query["fragment"] = "1"
        }
        if (protocol == AetherProtocol.GOOL) {
            AetherEndpoint.parse(config.aetherWiwOuter)?.let { query["outer"] = it.toString() }
            AetherEndpoint.parse(config.aetherWiwInner)?.let { query["inner"] = it.toString() }
        }
        val endpoint = AetherEndpoint.of(config.server, config.serverPort).takeUnless { protocol == AetherProtocol.GOOL }

        val queryText = query.entries.joinToString("&") { "${it.key}=${Utils.encodeURIComponent(it.value)}" }
        return "${endpoint ?: ""}?$queryText#${Utils.encodeURIComponent(config.remarks)}"
    }

    fun normalizeEndpoints(config: ProfileItem): EndpointProblem? {
        if (AetherProtocol.fromString(config.aetherProtocol) == AetherProtocol.GOOL) {
            val outerText = config.aetherWiwOuter?.trim().orEmpty()
            val innerText = config.aetherWiwInner?.trim().orEmpty()
            val outer = AetherEndpoint.parse(outerText)
            val inner = AetherEndpoint.parse(innerText)
            if (outerText.isNotEmpty() && outer == null || innerText.isNotEmpty() && inner == null) {
                return EndpointProblem.INVALID_HOP
            }
            if (outer != null && inner != null && outer.host == inner.host) {
                return EndpointProblem.SHARED_HOP
            }
            config.aetherWiwOuter = outer?.toString()
            config.aetherWiwInner = inner?.toString()
            config.server = null
            config.serverPort = null
            return null
        }

        val address = config.server?.trim().orEmpty()
        val endpoint = AetherEndpoint.of(address, config.serverPort)
        if (address.isNotEmpty() && endpoint == null) {
            return EndpointProblem.INVALID_PEER
        }
        config.server = endpoint?.host
        config.serverPort = endpoint?.port?.toString()
        config.aetherWiwOuter = null
        config.aetherWiwInner = null
        return null
    }
}
