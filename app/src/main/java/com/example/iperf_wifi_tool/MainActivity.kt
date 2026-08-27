package com.example.iperf_wifi_tool

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.iperf_wifi_tool.ui.theme.Iperf_wifi_toolTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Iperf_wifi_toolTheme {
                Surface(modifier = Modifier.fillMaxSize()) { IperfScreen() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IperfScreen() {
    val context = LocalContext.current
    var services by remember { mutableStateOf<List<IperfService>>(emptyList()) }
    var selected by remember { mutableStateOf<IperfService?>(null) }
    var discoveryStatus by remember { mutableStateOf("准备发现服务") }
    var manualMode by remember { mutableStateOf(false) }
    var manualHost by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("5201") }
    var protocol by remember { mutableStateOf(TestProtocol.TCP) }
    var direction by remember { mutableStateOf(TestDirection.UPLOAD) }
    var streams by remember { mutableIntStateOf(1) }
    var udpMbps by remember { mutableIntStateOf(30) }
    var duration by remember { mutableIntStateOf(30) }
    var continuous by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf(TestResultState()) }
    var validationError by remember { mutableStateOf<String?>(null) }

    val discovery = remember {
        MdnsDiscovery(
            context.applicationContext,
            onServicesChanged = { found ->
                services = found
                if (!manualMode && (selected == null || found.none { it == selected })) {
                    selected = found.firstOrNull { it.name.contains("rk3399", true) } ?: found.firstOrNull()
                    selected?.let { portText = it.port.toString() }
                }
            },
            onStatusChanged = { discoveryStatus = it },
        )
    }
    val runner = remember { IperfRunner(context.applicationContext) { result = it } }

    DisposableEffect(Unit) {
        discovery.start()
        onDispose { discovery.stop(); runner.stop() }
    }

    val host = if (manualMode) manualHost.trim() else selected?.host.orEmpty()
    val port = portText.toIntOrNull() ?: 0
    val config = TestConfig(host, port, protocol, direction, streams, udpMbps, duration)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("RK3399 Wi-Fi 压测", fontWeight = FontWeight.Bold)
                        Text("mDNS + iperf3", style = MaterialTheme.typography.labelSmall)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionCard("服务端") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!manualMode, {
                        manualMode = false
                        if (selected == null) {
                            selected = services.firstOrNull { it.name.contains("rk3399", true) } ?: services.firstOrNull()
                            selected?.let { portText = it.port.toString() }
                        }
                    }, { Text("mDNS 自动发现") })
                    FilterChip(manualMode, { manualMode = true }, { Text("手动 IP") })
                }
                if (manualMode) {
                    OutlinedTextField(
                        manualHost, { manualHost = it; validationError = null },
                        label = { Text("服务端 IP 或主机名") }, placeholder = { Text("例如 192.168.1.100") },
                        singleLine = true, enabled = !result.running, modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(selected != null)
                        Spacer(Modifier.width(8.dp))
                        Text(discoveryStatus, modifier = Modifier.weight(1f))
                        TextButton({ discovery.refresh() }, enabled = !result.running) { Text("刷新") }
                    }
                    if (services.isEmpty()) {
                        Text(
                            "等待 _iperf3._tcp.local 服务；会优先选择名称含 RK3399 的设备。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else services.forEach { service ->
                        val isSelected = service == selected
                        Surface(
                            color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !result.running) {
                                selected = service; portText = service.port.toString()
                            },
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(isSelected, { selected = service; portText = service.port.toString() }, enabled = !result.running)
                                Column {
                                    Text(service.name, fontWeight = FontWeight.SemiBold)
                                    Text("${service.host}:${service.port}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    portText, { portText = it.filter(Char::isDigit).take(5); validationError = null },
                    label = { Text("端口") }, singleLine = true, enabled = !result.running,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(150.dp),
                )
            }

            SectionCard("压测参数") {
                SelectorRow("协议", listOf("TCP", "UDP"), if (protocol == TestProtocol.TCP) 0 else 1, !result.running) {
                    protocol = if (it == 0) TestProtocol.TCP else TestProtocol.UDP
                }
                SelectorRow("方向", listOf("上传 手机 → RK3399", "下载 RK3399 → 手机"), if (direction == TestDirection.UPLOAD) 0 else 1, !result.running) {
                    direction = if (it == 0) TestDirection.UPLOAD else TestDirection.DOWNLOAD
                }
                Text("并发连接数", style = MaterialTheme.typography.labelLarge)
                ChoiceChips(listOf(1, 4, 8, 10), streams, !result.running) { streams = it }
                if (protocol == TestProtocol.UDP) {
                    Text("UDP 目标总带宽", style = MaterialTheme.typography.labelLarge)
                    ChoiceChips(listOf(10, 20, 30, 50, 100), udpMbps, !result.running, suffix = "M") { udpMbps = it }
                    Text(
                        "$streams 路 × ${"%.2f".format(udpMbps.toDouble() / streams)} Mbps = $udpMbps Mbps 总目标",
                        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text("测试时长", style = MaterialTheme.typography.labelLarge)
                ChoiceChips(listOf(10, 30, 60, 300, 3600), duration, !result.running, transform = {
                    when (it) { 300 -> "5 分钟"; 3600 -> "1 小时"; else -> "$it 秒" }
                }) { duration = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(continuous, { continuous = it }, enabled = !result.running)
                    Column {
                        Text("持续压测")
                        Text("每轮结束自动重启，直到按停止", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            SectionCard("执行指令") {
                Text(
                    if (host.isBlank()) "等待选择服务端…" else IperfCommand.display(config),
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            result.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    if (result.running) runner.stop() else {
                        validationError = when {
                            host.isBlank() -> if (manualMode) "请输入服务端 IP" else "尚未发现或选择 RK3399 服务"
                            port !in 1..65535 -> "端口必须在 1–65535 之间"
                            else -> null
                        }
                        if (validationError == null) runner.start(config, continuous)
                    }
                },
                colors = if (result.running) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (result.running) "停止压测" else "开始压测", fontSize = 17.sp) }

            if (result.running || result.output.isNotEmpty()) {
                SectionCard("实时结果") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Metric("当前", result.currentMbps); Metric("平均", result.averageMbps); Metric("峰值", result.peakMbps)
                    }
                    if (protocol == TestProtocol.UDP && (result.jitterMs != null || result.lossPercent != null)) {
                        Text("抖动 ${result.jitterMs?.let { "%.2f ms".format(it) } ?: "--"}    丢包 ${result.lossPercent?.let { "%.2f%%".format(it) } ?: "--"}")
                    }
                    Text(
                        result.output.takeLast(18).joinToString("\n"),
                        modifier = Modifier.fillMaxWidth().background(Color(0xFF101418), RoundedCornerShape(8.dp)).padding(10.dp),
                        color = Color(0xFFD5E8D4), fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 14.sp,
                    )
                }
            }
            Text(
                "RK3399 端需先运行：iperf3 -s -p ${if (port > 0) port else 5201}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 20.dp),
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun SelectorRow(label: String, values: List<String>, selected: Int, enabled: Boolean, onSelected: (Int) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEachIndexed { index, value ->
            FilterChip(selected == index, { onSelected(index) }, { Text(value) }, enabled = enabled)
        }
    }
}

@Composable
private fun ChoiceChips(
    values: List<Int>, selected: Int, enabled: Boolean, suffix: String = "",
    transform: ((Int) -> String)? = null, onSelected: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { value ->
            FilterChip(selected == value, { onSelected(value) }, { Text(transform?.invoke(value) ?: "$value$suffix") }, enabled = enabled)
        }
    }
}

@Composable
private fun StatusDot(active: Boolean) {
    Box(Modifier.size(10.dp).background(if (active) Color(0xFF2E7D32) else Color(0xFF9E9E9E), RoundedCornerShape(5.dp)))
}

@Composable
private fun Metric(label: String, value: Double) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("%.1f".format(value), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text("$label Mbps", style = MaterialTheme.typography.labelSmall)
    }
}
