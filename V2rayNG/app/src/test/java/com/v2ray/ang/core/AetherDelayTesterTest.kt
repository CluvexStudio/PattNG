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

    @Test
    fun withoutAnAetherSessionEachTestGetsItsOwnTunnel() {
        val masque = aether(AetherProtocol.MASQUE)
        assertEquals(Route.NEW_TUNNEL, AetherDelayTester.route("a", masque, "a", masque, sessionUp = false))
        assertEquals(Route.NEW_TUNNEL, AetherDelayTester.route("a", masque, null, null, sessionUp = true))
        assertEquals(
            Route.NEW_TUNNEL,
            AetherDelayTester.route("a", masque, "b", ProfileItem.create(EConfigType.VLESS), sessionUp = true)
        )
    }

    @Test
    fun theConnectedProfileIsMeasuredThroughItsOwnSession() {
        val wireguard = aether(AetherProtocol.WIREGUARD)
        assertEquals(Route.ACTIVE_SESSION, AetherDelayTester.route("a", wireguard, "a", wireguard, sessionUp = true))
    }

    @Test
    fun aProfileSharingTheConnectedKeyIsLeftAlone() {
        assertEquals(
            Route.SKIP,
            AetherDelayTester.route("b", aether(AetherProtocol.MASQUE), "a", aether(AetherProtocol.MASQUE), sessionUp = true)
        )
        assertEquals(
            Route.SKIP,
            AetherDelayTester.route("b", aether(AetherProtocol.GOOL), "a", aether(AetherProtocol.WIREGUARD), sessionUp = true)
        )
    }

    @Test
    fun aProfileWithADifferentKeyGetsItsOwnTunnel() {
        assertEquals(
            Route.NEW_TUNNEL,
            AetherDelayTester.route("b", aether(AetherProtocol.MASQUE), "a", aether(AetherProtocol.WIREGUARD), sessionUp = true)
        )
        assertEquals(
            Route.NEW_TUNNEL,
            AetherDelayTester.route("b", aether(AetherProtocol.GOOL), "a", aether(AetherProtocol.MASQUE), sessionUp = true)
        )
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
