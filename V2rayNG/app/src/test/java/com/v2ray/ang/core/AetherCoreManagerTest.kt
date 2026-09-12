package com.v2ray.ang.core

import android.util.Log
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherIpVersion
import com.v2ray.ang.enums.AetherObfuscation
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.AetherScanMode
import com.v2ray.ang.enums.AetherTransport
import com.v2ray.ang.enums.EConfigType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AetherCoreManagerTest {

    private fun profile(
        protocol: AetherProtocol = AetherProtocol.MASQUE,
        transport: AetherTransport = AetherTransport.HTTP3,
        server: String? = null,
        port: String? = null,
        outer: String? = null,
        inner: String? = null,
        fragment: Boolean? = null,
    ) = ProfileItem(
        configType = EConfigType.AETHER,
        remarks = "test",
        server = server,
        serverPort = port,
        aetherProtocol = protocol.type,
        aetherTransport = transport.type,
        aetherScanMode = AetherScanMode.STEALTH.type,
        aetherObfuscation = AetherObfuscation.AGGRESSIVE.type,
        aetherIpVersion = AetherIpVersion.DUAL.type,
        aetherWiwOuter = outer,
        aetherWiwInner = inner,
        aetherFragment = fragment,
    )

    private fun valueAfter(arguments: List<String>, flag: String): String? =
        arguments.indexOf(flag).takeIf { it >= 0 }?.let { arguments.getOrNull(it + 1) }

    @Test
    fun bindsToTheLoopbackPortItIsGiven() {
        assertEquals("127.0.0.1:10819", valueAfter(AetherCoreManager.buildArguments(profile(), 10819), "--bind"))
        assertEquals("127.0.0.1:0", valueAfter(AetherCoreManager.buildArguments(profile(), 0, scan = true), "--bind"))
    }

    @Test
    fun theChosenModesAreForwarded() {
        val arguments = AetherCoreManager.buildArguments(profile(), 10819)
        assertEquals("masque", valueAfter(arguments, "--protocol"))
        assertEquals("stealth", valueAfter(arguments, "--scan"))
        assertEquals("aggressive", valueAfter(arguments, "--noize"))
        assertEquals("both", valueAfter(arguments, "--ip"))
        assertEquals("info", valueAfter(arguments, "--log-level"))
    }

    @Test
    fun eachProtocolIsNamedToTheCore() {
        assertEquals("wg", valueAfter(AetherCoreManager.buildArguments(profile(AetherProtocol.WIREGUARD), 10819), "--protocol"))
        assertEquals("gool", valueAfter(AetherCoreManager.buildArguments(profile(AetherProtocol.GOOL), 10819), "--protocol"))
    }

    @Test
    fun http2AndFragmentationOnlyApplyToMasque() {
        assertFalse(AetherCoreManager.buildArguments(profile(fragment = true), 10819).contains("--h2"))
        assertFalse(AetherCoreManager.buildArguments(profile(fragment = true), 10819).contains("--fragment"))

        val http2 = AetherCoreManager.buildArguments(profile(transport = AetherTransport.HTTP2), 10819)
        assertTrue(http2.contains("--h2"))
        assertFalse(http2.contains("--fragment"))

        val fragmented = AetherCoreManager.buildArguments(profile(transport = AetherTransport.HTTP2, fragment = true), 10819)
        assertTrue(fragmented.contains("--fragment"))

        val wireguard = AetherCoreManager.buildArguments(
            profile(AetherProtocol.WIREGUARD, AetherTransport.HTTP2, fragment = true),
            10819
        )
        assertFalse(wireguard.contains("--h2"))
        assertFalse(wireguard.contains("--fragment"))
    }

    @Test
    fun fragmentValuesReachTheCoreOnlyWhenFragmentingIsOn() {
        val tuned = profile(transport = AetherTransport.HTTP2, fragment = true).apply {
            aetherFragmentSize = "32-16"
            aetherFragmentDelay = "5"
        }
        val arguments = AetherCoreManager.buildArguments(tuned, 10819)
        assertEquals("16-32", valueAfter(arguments, "--fragment-size"))
        assertEquals("5", valueAfter(arguments, "--fragment-delay"))

        val off = AetherCoreManager.buildArguments(tuned.copy(aetherFragment = false), 10819)
        assertFalse(off.contains("--fragment-size"))
        assertFalse(off.contains("--fragment-delay"))

        val invalid = AetherCoreManager.buildArguments(tuned.copy(aetherFragmentSize = "0", aetherFragmentDelay = "x"), 10819)
        assertTrue(invalid.contains("--fragment"))
        assertFalse(invalid.contains("--fragment-size"))
        assertFalse(invalid.contains("--fragment-delay"))
    }

    @Test
    fun aPinnedEndpointIsForwardedInTheCoreFormat() {
        assertEquals(
            "162.159.198.1:443",
            valueAfter(AetherCoreManager.buildArguments(profile(server = "162.159.198.1", port = "443"), 10819), "--peer")
        )
        assertEquals(
            "[2606:4700:d0::a29f:c001]:2408",
            valueAfter(
                AetherCoreManager.buildArguments(
                    profile(AetherProtocol.WIREGUARD, server = "2606:4700:d0::a29f:c001", port = "2408"),
                    10819
                ),
                "--peer"
            )
        )
    }

    @Test
    fun anEndpointTheCoreCannotReadIsLeftToTheScan() {
        assertNull(valueAfter(AetherCoreManager.buildArguments(profile(), 10819), "--peer"))
        assertNull(valueAfter(AetherCoreManager.buildArguments(profile(server = "162.159.198.1"), 10819), "--peer"))
        assertNull(valueAfter(AetherCoreManager.buildArguments(profile(port = "443"), 10819), "--peer"))
        assertNull(valueAfter(AetherCoreManager.buildArguments(profile(server = "162.159.198.1", port = "0"), 10819), "--peer"))
        assertNull(
            valueAfter(AetherCoreManager.buildArguments(profile(server = "engage.cloudflareclient.com", port = "2408"), 10819), "--peer")
        )
    }

    @Test
    fun bothGoolHopsReachTheCoreWhenNamedByHand() {
        val arguments = AetherCoreManager.buildArguments(
            profile(AetherProtocol.GOOL, outer = "162.159.192.1:2408", inner = "188.114.96.1:894"),
            10819
        )
        assertEquals("162.159.192.1:2408", valueAfter(arguments, "--wiw-outer"))
        assertEquals("188.114.96.1:894", valueAfter(arguments, "--wiw-inner"))
        assertFalse(arguments.contains("--wiw-scan"))
        assertFalse(arguments.contains("--peer"))
    }

    @Test
    fun namingOneGoolHopLeavesTheOtherToTheScan() {
        val arguments = AetherCoreManager.buildArguments(profile(AetherProtocol.GOOL, inner = "188.114.96.1:894"), 10819)
        assertNull(valueAfter(arguments, "--wiw-outer"))
        assertEquals("188.114.96.1:894", valueAfter(arguments, "--wiw-inner"))
        assertFalse(arguments.contains("--wiw-scan"))
    }

    @Test
    fun goolScansForHopsItCannotUse() {
        assertTrue(AetherCoreManager.buildArguments(profile(AetherProtocol.GOOL), 10819).contains("--wiw-scan"))

        val malformed = AetherCoreManager.buildArguments(profile(AetherProtocol.GOOL, outer = "162.159.192.1"), 10819)
        assertNull(valueAfter(malformed, "--wiw-outer"))
        assertTrue(malformed.contains("--wiw-scan"))

        val pinnedEndpoint = AetherCoreManager.buildArguments(
            profile(AetherProtocol.GOOL, server = "162.159.198.1", port = "443"),
            10819
        )
        assertFalse(pinnedEndpoint.contains("--peer"))
        assertTrue(pinnedEndpoint.contains("--wiw-scan"))
    }

    @Test
    fun aRunReusesTheLastGatewayButAScanLooksAfresh() {
        val run = AetherCoreManager.buildArguments(profile(server = "162.159.198.1", port = "443"), 10819)
        assertTrue(run.contains("--quick-reconnect"))
        assertFalse(run.contains("--no-quick-reconnect"))

        val scan = AetherCoreManager.buildArguments(profile(server = "162.159.198.1", port = "443"), 0, scan = true)
        assertTrue(scan.contains("--no-quick-reconnect"))
        assertFalse(scan.contains("--quick-reconnect"))
        assertFalse(scan.contains("--peer"))
    }

    @Test
    fun aGoolScanIgnoresTheHopsItWasGiven() {
        val scan = AetherCoreManager.buildArguments(
            profile(AetherProtocol.GOOL, outer = "162.159.192.1:2408", inner = "188.114.96.1:894"),
            0,
            scan = true
        )
        assertFalse(scan.contains("--wiw-outer"))
        assertFalse(scan.contains("--wiw-inner"))
        assertTrue(scan.contains("--wiw-scan"))
    }

    @Test
    fun coreOutputKeepsItsLogLevel() {
        assertEquals(Log.ERROR, AetherCoreManager.outputPriority("[2026-09-11T10:00:00.000Z ERROR aether] config parse failed"))
        assertEquals(Log.WARN, AetherCoreManager.outputPriority("[2026-09-11T10:00:00.000Z WARN  aether] [-] tunnel ended"))
        assertEquals(Log.INFO, AetherCoreManager.outputPriority("[2026-09-11T10:00:00.000Z INFO  aether] [+] identity ready"))
        assertEquals(Log.DEBUG, AetherCoreManager.outputPriority("[2026-09-11T10:00:00.000Z DEBUG aether::quic] packet"))
        assertEquals(Log.DEBUG, AetherCoreManager.outputPriority("[2026-09-11T10:00:00.000Z TRACE aether] packet"))
    }

    @Test
    fun theTestWaitsUntilTheCoreStartsListening() = runBlocking {
        var polls = 0
        assertTrue(AetherCoreManager.awaitReady(5_000, 10, { true }, { ++polls >= 3 }))
        assertEquals(3, polls)
    }

    @Test
    fun theTestGivesUpWhenTheCoreStopsOrTakesTooLong() = runBlocking {
        assertFalse(AetherCoreManager.awaitReady(5_000, 10, { false }, { true }))

        var polls = 0
        assertFalse(AetherCoreManager.awaitReady(5_000, 10, { polls < 2 }, { polls++; false }))

        assertFalse(AetherCoreManager.awaitReady(100, 10, { true }, { false }))
    }

    @Test
    fun aFatalErrorFromTheCoreIsAnError() {
        assertEquals(Log.ERROR, AetherCoreManager.outputPriority("Error: Api(\"too many registrations\")"))
    }

    @Test
    fun theLogHeaderIsStrippedFromCoreOutput() {
        assertEquals(
            "[+] candidate ok 162.159.197.3:443 rtt=84ms",
            AetherCoreManager.outputMessage("[2026-09-11T10:00:00.000Z INFO  aether::prober] [+] candidate ok 162.159.197.3:443 rtt=84ms")
        )
        assertEquals("[+] selected protocol: MASQUE", AetherCoreManager.outputMessage("[+] selected protocol: MASQUE"))
        assertEquals("fallback over tcp 443", AetherCoreManager.outputMessage("  fallback over tcp 443  "))
        assertEquals("", AetherCoreManager.outputMessage("   "))
    }

    @Test
    fun outputWithoutALogHeaderIsInformational() {
        assertEquals(Log.INFO, AetherCoreManager.outputPriority("  fallback over tcp 443 (--masque-http2) on this network."))
        assertEquals(Log.INFO, AetherCoreManager.outputPriority("[unterminated header"))
        assertEquals(Log.INFO, AetherCoreManager.outputPriority("[]"))
    }

    @Test
    fun theSessionLogLevelFollowsTheAppSetting() {
        assertEquals("debug", AetherCoreManager.coreLogLevel("debug"))
        assertEquals("info", AetherCoreManager.coreLogLevel("info"))
        assertEquals("warn", AetherCoreManager.coreLogLevel("warning"))
        assertEquals("warn", AetherCoreManager.coreLogLevel("Warn"))
        assertEquals("error", AetherCoreManager.coreLogLevel("error"))
        assertEquals("error", AetherCoreManager.coreLogLevel("none"))
        assertEquals("info", AetherCoreManager.coreLogLevel(null))
        assertEquals("info", AetherCoreManager.coreLogLevel("verbose"))

        assertEquals("warn", valueAfter(AetherCoreManager.buildArguments(profile(), 10819, logLevel = "warn"), "--log-level"))
        assertEquals("info", valueAfter(AetherCoreManager.buildArguments(profile(), 0, scan = true), "--log-level"))
    }

    @Test
    fun aLeftoverCoreIsRecognisedByItsPortOrItsDeadOwner() {
        val session = listOf("/data/app/lib/libaether.so", "--bind", "127.0.0.1:10819", "--protocol", "masque")
        val scan = listOf("/data/app/lib/libaether.so", "--bind", "127.0.0.1:0", "--protocol", "masque")

        assertEquals("127.0.0.1:10819", AetherCoreManager.bindAddress(session))
        assertNull(AetherCoreManager.bindAddress(listOf("/data/app/lib/libaether.so", "--bind")))
        assertNull(AetherCoreManager.bindAddress(emptyList()))

        assertTrue(AetherCoreManager.isStale(session, ownerAlive = true, bindAddress = "127.0.0.1:10819"))
        assertTrue(AetherCoreManager.isStale(session, ownerAlive = null, bindAddress = "127.0.0.1:10819"))
        assertFalse(AetherCoreManager.isStale(scan, ownerAlive = true, bindAddress = "127.0.0.1:10819"))
        assertFalse(AetherCoreManager.isStale(scan, ownerAlive = null, bindAddress = "127.0.0.1:10819"))
        assertTrue(AetherCoreManager.isStale(scan, ownerAlive = false, bindAddress = "127.0.0.1:10819"))
        assertTrue(AetherCoreManager.isStale(scan, ownerAlive = false, bindAddress = null))
        assertFalse(AetherCoreManager.isStale(session, ownerAlive = true, bindAddress = null))
        assertFalse(AetherCoreManager.isStale(session, ownerAlive = null, bindAddress = null))
    }

    @Test
    fun theOwnerIsReadFromTheEnvironmentTheAppGaveTheCore() {
        val environ = listOf("HOME=/data/user/0/app/files/aether", "${AetherCoreManager.OWNER_ENV}=4242", "TMPDIR=/tmp")
        assertEquals(4242, AetherCoreManager.ownerPid(environ))
        assertNull(AetherCoreManager.ownerPid(listOf("HOME=/x", "${AetherCoreManager.OWNER_ENV}=")))
        assertNull(AetherCoreManager.ownerPid(listOf("HOME=/x")))
        assertNull(AetherCoreManager.ownerPid(null))
    }
}
