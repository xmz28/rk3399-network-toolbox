package com.example.iperf_wifi_tool

import android.content.Context
import android.net.ConnectivityManager
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

object MdnsHostResolver {
    fun resolve(context: Context, hostName: String, attempts: Int = 3): String? {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        repeat(attempts) { attempt ->
            val systemResult = try {
                val network = connectivity.activeNetwork
                val addresses = network?.getAllByName(hostName) ?: InetAddress.getAllByName(hostName)
                (addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull())?.hostAddress
            } catch (_: Exception) { null }
            if (!systemResult.isNullOrBlank()) return systemResult.substringBefore('%')

            val libcResult = try { resolveWithSystemPing(hostName) } catch (_: Exception) { null }
            if (!libcResult.isNullOrBlank()) return libcResult

            val mdnsResult = try { queryIpv4(hostName) } catch (_: Exception) { null }
            if (!mdnsResult.isNullOrBlank()) return mdnsResult
            if (attempt + 1 < attempts) Thread.sleep(500L)
        }
        return null
    }

    private fun resolveWithSystemPing(hostName: String): String? {
        val process = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "1", hostName)
            .redirectErrorStream(true)
            .start()
        val firstLines = process.inputStream.bufferedReader().use { reader ->
            buildString {
                repeat(4) {
                    val line = reader.readLine() ?: return@repeat
                    appendLine(line)
                }
            }
        }
        process.waitFor()
        return Regex("(?:\\(|from\\s+)(\\d{1,3}(?:\\.\\d{1,3}){3})").find(firstLines)?.groupValues?.get(1)
    }

    private fun queryIpv4(hostName: String): String? {
        val query = buildQuery(hostName)
        DatagramSocket().use { socket ->
            socket.soTimeout = 1200
            socket.send(DatagramPacket(query, query.size, InetAddress.getByName("224.0.0.251"), 5353))
            val deadline = System.currentTimeMillis() + 1200
            while (System.currentTimeMillis() < deadline) {
                val buffer = ByteArray(1500)
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                parseIpv4Response(buffer.copyOf(response.length))?.let { return it }
            }
        }
        return null
    }

    internal fun buildQuery(hostName: String): ByteArray {
        val out = ByteArrayOutputStream()
        repeat(2) { out.write(0) } // transaction ID
        repeat(2) { out.write(0) } // flags
        out.write(byteArrayOf(0, 1)) // one question
        repeat(6) { out.write(0) }
        hostName.trimEnd('.').split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.UTF_8)
            require(bytes.size in 1..63)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        out.write(byteArrayOf(0, 1)) // A record
        out.write(byteArrayOf(0x80.toByte(), 1)) // IN + unicast-response bit
        return out.toByteArray()
    }

    internal fun parseIpv4Response(packet: ByteArray): String? {
        if (packet.size < 12) return null
        val questionCount = u16(packet, 4)
        val recordCount = u16(packet, 6) + u16(packet, 8) + u16(packet, 10)
        var offset = 12
        repeat(questionCount) {
            offset = skipName(packet, offset) ?: return null
            if (offset + 4 > packet.size) return null
            offset += 4
        }
        repeat(recordCount) {
            offset = skipName(packet, offset) ?: return null
            if (offset + 10 > packet.size) return null
            val type = u16(packet, offset)
            val dataLength = u16(packet, offset + 8)
            offset += 10
            if (offset + dataLength > packet.size) return null
            if (type == 1 && dataLength == 4) {
                return (0 until 4).joinToString(".") { (packet[offset + it].toInt() and 0xff).toString() }
            }
            offset += dataLength
        }
        return null
    }

    private fun skipName(packet: ByteArray, start: Int): Int? {
        var offset = start
        while (offset < packet.size) {
            val length = packet[offset].toInt() and 0xff
            if (length == 0) return offset + 1
            if (length and 0xc0 == 0xc0) return if (offset + 1 < packet.size) offset + 2 else null
            if (length > 63 || offset + 1 + length > packet.size) return null
            offset += 1 + length
        }
        return null
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
}
