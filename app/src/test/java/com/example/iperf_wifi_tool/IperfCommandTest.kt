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
}
