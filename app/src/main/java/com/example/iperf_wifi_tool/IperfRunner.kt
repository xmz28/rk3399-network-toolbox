package com.example.iperf_wifi_tool

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class TestResultState(
    val running: Boolean = false,
    val currentMbps: Double = 0.0,
    val averageMbps: Double = 0.0,
    val peakMbps: Double = 0.0,
    val jitterMs: Double? = null,
    val lossPercent: Double? = null,
    val output: List<String> = emptyList(),
    val error: String? = null,
)

class IperfRunner(
    private val context: Context,
    private val onState: (TestResultState) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val stopping = AtomicBoolean(false)
    @Volatile private var process: Process? = null
    @Volatile private var state = TestResultState()

    fun start(config: TestConfig, continuous: Boolean) {
        if (state.running) return
        stopping.set(false)
        update(TestResultState(running = true))
        Thread({
            do {
                runOnce(config)
            } while (continuous && !stopping.get() && state.error == null)
            update(state.copy(running = false))
        }, "iperf3-runner").start()
    }

    private fun runOnce(config: TestConfig) {
        try {
            val executable = File(context.applicationInfo.nativeLibraryDir, "libiperf3.so").absolutePath
            val args = IperfCommand.arguments(executable, config)
            append("$ ${IperfCommand.display(config)}")
            process = ProcessBuilder(args).redirectErrorStream(true).start()
            val samples = mutableListOf<Double>()
            process!!.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (stopping.get()) return@forEach
                    append(line)
                    val isSummary = line.contains("sender") || line.contains("receiver")
                    val isWantedStream = config.streams == 1 || line.contains("[SUM]")
                    if (!isSummary && isWantedStream && line.contains("sec")) {
                        IperfOutputParser.bandwidthMbps(line)?.let { mbps ->
                            samples += mbps
                            update(state.copy(
                                currentMbps = mbps,
                                averageMbps = samples.average(),
                                peakMbps = samples.maxOrNull() ?: mbps,
                                jitterMs = IperfOutputParser.jitterMs(line) ?: state.jitterMs,
                                lossPercent = IperfOutputParser.lossPercent(line) ?: state.lossPercent,
                            ))
                        }
                    }
                    if (line.contains("error", ignoreCase = true)) {
                        update(state.copy(error = friendlyError(line)))
                    }
                }
            }
            val exit = process?.waitFor() ?: -1
            if (!stopping.get() && exit != 0 && state.error == null) {
                update(state.copy(error = "iperf3 异常退出（代码 $exit），请检查服务端是否启动"))
            }
        } catch (e: Exception) {
            if (!stopping.get()) update(state.copy(error = friendlyError(e.message ?: e.javaClass.simpleName)))
        } finally {
            process?.destroy()
            process = null
        }
    }

    private fun friendlyError(message: String): String = when {
        message.contains("unable to connect", true) || message.contains("connection refused", true) ->
            "无法连接服务端，请确认 IP、端口及 RK3399 上的 iperf3 -s"
        message.contains("no route to host", true) -> "无法到达服务端，请确认手机与 RK3399 在同一局域网"
        message.contains("permission denied", true) -> "iperf3 内核没有执行权限"
        else -> message
    }

    private fun append(line: String) {
        val next = (state.output + line).takeLast(120)
        update(state.copy(output = next))
    }

    private fun update(newState: TestResultState) {
        state = newState
        main.post { onState(newState) }
    }

    fun stop() {
        stopping.set(true)
        process?.destroy()
        update(state.copy(running = false))
    }
}
