package com.example.iperf_wifi_tool

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun RouterManagement(load: suspend () -> JSONObject, perform: suspend (String, JSONObject) -> JSONObject) {
    var state by remember { mutableStateOf(JSONObject()) }
    var loaded by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(true) }
    var uplink by remember { mutableStateOf("auto") }
    var eth0 by remember { mutableStateOf("auto") }
    var eth1 by remember { mutableStateOf("auto") }
    var wait by remember { mutableStateOf("20") }
    var ip by remember { mutableStateOf("192.168.31.1") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var preset by remember { mutableStateOf("board") }
    var menu by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    val presets = listOf(
        Triple("board", "日常默认", "板载固定 WAN · USB LAN · 无线 LAN AP"),
        Triple("usb", "USB 千兆网卡上网", "USB 固定 WAN · 板载 LAN · 无线 LAN AP"),
        Triple("wireless", "无线上网", "WiFi WWAN · 双网口 LAN · 同信道 AP（实验）"),
        Triple("offline", "离线 NAS", "双网口 LAN · 无线 LAN AP · 无上行"),
        Triple("automatic", "双网口全自动", "板载优先、USB 备用 · 双网口自动 WAN/LAN · 无线 LAN AP")
    )
    val scope = rememberCoroutineScope()
    suspend fun refresh() {
        try {
            state = load()
            if (!loaded) {
                enabled = state.optString("enabled") != "0"
                uplink = state.optString("uplink").ifBlank { "auto" }
                eth0 = state.optString("eth0Mode").ifBlank { "auto" }
                eth1 = state.optString("eth1Mode").ifBlank { "auto" }
                wait = state.optString("wait").ifBlank { "20" }
                ip = state.optString("lanIp").ifBlank { "192.168.31.1" }
                preset = state.optString("preset").ifBlank { if (state.optString("enabled").isBlank()) "board" else "custom" }
                loaded = true
            }
        } catch (e: Exception) { message = e.message ?: "网络状态读取失败" }
    }
    LaunchedEffect(Unit) { while (true) { if (!busy) refresh(); delay(5000) } }
    fun action(path: String, body: JSONObject) {
        scope.launch {
            busy = true
            try { message = perform(path, body).optString("output"); delay(2500); refresh() }
            catch (e: Exception) { message = "${e.message}；若地址改变，请重新连接，未确认配置会自动回滚。" }
            finally { busy = false }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("最多一个 WAN · 其余桥接 LAN", style = MaterialTheme.typography.titleSmall)
        Text("当前 WAN：${state.optString("activeWan").ifBlank { "无" }} · LAN ${state.optString("lanIp")}")
        for (iface in listOf("eth0", "eth1")) Text("${if (iface == "eth0") "板载" else "USB"} · ${state.optString("$iface.role")} · ${state.optString("$iface.ip")}\n${state.optString("$iface.mac")}", style = MaterialTheme.typography.bodySmall)
        Text("DHCP ${if (state.optString("dhcp") == "1") "运行中" else "未运行"} · ${state.optString("message")}", style = MaterialTheme.typography.bodySmall)
        if (state.optString("wifiOperation") == "2") Text("并发 AP ${if (state.optString("concurrentAp") == "1") "已启动" else "未就绪"} · 实际 CH ${state.optString("ap0.channel")}", style = MaterialTheme.typography.bodySmall)
        Box {
            OutlinedButton(onClick = { menu = true }, enabled = !busy && state.optString("pendingUntil").isBlank()) {
                Text("场景：${presets.firstOrNull { it.first == preset }?.second ?: "高级自定义"}")
            }
            DropdownMenu(menu, { menu = false }) {
                presets.forEach { choice -> DropdownMenuItem(text = { Text(choice.second) }, onClick = { preset = choice.first; menu = false }) }
                DropdownMenuItem(text = { Text("高级自定义") }, onClick = { preset = "custom"; advanced = true; menu = false })
            }
        }
        Text(presets.firstOrNull { it.first == preset }?.third ?: "保留现有逐接口配置与无线模式", style = MaterialTheme.typography.bodySmall)
        if (preset == "wireless") Text("请先在 WiFi 页面连接上游；并发热点跟随 STA 信道，仍为实验功能。", style = MaterialTheme.typography.bodySmall)
        Text("上游接 WAN，下游接 LAN。仅 LAN/离线 NAS 不要接已有路由器 LAN。", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级设置" else "高级设置") }
        if (advanced) {
        Row { Switch(enabled, { enabled = it }, enabled = !busy); Text("启用统一路由管理", Modifier.padding(top = 12.dp)) }
        if (preset == "custom") {
        listOf("auto" to "自动", "eth0" to "板载 WAN", "eth1" to "USB WAN", "wlan0" to "WiFi WWAN", "none" to "全 LAN").chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEach { (id, title) ->
                FilterChip(uplink == id, { uplink = id }, label = { Text(title) }, enabled = !busy)
            } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(eth0 == "lan", { eth0 = if (eth0 == "lan") "auto" else "lan" }, label = { Text("板载仅 LAN") }, enabled = !busy)
            FilterChip(eth1 == "lan", { eth1 = if (eth1 == "lan") "auto" else "lan" }, label = { Text("USB 仅 LAN") }, enabled = !busy)
        }
        }
        OutlinedTextField(wait, { wait = it.filter(Char::isDigit).take(3) }, Modifier.fillMaxWidth(), label = { Text("自动检测秒数 3–120") }, singleLine = true)
        OutlinedTextField(ip, { ip = it }, Modifier.fillMaxWidth(), label = { Text("LAN 地址（192.168.x.1–99 /24）") }, singleLine = true)
        }
        val pending = state.optString("pendingUntil").isNotBlank()
        Button(onClick = {
            val body = JSONObject().put("enabled", enabled).put("wait", wait.toIntOrNull() ?: 0).put("lanIp", ip)
            if (preset == "custom") action("network", body.put("uplink", uplink).put("eth0Mode", eth0).put("eth1Mode", eth1))
            else action("network/preset", body.put("preset", preset))
        }, enabled = !busy && loaded && !pending) { Text("应用场景（需确认）") }
        if (pending) {
            Text("待确认：切换后请连接新地址，120 秒内确认，否则回滚。", color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { action("network/confirm", JSONObject().put("token", state.optString("token"))) }, enabled = !busy) { Text("确认可用并保留") }
                OutlinedButton(onClick = { action("network/rollback", JSONObject()) }, enabled = !busy) { Text("回滚") }
            }
        }
        OutlinedButton(onClick = { scope.launch { refresh() } }, enabled = !busy) { Text("刷新接口状态") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        Text("上游使用 WAN 地址、下游使用 LAN 地址访问 RK3399；WAN 侧仅允许直连私网访问管理/NAS，不开放 WAN→LAN 主动连接。", style = MaterialTheme.typography.bodySmall)
    }
}
