package com.v2ray.ang.fmt

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherIpVersion
import com.v2ray.ang.enums.AetherObfuscation
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.AetherScanMode
import com.v2ray.ang.enums.AetherTransport
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AetherFmtTest {

    private fun link(config: ProfileItem) =
        EConfigType.AETHER.protocolScheme + AetherFmt.toUri(config)

    private fun profile(block: ProfileItem.() -> Unit) =
        ProfileItem.create(EConfigType.AETHER).apply {
            remarks = "My Node"
            aetherProtocol = AetherProtocol.MASQUE.type
            aetherTransport = AetherTransport.HTTP3.type
            aetherScanMode = AetherScanMode.BALANCED.type
            aetherObfuscation = AetherObfuscation.BALANCED.type
            aetherIpVersion = AetherIpVersion.V4.type
            block()
        }

    @Test
    fun aPinnedMasqueNodeSurvivesTheRoundTrip() {
        val original = profile {
            server = "162.159.198.1"
            serverPort = "443"
            aetherTransport = AetherTransport.HTTP2.type
            aetherScanMode = AetherScanMode.STEALTH.type
            aetherObfuscation = AetherObfuscation.AGGRESSIVE.type
            aetherIpVersion = AetherIpVersion.DUAL.type
            aetherFragment = true
        }

        val parsed = AetherFmt.parse(link(original))

        assertNotNull(parsed)
        assertEquals(EConfigType.AETHER, parsed?.configType)
        assertEquals("My Node", parsed?.remarks)
        assertEquals("162.159.198.1", parsed?.server)
        assertEquals("443", parsed?.serverPort)
        assertEquals("masque", parsed?.aetherProtocol)
        assertEquals("h2", parsed?.aetherTransport)
        assertEquals("stealth", parsed?.aetherScanMode)
        assertEquals("aggressive", parsed?.aetherObfuscation)
        assertEquals("both", parsed?.aetherIpVersion)
        assertEquals(true, parsed?.aetherFragment)
    }

    @Test
    fun bothGoolHopsSurviveTheRoundTrip() {
        val original = profile {
            remarks = "Gool"
            aetherProtocol = AetherProtocol.GOOL.type
            aetherWiwOuter = "162.159.192.1:2408"
            aetherWiwInner = "[2606:4700:d0::a29f:c001]:894"
        }

        val parsed = AetherFmt.parse(link(original))

        assertEquals("gool", parsed?.aetherProtocol)
        assertEquals("162.159.192.1:2408", parsed?.aetherWiwOuter)
        assertEquals("[2606:4700:d0::a29f:c001]:894", parsed?.aetherWiwInner)
        assertNull(parsed?.server)
        assertNull(parsed?.serverPort)
    }

    @Test
    fun aNodeLeftToTheScannerCarriesNoEndpoint() {
        val uri = link(profile {})

        assertTrue("uri should hold only a query: $uri", uri.startsWith("aether://?"))

        val parsed = AetherFmt.parse(uri)
        assertNull(parsed?.server)
        assertNull(parsed?.serverPort)
        assertEquals("masque", parsed?.aetherProtocol)
    }

    @Test
    fun anIpv6EndpointSurvivesTheRoundTrip() {
        val uri = link(profile {
            server = "2606:4700:d0::a29f:c001"
            serverPort = "443"
        })
        assertTrue("uri should bracket the address: $uri", uri.startsWith("aether://[2606:4700:d0::a29f:c001]:443?"))

        val parsed = AetherFmt.parse(uri)
        assertEquals("2606:4700:d0::a29f:c001", parsed?.server)
        assertEquals("443", parsed?.serverPort)
    }

    @Test
    fun theHopsOnlyRideAlongWithGool() {
        val uri = link(profile {
            aetherWiwOuter = "162.159.192.1:2408"
            aetherWiwInner = "188.114.96.1:894"
        })

        assertFalse(uri.contains("outer="))
        assertFalse(uri.contains("inner="))
    }

    @Test
    fun theEndpointNeverRidesAlongWithGool() {
        val uri = link(profile {
            aetherProtocol = AetherProtocol.GOOL.type
            server = "162.159.198.1"
            serverPort = "443"
        })

        assertTrue("uri should hold only a query: $uri", uri.startsWith("aether://?"))
        assertNull(AetherFmt.parse("aether://162.159.198.1:443?protocol=gool#X")?.server)
    }

    @Test
    fun theTransportOnlyRidesAlongWithMasque() {
        val uri = link(profile {
            aetherProtocol = AetherProtocol.WIREGUARD.type
            aetherTransport = AetherTransport.HTTP2.type
            aetherFragment = true
        })

        assertFalse(uri.contains("transport="))
        assertFalse(uri.contains("fragment="))
        assertEquals("wg", AetherFmt.parse(uri)?.aetherProtocol)
    }

    @Test
    fun aLinkWithoutSettingsFallsBackToTheDefaults() {
        val parsed = AetherFmt.parse("aether://#Shared")

        assertEquals("Shared", parsed?.remarks)
        assertEquals("masque", parsed?.aetherProtocol)
        assertEquals("h3", parsed?.aetherTransport)
        assertEquals("balanced", parsed?.aetherScanMode)
        assertEquals("balanced", parsed?.aetherObfuscation)
        assertEquals("v4", parsed?.aetherIpVersion)
        assertEquals(false, parsed?.aetherFragment)
    }

    @Test
    fun aNamelessLinkStillGetsALabel() {
        assertEquals("Aether", AetherFmt.parse("aether://?protocol=wg")?.remarks)
    }

    @Test
    fun anUnknownSettingFallsBackInsteadOfFailing() {
        val parsed = AetherFmt.parse("aether://?protocol=quantum&scan=instant&ip=v9#X")

        assertEquals("masque", parsed?.aetherProtocol)
        assertEquals("balanced", parsed?.aetherScanMode)
        assertEquals("v4", parsed?.aetherIpVersion)
    }

    @Test
    fun anEndpointTheCoreCannotUseIsDroppedFromALink() {
        val hostname = AetherFmt.parse("aether://engage.cloudflareclient.com:2408?protocol=wg#X")
        assertNull(hostname?.server)
        assertNull(hostname?.serverPort)

        val portless = AetherFmt.parse("aether://162.159.198.1?protocol=masque#X")
        assertNull(portless?.server)
        assertNull(portless?.serverPort)
    }

    @Test
    fun goolHopsFromALinkAreCheckedAndNormalized() {
        val parsed = AetherFmt.parse(
            "aether://?protocol=gool&outer=162.159.192.1%3A2408&inner=%5B2606%3A4700%3A0%3A0%3A0%3A0%3A0%3A1%5D%3A894#X"
        )
        assertEquals("162.159.192.1:2408", parsed?.aetherWiwOuter)
        assertEquals("[2606:4700::1]:894", parsed?.aetherWiwInner)

        val malformed = AetherFmt.parse("aether://?protocol=gool&outer=162.159.192.1&inner=example.com%3A894#X")
        assertNull(malformed?.aetherWiwOuter)
        assertNull(malformed?.aetherWiwInner)

        val shared = AetherFmt.parse("aether://?protocol=gool&outer=162.159.192.1%3A2408&inner=162.159.192.1%3A894#X")
        assertEquals("162.159.192.1:2408", shared?.aetherWiwOuter)
        assertNull(shared?.aetherWiwInner)
    }

    @Test
    fun anEmptyEndpointMeansScanning() {
        val config = profile {
            server = "  "
            serverPort = "443"
            aetherWiwOuter = "162.159.192.1:2408"
        }

        assertNull(AetherFmt.normalizeEndpoints(config))
        assertNull(config.server)
        assertNull(config.serverPort)
        assertNull(config.aetherWiwOuter)
    }

    @Test
    fun aPinnedEndpointIsNormalizedBeforeItIsSaved() {
        val config = profile {
            server = " [2606:4700:0:0:0:0:0:1] "
            serverPort = " 0443 "
        }

        assertNull(AetherFmt.normalizeEndpoints(config))
        assertEquals("2606:4700::1", config.server)
        assertEquals("443", config.serverPort)
    }

    @Test
    fun aPinnedEndpointTheCoreCannotUseIsRejected() {
        assertEquals(
            AetherFmt.EndpointProblem.INVALID_PEER,
            AetherFmt.normalizeEndpoints(profile { server = "engage.cloudflareclient.com"; serverPort = "2408" })
        )
        assertEquals(
            AetherFmt.EndpointProblem.INVALID_PEER,
            AetherFmt.normalizeEndpoints(profile { server = "162.159.198.1"; serverPort = "" })
        )
        assertEquals(
            AetherFmt.EndpointProblem.INVALID_PEER,
            AetherFmt.normalizeEndpoints(profile { server = "162.159.198.1"; serverPort = "70000" })
        )
    }

    @Test
    fun goolHopsAreNormalizedAndTheEndpointIsCleared() {
        val config = profile {
            aetherProtocol = AetherProtocol.GOOL.type
            server = "162.159.198.1"
            serverPort = "443"
            aetherWiwOuter = " 162.159.192.1:2408 "
            aetherWiwInner = ""
        }

        assertNull(AetherFmt.normalizeEndpoints(config))
        assertEquals("162.159.192.1:2408", config.aetherWiwOuter)
        assertNull(config.aetherWiwInner)
        assertNull(config.server)
        assertNull(config.serverPort)
    }

    @Test
    fun aGoolHopTheCoreCannotUseIsRejected() {
        assertEquals(
            AetherFmt.EndpointProblem.INVALID_HOP,
            AetherFmt.normalizeEndpoints(profile { aetherProtocol = AetherProtocol.GOOL.type; aetherWiwOuter = "162.159.192.1" })
        )
        assertEquals(
            AetherFmt.EndpointProblem.INVALID_HOP,
            AetherFmt.normalizeEndpoints(profile { aetherProtocol = AetherProtocol.GOOL.type; aetherWiwInner = "2606:4700::1:894" })
        )
    }

    @Test
    fun goolHopsMustLeaveThroughDifferentAddresses() {
        val config = profile {
            aetherProtocol = AetherProtocol.GOOL.type
            aetherWiwOuter = "162.159.192.1:2408"
            aetherWiwInner = "162.159.192.1:894"
        }

        assertEquals(AetherFmt.EndpointProblem.SHARED_HOP, AetherFmt.normalizeEndpoints(config))
        assertEquals("162.159.192.1:2408", config.aetherWiwOuter)
        assertEquals("162.159.192.1:894", config.aetherWiwInner)
    }
}
