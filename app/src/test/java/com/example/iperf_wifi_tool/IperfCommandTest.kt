package com.example.iperf_wifi_tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IperfCommandTest {
    @Test fun tcpDownloadBuildsReverseCommand() {
        val args = IperfCommand.arguments("iperf3", TestConfig("192.168.1.100", direction = TestDirection.DOWNLOAD, streams = 10))
        assertTrue(args.containsAll(listOf("-c", "192.168.1.100", "-P", "10", "-R")))
    }

    @Test fun udpBandwidthIsTotalAcrossStreams() {
        val args = IperfCommand.arguments("iperf3", TestConfig("rk3399.local", protocol = TestProtocol.UDP, streams = 10, totalUdpMbps = 100))
        assertEquals("10M", args[args.indexOf("-b") + 1])
    }

    @Test fun parsesBandwidthUnits() {
        assertEquals(27.0, IperfOutputParser.bandwidthMbps("[  5] 0.00-1.00 sec 3.2 MBytes 27.0 Mbits/sec")!!, 0.001)
        assertEquals(1200.0, IperfOutputParser.bandwidthMbps("[SUM] 0.00-1.00 sec 1.20 Gbits/sec")!!, 0.001)
    }

    @Test fun rk3399ServicePortsMatchDeployment() {
        assertEquals(445, RkService.SAMBA.port)
        assertEquals(3923, RkService.HTTP.port)
        assertEquals(12345, RkService.FTP.port)
        assertEquals(1234, RkService.SFTP.port)
        assertEquals(3000, RkService.OPEN_SPEED_TEST.port)
        assertEquals(5201, RkService.IPERF3.port)
    }

    @Test fun mdnsQueryTargetsALocalIpv4() {
        val query = MdnsHostResolver.buildQuery("a.local")
        val encodedName = byteArrayOf(1, 'a'.code.toByte(), 5, 'l'.code.toByte(), 'o'.code.toByte(), 'c'.code.toByte(), 'a'.code.toByte(), 'l'.code.toByte(), 0)
        assertTrue(query.toList().windowed(encodedName.size).any { it == encodedName.toList() })
        assertEquals(listOf(0, 1, 128, 1), query.takeLast(4).map { it.toInt() and 0xff })
    }

    @Test fun remotePathsCannotEscapeRoot() {
        assertEquals("/video/demo.mp4", joinRemote("/video", "demo.mp4"))
        assertEquals("/", parentPath("/"))
        assertEquals("/video", parentPath("/video/sub"))
        assertEquals("/safe", normalizePath("/../../safe"))
    }
}
