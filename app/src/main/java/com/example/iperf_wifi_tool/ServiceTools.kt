package com.example.iperf_wifi_tool

import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import kotlin.system.measureTimeMillis

enum class RkService(
    val title: String,
    val port: Int,
    val description: String,
    val scheme: String,
) {
    SAMBA("Samba", 445, "SMB 文件共享 NAS", "smb"),
    HTTP("HTTP", 3923, "文件管理服务", "http"),
    FTP("FTP", 12345, "FTP 文件服务", "ftp"),
    SFTP("SFTP", 1234, "SSH 文件服务", "sftp"),
    OPEN_SPEED_TEST("OpenSpeedTest", 3000, "浏览器局域网测速", "http"),
    IPERF3("iperf3", 5201, "TCP / UDP 压测", "iperf3"),
}

enum class ProbeStatus { CHECKING, ONLINE, OFFLINE }

data class ServiceProbeResult(
    val service: RkService,
    val status: ProbeStatus,
    val latencyMs: Long? = null,
    val detail: String = "",
)

class ServiceProbeManager(private val onChanged: (Map<RkService, ServiceProbeResult>) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(6)
    @Volatile private var generation = 0

    fun scan(host: String) {
        if (host.isBlank()) return
        val currentGeneration = ++generation
        val results = RkService.entries.associateWith {
            ServiceProbeResult(it, ProbeStatus.CHECKING, detail = "检测中")
        }.toMutableMap()
        publish(results)
        RkService.entries.forEach { service ->
            executor.execute {
                val result = probe(host, service)
                if (generation == currentGeneration) {
                    synchronized(results) { results[service] = result; publish(results) }
                }
            }
        }
    }

    private fun probe(host: String, service: RkService): ServiceProbeResult {
        return try {
            var detail = "端口已连接"
            val elapsed = measureTimeMillis {
                when (service) {
                    RkService.HTTP, RkService.OPEN_SPEED_TEST -> detail = probeHttp(host, service.port)
                    RkService.FTP -> detail = probeBanner(host, service.port, "220", "FTP")
                    RkService.SFTP -> detail = probeBanner(host, service.port, "SSH-", "SSH")
                    else -> Socket().use { socket -> socket.connect(InetSocketAddress(host, service.port), 900) }
                }
            }
            ServiceProbeResult(service, ProbeStatus.ONLINE, elapsed, detail)
        } catch (e: Exception) {
            ServiceProbeResult(service, ProbeStatus.OFFLINE, detail = e.message?.take(80) ?: "连接失败")
        }
    }

    private fun probeHttp(host: String, port: Int): String {
        val connection = URL("http://$host:$port/").openConnection() as HttpURLConnection
        connection.connectTimeout = 900
        connection.readTimeout = 900
        connection.requestMethod = "HEAD"
        connection.instanceFollowRedirects = false
        return try { "HTTP ${connection.responseCode}" } finally { connection.disconnect() }
    }

    private fun probeBanner(host: String, port: Int, expected: String, name: String): String {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 900)
            socket.soTimeout = 900
            val banner = BufferedReader(InputStreamReader(socket.getInputStream())).readLine().orEmpty()
            if (!banner.startsWith(expected)) error("不是 $name 服务")
            return banner.take(60)
        }
    }

    private fun publish(results: Map<RkService, ServiceProbeResult>) {
        val snapshot = results.toMap()
        main.post { onChanged(snapshot) }
    }

    fun close() {
        generation++
        executor.shutdownNow()
    }
}
