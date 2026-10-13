package com.example.iperf_wifi_tool

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONArray

@Composable
fun RouterManagement(load: suspend () -> JSONObject, perform: suspend (String, JSONObject) -> JSONObject) {
    var state by remember { mutableStateOf(JSONObject()) }
    var loaded by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(true) }
    var uplink by remember { mutableStateOf("auto") }
    var members by remember { mutableStateOf(setOf<String>()) }
    var wanMenu by remember { mutableStateOf(false) }
    var lanMenu by remember { mutableStateOf(false) }
    var emptyWarning by remember { mutableStateOf(false) }
    var safetyRollback by remember { mutableStateOf(true) }
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
                uplink = state.optString("uplink").let { if (it.isBlank() || it == "auto") state.optString("activeWan").ifBlank { "none" } else it }
                members = if (state.optString("interfaceMode") == "explicit") state.optString("lanMembers").split(' ').filter { it.isNotBlank() }.toSet()
                else listOf("eth0", "eth1").filter { it != uplink && (state.optString("$it.role") == "lan" || state.optString("${it}Mode") == "lan" || state.optString("uplink") == "none") }.toSet() +
                    if (state.optString("wifiOperation") in setOf("1", "2")) setOf("ap") else emptySet()
                wait = state.optString("wait").ifBlank { "20" }
                ip = state.optString("lanIp").ifBlank { "192.168.31.1" }
                preset = state.optString("preset").ifBlank { if (state.optString("enabled").isBlank()) "board" else "custom" }
                safetyRollback = state.optString("rollbackPolicy") != "off"
                loaded = true
            }
        } catch (e: Exception) { message = e.message ?: "网络状态读取失败" }
    }
    LaunchedEffect(Unit) { while (true) { if (!busy) refresh(); delay(5000) } }
    fun action(path: String, body: JSONObject) {
        scope.launch {
            busy = true
            try { message = perform(path, body).optString("output"); delay(2500); refresh() }
            catch (e: Exception) { message = "${e.message}；请重新连接并检查状态。仅待确认且启用安全回滚的配置会超时恢复。" }
            finally { busy = false }
        }
    }
    val interfaces = state.optString("interfaces").split(' ').filter { it.isNotBlank() }.distinct()
    fun label(id: String) = state.optString("$id.label").ifBlank { if (id == "none") "无上行" else id }
    fun apply() {
        val body = JSONObject().put("enabled", enabled).put("wait", wait.toIntOrNull() ?: 0).put("lanIp", ip)
        if (preset == "custom") action("network", body.put("uplink", uplink).put("lanMembers", JSONArray(members.toList())).put("rollbackPolicy", if (safetyRollback) "on" else "off"))
        else action("network/preset", body.put("preset", preset))
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("最多一个 WAN · LAN 成员自由选择", style = MaterialTheme.typography.titleSmall)
        Text("当前 WAN：${state.optString("activeWan").ifBlank { "无" }} · LAN ${state.optString("lanIp")}")
        for (iface in interfaces.filter { it != "wlan0" && it != "ap" }) Text("${label(iface)} · ${state.optString("$iface.role")} · ${state.optString("$iface.ip")}", style = MaterialTheme.typography.bodySmall)
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
        Text(presets.firstOrNull { it.first == preset }?.third ?: "WAN 单选 · br-lan 成员多选 · 未选接口不参与路由", style = MaterialTheme.typography.bodySmall)
        if (preset == "wireless") Text("请先在 WiFi 页面连接上游；并发热点跟随 STA 信道，仍为实验功能。", style = MaterialTheme.typography.bodySmall)
        Text("上游接 WAN，下游接 LAN。仅 LAN/离线 NAS 不要接已有路由器 LAN。", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级设置" else "高级设置") }
        if (advanced) {
        Row { Switch(enabled, { enabled = it }, enabled = !busy); Text("启用统一路由管理", Modifier.padding(top = 12.dp)) }
        if (preset == "custom") {
        Box {
            OutlinedButton(onClick = { wanMenu = true }, enabled = !busy) { Text("WAN：${label(uplink)} ▾") }
            DropdownMenu(wanMenu, { wanMenu = false }) {
                (listOf("none") + interfaces.filter { it != "ap" }).forEach { id ->
                    DropdownMenuItem(text = { Text(label(id)) }, onClick = { uplink = id; members = members - id; wanMenu = false })
                }
            }
        }
        Box {
            OutlinedButton(onClick = { lanMenu = true }, enabled = !busy) { Text("LAN（br-lan）：${if (members.isEmpty()) "无成员" else members.joinToString()} ▾") }
            DropdownMenu(lanMenu, { lanMenu = false }) {
                (interfaces + members).distinct().filter { it != "wlan0" && it != uplink }.forEach { id ->
                    DropdownMenuItem(text = { Text(label(id)) }, leadingIcon = { Checkbox(id in members, null) },
                        onClick = { members = if (id in members) members - id else members + id })
                }
                DropdownMenuItem(text = { Text("完成") }, onClick = { lanMenu = false })
            }
        }
        Text("WiFi STA 作 WAN 前需先连接上游；无线模式仍在 WiFi 页设置。空 LAN 或取消 AP 可能断开手机。", style = MaterialTheme.typography.bodySmall)
        Row { Switch(safetyRollback, { safetyRollback = it }, enabled = !busy); Text("安全回滚（120 秒确认）", Modifier.padding(top = 12.dp)) }
        Text(if (safetyRollback) "高级配置应用后需确认，超时恢复。" else "已关闭：配置直接保留，断连后不会自动恢复。", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(wait, { wait = it.filter(Char::isDigit).take(3) }, Modifier.fillMaxWidth(), label = { Text("自动检测秒数 3–120") }, singleLine = true)
        OutlinedTextField(ip, { ip = it }, Modifier.fillMaxWidth(), label = { Text("LAN 地址（192.168.x.1–99 /24）") }, singleLine = true)
        }
        val pending = state.optString("pendingUntil").isNotBlank()
        Text("预设按风险判断：AP、LAN 地址和无线模式不变时无需确认。", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { if (preset == "custom" && (!safetyRollback || members.isEmpty() || "ap" !in members)) emptyWarning = true else apply() }, enabled = !busy && loaded && !pending) { Text("应用配置") }
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
    if (emptyWarning) AlertDialog(onDismissRequest = { emptyWarning = false }, title = { Text("可能断开控制连接") },
        text = { Text(if (!safetyRollback) "安全回滚已关闭，配置将直接保留。若 LAN 地址、接口或热点不可用，需通过其他网口、本机或 ADB 恢复。" else "LAN 无成员或未包含热点 AP。应用后可能需要从 WAN 地址重新连接；120 秒内未确认会回滚。") },
        confirmButton = { TextButton(onClick = { emptyWarning = false; apply() }) { Text("仍然应用") } },
        dismissButton = { TextButton(onClick = { emptyWarning = false }) { Text("取消") } })
}
