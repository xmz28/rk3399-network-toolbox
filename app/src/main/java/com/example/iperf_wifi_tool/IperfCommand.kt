package com.example.iperf_wifi_tool

enum class TestProtocol { TCP, UDP }
enum class TestDirection { UPLOAD, DOWNLOAD }

data class TestConfig(
    val host: String,
    val port: Int = 5201,
    val protocol: TestProtocol = TestProtocol.TCP,
    val direction: TestDirection = TestDirection.UPLOAD,
    val streams: Int = 1,
    val totalUdpMbps: Int = 30,
    val durationSeconds: Int = 30,
)

object IperfCommand {
    fun arguments(executable: String, config: TestConfig): List<String> {
        val args = mutableListOf(
            executable,
            "-c", config.host,
            "-p", config.port.coerceIn(1, 65535).toString(),
            "-P", config.streams.coerceIn(1, 32).toString(),
            "-t", config.durationSeconds.coerceAtLeast(1).toString(),
            "-i", "1",
            "--forceflush",
        )
        if (config.direction == TestDirection.DOWNLOAD) args += "-R"
        if (config.protocol == TestProtocol.UDP) {
            val perStream = config.totalUdpMbps.toDouble() / config.streams.coerceAtLeast(1)
            args += listOf("-u", "-b", formatMbps(perStream))
        }
        return args
    }

    fun display(config: TestConfig): String =
        arguments("iperf3", config).joinToString(" ")

    private fun formatMbps(value: Double): String {
        val text = if (value % 1.0 == 0.0) value.toInt().toString()
        else "%.3f".format(java.util.Locale.US, value).trimEnd('0').trimEnd('.')
        return "${text}M"
    }
}

object IperfOutputParser {
    private val bandwidth = Regex("([0-9.]+)\\s+([KMGT])bits/sec", RegexOption.IGNORE_CASE)
    private val loss = Regex("\\(([0-9.]+)%\\)")
    private val jitter = Regex("([0-9.]+)\\s+ms\\s+\\d+/\\d+")

    fun bandwidthMbps(line: String): Double? {
        val match = bandwidth.find(line) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        return when (match.groupValues[2].uppercase()) {
            "T" -> value * 1_000_000
            "G" -> value * 1_000
            "M" -> value
            "K" -> value / 1_000
            else -> null
        }
    }

    fun lossPercent(line: String): Double? = loss.find(line)?.groupValues?.get(1)?.toDoubleOrNull()
    fun jitterMs(line: String): Double? = jitter.find(line)?.groupValues?.get(1)?.toDoubleOrNull()
}
