package com.v2ray.ang.enums

enum class AetherProtocol(val type: String) {
    MASQUE("masque"),
    WIREGUARD("wg"),
    GOOL("gool");

    companion object {
        fun fromString(type: String?) = entries.find { it.type == type } ?: MASQUE
    }
}

enum class AetherTransport(val type: String) {
    HTTP3("h3"),
    HTTP2("h2");

    companion object {
        fun fromString(type: String?) = entries.find { it.type == type } ?: HTTP3
    }
}

enum class AetherScanMode(val type: String) {
    TURBO("turbo"),
    BALANCED("balanced"),
    THOROUGH("thorough"),
    STEALTH("stealth"),
    IRONCLAD("ironclad");

    companion object {
        fun fromString(type: String?) = entries.find { it.type == type } ?: BALANCED
    }
}

enum class AetherObfuscation(val type: String) {
    OFF("off"),
    LIGHT("light"),
    BALANCED("balanced"),
    AGGRESSIVE("aggressive");

    companion object {
        fun fromString(type: String?) = entries.find { it.type == type } ?: BALANCED
    }
}

enum class AetherIpVersion(val type: String) {
    V4("v4"),
    V6("v6"),
    DUAL("both");

    companion object {
        fun fromString(type: String?) = entries.find { it.type == type } ?: V4
    }
}
