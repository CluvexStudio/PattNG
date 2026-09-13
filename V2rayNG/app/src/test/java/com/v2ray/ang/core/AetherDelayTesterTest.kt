package com.v2ray.ang.core

import com.v2ray.ang.core.AetherDelayTester.Route
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class AetherDelayTesterTest {

    private fun aether(protocol: AetherProtocol) =
        ProfileItem.create(EConfigType.AETHER).apply { aetherProtocol = protocol.type }

    private fun session(protocol: AetherProtocol, running: ProfileItem? = null, listening: Boolean = true) =
        AetherDelayTester.LiveSession(protocol, running?.let { AetherCoreManager.buildArguments(it, AetherCoreManager.socksPort) }, listening)

    @Test
    fun withoutAnAetherSessionEachTestGetsItsOwnTunnel() {
        val masque = aether(AetherProtocol.MASQUE)
        assertEquals(Route.NEW_TUNNEL, AetherDelayTester.route("a", masque, "a", session = null))
        assertEquals(Route.NEW_TUNNEL, AetherDelayTester.route("a", masque, null, session = null))
    }

    @Test
    fun theRunningProfileIsMeasuredThroughTheLiveSession() {
        val wireguard = aether(AetherProtocol.WIREGUARD).apply { server = "162.159.192.1"; serverPort = "2408" }
        // Told by the session's arguments, whichever profile is selected.
        assertEquals(Route.ACTIVE_SESSION, AetherDelayTester.route("a", wireguard, "b", session(AetherProtocol.WIREGUARD, wireguard)))
        // Without the process, the selected profile stands in for the running one.
        assertEquals(Route.ACTIVE_SESSION, AetherDelayTester.route("a", wireguard, "a", session(AetherProtocol.WIREGUARD)))
        assertEquals(Route.SKIP, AetherDelayTester.route("a", wireguard, "b", session(AetherProtocol.WIREGUARD)))
    }

    @Test
    fun theRunningProfileIsLeftUntestedWhileItsSessionIsStillConnecting() {
        val wireguard = aether(AetherProtocol.WIREGUARD).apply { server = "162.159.192.1"; serverPort = "2408" }
        val connecting = session(AetherProtocol.WIREGUARD, wireguard, listening = false)
        // A request through a listener that is not up yet would fail, and that is not a failure of the profile.
        assertEquals(Route.NOT_READY, AetherDelayTester.route("a", wireguard, "a", connecting))
        // The other routes do not depend on the listener.
        assertEquals(Route.SKIP, AetherDelayTester.route("b", aether(AetherProtocol.GOOL), "a", connecting))
        assertEquals(Route.NEW_TUNNEL, AetherDelayTester.route("b", aether(AetherProtocol.MASQUE), "a", connecting))
    }

    @Test
    fun aProfileSharingTheLiveSessionsKeyIsLeftAlone() {
        val running = aether(AetherProtocol.MASQUE).apply { server = "162.159.198.1"; serverPort = "443" }
        val live = session(AetherProtocol.MASQUE, running)
        assertEquals(Route.SKIP, AetherDelayTester.route("b", aether(AetherProtocol.MASQUE), "a", live))
        // Selected, but not what the session runs: it is not measured through that session.
        assertEquals(Route.SKIP, AetherDelayTester.route("b", aether(AetherProtocol.MASQUE), "b", live))
        assertEquals(Route.SKIP, AetherDelayTester.route("b", aether(AetherProtocol.GOOL), "a", session(AetherProtocol.WIREGUARD)))
        assertEquals(Route.SKIP, AetherDelayTester.route("b", aether(AetherProtocol.WIREGUARD), "a", session(AetherProtocol.GOOL)))
    }

    @Test
    fun aProfileWithADifferentKeyGetsItsOwnTunnel() {
        assertEquals(Route.NEW_TUNNEL, AetherDelayTester.route("b", aether(AetherProtocol.MASQUE), "a", session(AetherProtocol.WIREGUARD)))
        assertEquals(Route.NEW_TUNNEL, AetherDelayTester.route("b", aether(AetherProtocol.GOOL), "a", session(AetherProtocol.MASQUE)))
    }

    @Test
    fun aTcpPingProbesThePinnedEdgeInsteadOfOpeningATunnel() {
        val probed = mutableListOf<Pair<String, Int>>()
        val connect = { host: String, port: Int -> probed.add(host to port); 42L }

        val pinned = aether(AetherProtocol.MASQUE).apply { server = "162.159.198.1"; serverPort = "2408" }
        assertEquals(42L, AetherDelayTester.reachability(pinned, connect))
        assertEquals(listOf("162.159.198.1" to 443), probed)

        probed.clear()
        val hops = aether(AetherProtocol.GOOL).apply { aetherWiwOuter = "162.159.192.1:2408"; aetherWiwInner = "188.114.96.1:894" }
        assertEquals(42L, AetherDelayTester.reachability(hops, connect))
        assertEquals(listOf("162.159.192.1" to 443), probed)

        probed.clear()
        val unreachable = { _: String, _: Int -> -1L }
        assertEquals(-1L, AetherDelayTester.reachability(pinned, unreachable))
    }

    @Test
    fun aProfileLeftToTheScannerHasNothingToProbe() {
        val probes = { _: String, _: Int -> throw AssertionError("must not probe") }

        assertEquals(AetherDelayTester.UNTESTED, AetherDelayTester.reachability(aether(AetherProtocol.MASQUE), probes))
        assertEquals(AetherDelayTester.UNTESTED, AetherDelayTester.reachability(aether(AetherProtocol.GOOL), probes))
        val halfPinned = aether(AetherProtocol.WIREGUARD).apply { server = "162.159.198.1" }
        assertEquals(AetherDelayTester.UNTESTED, AetherDelayTester.reachability(halfPinned, probes))
        val hostName = aether(AetherProtocol.WIREGUARD).apply { server = "engage.cloudflareclient.com"; serverPort = "2408" }
        assertEquals(AetherDelayTester.UNTESTED, AetherDelayTester.reachability(hostName, probes))
        assertEquals(0L, AetherDelayTester.UNTESTED)
    }

    @Test
    fun aDelayIsMeasuredThroughTheSocksPort() {
        HttpStub("204 No Content").use { http ->
            SocksStub().use { socks ->
                val delay = AetherDelayTester.requestDelay(socks.port, "http://127.0.0.1:${http.port}/generate_204")
                assertTrue("delay was $delay", delay >= 0)
            }
        }
    }

    @Test
    fun aPlainOkAlsoCountsAsAnAnswer() {
        HttpStub("200 OK").use { http ->
            SocksStub().use { socks ->
                assertTrue(AetherDelayTester.requestDelay(socks.port, "http://127.0.0.1:${http.port}/") >= 0)
            }
        }
    }

    @Test
    fun anErrorStatusIsNotADelay() {
        HttpStub("500 Internal Server Error").use { http ->
            SocksStub().use { socks ->
                assertEquals(-1L, AetherDelayTester.requestDelay(socks.port, "http://127.0.0.1:${http.port}/"))
            }
        }
    }

    @Test
    fun nothingListeningOrABrokenUrlIsNotADelay() {
        val closedPort = ServerSocket(0).use { it.localPort }
        assertEquals(-1L, AetherDelayTester.requestDelay(closedPort, "http://127.0.0.1:1/generate_204"))
        assertEquals(-1L, AetherDelayTester.requestDelay(closedPort, "not a url"))
    }

    private class HttpStub(private val status: String) : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true) {
                while (true) {
                    val client = try {
                        server.accept()
                    } catch (_: IOException) {
                        return@thread
                    }
                    thread(isDaemon = true) { answer(client) }
                }
            }
        }

        private fun answer(client: Socket) = client.use { socket ->
            val reader = socket.getInputStream().bufferedReader()
            while (reader.readLine()?.isNotEmpty() == true) Unit
            val response = "HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(response.toByteArray())
        }

        override fun close() = server.close()
    }

    private class SocksStub : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true) {
                while (true) {
                    val client = try {
                        server.accept()
                    } catch (_: IOException) {
                        return@thread
                    }
                    thread(isDaemon = true) { runCatching { relay(client) } }
                }
            }
        }

        private fun relay(client: Socket) = client.use {
            val input = DataInputStream(client.getInputStream())
            val output = client.getOutputStream()
            input.readByte()
            repeat(input.readUnsignedByte()) { input.readByte() }
            output.write(byteArrayOf(5, 0))
            repeat(3) { input.readByte() }
            val host = when (input.readUnsignedByte()) {
                1 -> InetAddress.getByAddress(ByteArray(4).also(input::readFully)).hostAddress
                3 -> String(ByteArray(input.readUnsignedByte()).also(input::readFully))
                else -> return@use
            }
            val targetPort = input.readUnsignedShort()
            Socket(host, targetPort).use { target ->
                output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                thread(isDaemon = true) {
                    runCatching { client.getInputStream().copyTo(target.getOutputStream()) }
                }
                runCatching { target.getInputStream().copyTo(output) }
            }
        }

        override fun close() = server.close()
    }
}
