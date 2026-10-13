package com.example.iperf_wifi_tool

import android.content.Context
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.unit.dp
import com.example.iperf_wifi_tool.ui.theme.Iperf_wifi_toolTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class RemoteControlActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val host = intent.getStringExtra(EXTRA_HOST).orEmpty().ifBlank { "a.local" }
        setContent {
            Iperf_wifi_toolTheme {
                MaterialTheme(colorScheme = lightColorScheme(
                    primary = Color(0xFF3278FF), onPrimary = Color.White,
                    secondary = Color(0xFF4C60AD), surface = Color.White,
                    secondaryContainer = Color(0xFFDDE7FF), surfaceVariant = Color(0xFFE6EBF5),
                    background = Color(0xFFEEF1F6), onSurface = Color(0xFF20243A)
                )) { RemoteControlScreen(host, this) }
            }
        }
    }

    companion object { const val EXTRA_HOST = "host" }
}

private class ControlApi(private val host: String, private val password: String) {
    fun request(path: String, body: JSONObject? = null): JSONObject {
        require(host.matches(Regex("[A-Za-z0-9.:-]+"))) { "目标地址格式不正确" }
        val connection = (URL("http://$host:8765/api/v1/$path").openConnection() as HttpURLConnection)
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = if (path == "storage") 270000 else 65000
            if (path == "storage") connection.setRequestProperty("X-Storage-Request", "1")
            connection.setRequestProperty("Authorization", "Basic " + Base64.encodeToString("admin:$password".toByteArray(), Base64.NO_WRAP))
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            if (connection.responseCode == 401) error("密码错误")
            val text = (if (connection.responseCode < 400) connection.inputStream else connection.errorStream)
                .bufferedReader().use { it.readText() }
            val response = JSONObject(text)
            if (!response.optBoolean("ok")) error(response.optString("error", response.optString("output", "操作失败"))
                .lineSequence().lastOrNull { it.isNotBlank() }?.take(160) ?: "操作失败")
            return response
        } finally { connection.disconnect() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteControlScreen(initialHost: String, activity: RemoteControlActivity) {
    val prefs = remember { activity.getSharedPreferences("remote_control", Context.MODE_PRIVATE) }
    var host by remember { mutableStateOf(initialHost) }
    var password by remember { mutableStateOf(prefs.getString("password", "admin") ?: "admin") }
    var rememberPassword by remember { mutableStateOf(prefs.contains("password")) }
    var authenticated by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("连接前请验证控制密码") }
    var snapshot by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var logType by remember { mutableStateOf("system") }
    var logText by remember { mutableStateOf("") }
    var apSsid by remember { mutableStateOf("") }
    var apPassword by remember { mutableStateOf("") }
    var band by remember { mutableStateOf("2g") }
    var channel by remember { mutableStateOf("1") }
    var ethMode by remember { mutableStateOf("auto") }
    var waitSeconds by remember { mutableStateOf("6") }
    var ip by remember { mutableStateOf("") }
    var prefix by remember { mutableStateOf("24") }
    var gateway by remember { mutableStateOf("") }
    var dns1 by remember { mutableStateOf("") }
    var dns2 by remember { mutableStateOf("") }
    var pppoeUser by remember { mutableStateOf("") }
    var pppoePassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var nasPassword by remember { mutableStateOf("") }
    var nasPasswordAgain by remember { mutableStateOf("") }
    var page by remember { mutableStateOf("概览") }
    var showConnection by remember { mutableStateOf(true) }
    var networks by remember { mutableStateOf<org.json.JSONArray?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var selectedWifi by remember { mutableStateOf<String?>(null) }
    var staPassword by remember { mutableStateOf("") }
    var pendingUsbPreset by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        if (busy) return
        scope.launch {
            busy = true
            try {
                val response = withContext(Dispatchers.IO) { ControlApi(host.trim(), password).request("status") }
                snapshot = response
                authenticated = true
                status = "已连接 ${response.optString("model")}"
                apSsid = response.optString("apSsid", response.optString("ssid")).takeUnless { it == "—" }.orEmpty()
                band = response.optString("apBand", "2g")
                channel = response.optString("apChannel", "1")
                ethMode = response.optString("ethMode", "auto")
                waitSeconds = response.optInt("ethAutoWait", 6).toString()
                prefs.edit().apply { if (rememberPassword) putString("password", password) else remove("password") }.apply()
            } catch (e: Exception) {
                authenticated = false
                status = e.message ?: "连接失败"
            } finally { busy = false }
        }
    }

    fun perform(path: String, body: JSONObject, onSuccess: (() -> Unit)? = null) {
        if (busy) return
        scope.launch {
            busy = true
            var succeeded = false
            try {
                val response = withContext(Dispatchers.IO) { ControlApi(host.trim(), password).request(path, body) }
                status = response.optString("output").lineSequence().lastOrNull { it.isNotBlank() }?.take(160) ?: "操作成功"
                onSuccess?.invoke()
                succeeded = true
            } catch (e: Exception) { status = e.message ?: "操作失败" }
            finally { busy = false }
            if (succeeded) refresh()
        }
    }

    fun scanWifi() {
        scope.launch {
            scanning = true
            try {
                networks = withContext(Dispatchers.IO) { ControlApi(host.trim(), password).request("wifi/scan") }.optJSONArray("networks")
                status = "已发现 ${networks?.length() ?: 0} 个 Wi‑Fi 网络"
            } catch (e: Exception) { status = e.message ?: "扫描失败" }
            finally { scanning = false }
        }
    }

    Scaffold(containerColor = Color.Transparent, topBar = {
        TopAppBar(title = { Text("RK3399", color = Color(0xFF20243A)) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFCFD5F6)))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(Brush.verticalGradient(listOf(
            Color(0xFFCFD5F6), Color(0xFFF4EDF5), Color(0xFFEEF1F6), Color(0xFFE5E8EE)
        ))).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RemotePanel("远程连接") {
            if (authenticated && !showConnection) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("${host.trim()} · admin", color = Color(0xFF72798A))
                    TextButton(onClick = { showConnection = true }) { Text("修改") }
                }
            } else {
            OutlinedTextField(host, { host = it; authenticated = false }, Modifier.fillMaxWidth(), label = { Text("RK3399 地址") }, singleLine = true)
            OutlinedTextField(password, { password = it; authenticated = false }, Modifier.fillMaxWidth(), label = { Text("控制密码（账号 admin）") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            Row { Checkbox(rememberPassword, { rememberPassword = it }); Text("在本机记住密码", Modifier.padding(top = 12.dp)) }
            Button(onClick = { refresh(); showConnection = false }, enabled = !busy && host.isNotBlank() && password.isNotBlank()) { Text(if (authenticated) "刷新状态" else "验证密码并连接") }
            }
            }
            Text(status, color = if (authenticated) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (authenticated && snapshot != null) {
                val s = snapshot!!
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("概览", "服务", "存储", "路由", "Wi-Fi", "连接", "日志").forEach { name ->
                        FilterChip(page == name, { page = name }, label = { Text(name) })
                    }
                }
                if (page == "概览") RemotePanel("设备状态") {
                Text("${s.optString("model")} · Android ${s.optString("android")}", color = Color(0xFF72798A))
                if (s.optBoolean("routerEnabled")) Text("路由上行 ${s.optString("routerWan").ifBlank { "无" }} · LAN ${s.optString("lanIp")}")
                Text("有线 ${s.optString("ethRole")} · ${s.optString("ethIp")} · ${s.optString("ethMode")}")
                Text("Wi-Fi ${s.optString("wifiMode")} · ${s.optString("ssid")} · ${s.optString("wlanIp")} · ${s.optString("wifiBand")} CH ${s.optString("channel")}")
                OutlinedButton(onClick = ::refresh, enabled = !busy) { Text("刷新状态") }
                }
                if (page == "存储") StorageManagement(
                    load = { withContext(Dispatchers.IO) { ControlApi(host.trim(), password).request("storage") } },
                    perform = { body -> withContext(Dispatchers.IO) { ControlApi(host.trim(), password).request("storage", body) } })
                if (page == "路由") RemotePanel("接口与路由") {
                    RouterManagement(
                        load = { withContext(Dispatchers.IO) { ControlApi(host.trim(), password).request("network") } },
                        perform = { path, body -> withContext(Dispatchers.IO) { ControlApi(host.trim(), password).request(path, body) } })
                }
                if (page == "服务") RemotePanel("NAS 与测试服务") {
                val services = s.optJSONArray("services")
                if (services != null) for (i in 0 until services.length()) {
                    val item = services.getJSONObject(i)
                    val id = item.optString("id")
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Color(0xFFF4F6FC)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(item.optString("name"), color = Color(0xFF20243A), style = MaterialTheme.typography.titleSmall)
                            Text(if (item.optBoolean("running")) "● 运行中" else "○ 未运行",
                                color = if (item.optBoolean("running")) Color(0xFF128C5E) else Color(0xFF72798A),
                                style = MaterialTheme.typography.labelMedium)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("端口 ${item.optInt("port")}", color = Color(0xFF72798A), style = MaterialTheme.typography.bodySmall)
                            if (item.optBoolean("controllable")) Switch(item.optBoolean("enabled"), { enabled ->
                                perform("service", JSONObject().put("id", id).put("enabled", enabled))
                            }, enabled = !busy)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (item.optBoolean("editablePort")) {
                                var portText by remember(id) { mutableStateOf(item.optInt("port").toString()) }
                                OutlinedTextField(portText, { portText = it.filter(Char::isDigit).take(5) }, Modifier.width(120.dp), label = { Text("修改端口") }, singleLine = true)
                                TextButton(onClick = { perform("port", JSONObject().put("id", id).put("port", portText.toIntOrNull() ?: 0)) }, enabled = !busy) { Text("保存") }
                            }
                        }
                    }
                    }
                }
                }
                if (page == "服务") RemotePanel("NAS 共用密码") {
                    Text("Samba / HTTP 文件管理 / FTP / SFTP 使用同一个 admin 密码。", color = Color(0xFF72798A))
                    OutlinedTextField(nasPassword, { nasPassword = it }, Modifier.fillMaxWidth(), label = { Text("新密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(nasPasswordAgain, { nasPasswordAgain = it }, Modifier.fillMaxWidth(), label = { Text("再次输入") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    Button(onClick = {
                        perform("nas-password", JSONObject().put("password", nasPassword), onSuccess = {
                            activity.getSharedPreferences("remote_files", Context.MODE_PRIVATE).edit().putString("nas_password", nasPassword).apply()
                            nasPassword = ""; nasPasswordAgain = ""
                        })
                    }, enabled = !busy && nasPassword == nasPasswordAgain && nasPassword.matches(Regex("[A-Za-z0-9._@#-]{4,64}"))) { Text("同步修改四种服务") }
                    Text("仅支持 4–64 位字母、数字或 ._@#-；修改后本机文件页会记住新密码。", style = MaterialTheme.typography.bodySmall, color = Color(0xFF72798A))
                }
                if (page == "Wi-Fi") RemotePanel("模式与热点") {
                Text("当前 ${s.optString("wifiMode")} · ${s.optString("ssid")} · ${s.optString("wlanIp")}", color = Color(0xFF72798A))
                if (s.optString("wifiMode") == "AP") Text("热点 DHCP：${if (s.optBoolean("wifiDhcpRunning")) "运行中" else "未运行，手机可能无法获取 IP"}", color = if (s.optBoolean("wifiDhcpRunning")) Color(0xFF377B65) else MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(s.optString("wifiMode") == "AP", { perform("wifi", JSONObject().put("mode", "ap")) }, enabled = !busy, label = { Text("热点 AP") })
                    FilterChip(s.optString("wifiMode") == "STA", {
                        perform("wifi", JSONObject().put("mode", "sta"), onSuccess = { scanWifi() })
                    }, enabled = !busy, label = { Text("连接 Wi‑Fi (STA)") })
                }
                OutlinedButton(onClick = { perform("wifi", JSONObject().put("mode", "apsta")) }, enabled = !busy && s.optString("wifiMode") == "STA") { Text("实验：STA + AP 同时开启") }
                Text("并发需先启用路由并连接上游 WiFi；AP 实际频段/信道跟随上游，尚未验证所有组合。", style = MaterialTheme.typography.bodySmall)
                if (s.optString("wifiMode") != "AP") {
                    OutlinedButton(onClick = ::scanWifi, enabled = !scanning && !busy) { Text(if (scanning) "扫描中…" else "重新扫描附近网络") }
                    val found = networks
                    if (found != null) {
                        if (found.length() == 0) Text("未找到网络，请稍后重试。")
                        for (i in 0 until found.length()) {
                            val item = found.optJSONObject(i) ?: continue
                            val ssid = item.optString("ssid")
                            Surface(onClick = { selectedWifi = ssid; staPassword = "" },
                                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = Color(0xFFF4F6FC)) {
                                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(ssid, modifier = Modifier.weight(1f), color = Color(0xFF20243A))
                                    Text("${if (item.optInt("frequency") >= 5000) "5G" else "2.4G"}  ${item.optInt("signal")} dBm", color = Color(0xFF72798A))
                                }
                            }
                        }
                    }
                }
                }
                if (page == "Wi-Fi") RemotePanel("热点配置") {
                OutlinedTextField(apSsid, { apSsid = it }, Modifier.fillMaxWidth(), label = { Text("热点 SSID") }, singleLine = true)
                OutlinedTextField(apPassword, { apPassword = it }, Modifier.fillMaxWidth(), label = { Text("新热点密码（8–63 位）") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(band == "2g", { band = "2g"; channel = "1" }, label = { Text("2.4 GHz") })
                    FilterChip(band == "5g", { band = "5g"; channel = "36" }, label = { Text("5 GHz") })
                }
                val channels = if (band == "5g") listOf(36, 40, 44, 48, 52, 56, 60, 64, 149, 153, 157, 161, 165) else (1..13).toList()
                Text("信道 · ${if (band == "5g") "5 GHz" else "2.4 GHz"}", color = Color(0xFF72798A))
                channels.chunked(5).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { ch -> FilterChip(channel == ch.toString(), { channel = ch.toString() }, label = { Text("$ch") }) }
                    }
                }
                Button(onClick = { perform("ap", JSONObject().put("ssid", apSsid).put("password", apPassword).put("band", band).put("channel", channel.toIntOrNull() ?: 0)) }, enabled = !busy && apPassword.length in 8..63) { Text("保存热点配置") }
                Text("切换 AP/STA 或修改热点会断开当前 Wi-Fi；DFS 52–64 只限屏蔽箱测试。", style = MaterialTheme.typography.bodySmall)
                }
                if (page == "连接") RemotePanel("网口模式") {
                if (s.optBoolean("routerEnabled")) {
                    Text("统一路由已接管网口，请进入“路由”页配置板载/USB 网口。")
                } else {
                listOf("auto" to "自动 WAN/LAN", "lan" to "固定 LAN", "wan_dhcp" to "WAN DHCP", "wan_static" to "WAN 静态 IP", "pppoe" to "WAN PPPoE").forEach { (id, label) ->
                    FilterChip(ethMode == id, { ethMode = id }, label = { Text(label) })
                }
                if (ethMode == "auto") OutlinedTextField(waitSeconds, { waitSeconds = it.filter(Char::isDigit).take(3) }, label = { Text("自动检测秒数 1–120") }, singleLine = true)
                if (ethMode == "wan_static") {
                    OutlinedTextField(ip, { ip = it }, label = { Text("IP") }, singleLine = true)
                    OutlinedTextField(prefix, { prefix = it }, label = { Text("前缀，如 24") }, singleLine = true)
                    OutlinedTextField(gateway, { gateway = it }, label = { Text("网关") }, singleLine = true)
                    OutlinedTextField(dns1, { dns1 = it }, label = { Text("DNS 1") }, singleLine = true)
                    OutlinedTextField(dns2, { dns2 = it }, label = { Text("DNS 2") }, singleLine = true)
                }
                if (ethMode == "pppoe") {
                    OutlinedTextField(pppoeUser, { pppoeUser = it }, label = { Text("PPPoE 账号") }, singleLine = true)
                    OutlinedTextField(pppoePassword, { pppoePassword = it }, label = { Text("PPPoE 密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                }
                Button(onClick = {
                    perform("ethernet", JSONObject().put("mode", ethMode).put("autoWaitSeconds", waitSeconds.toIntOrNull() ?: 0)
                        .put("ip", ip).put("prefix", prefix.toIntOrNull() ?: 0).put("gateway", gateway).put("dns1", dns1).put("dns2", dns2)
                        .put("pppoeUser", pppoeUser).put("pppoePassword", pppoePassword))
                }, enabled = !busy) { Text("应用网口配置") }
                Text("切换网口角色可能使当前连接立即中断。", style = MaterialTheme.typography.bodySmall)
                }
                }
                if (page == "连接") RemotePanel("USB 启动预设") {
                Text("Type-C · fe800000 · 设备树 ${s.optString("usbCDeviceTree")} · 当前 ${s.optString("usbCMode")}")
                Text("USB3 Type-A · fe900000 · 固件 ${s.optString("usbADeviceTree")} · 当前 ${s.optString("usbAMode")}")
                Text("Gadget ${s.optString("usbGadgetState")} · 外设 ${s.optInt("usbAAttached")} 个", style = MaterialTheme.typography.bodySmall)
                Text("当前 ${s.optString("usbPresetActive")} · 下次启动 ${s.optString("usbPresetNext")}", color = MaterialTheme.colorScheme.primary)
                OutlinedButton(onClick = { pendingUsbPreset = "A" }, enabled = !busy && s.optString("usbPresetNext") != "A") {
                    Text("A · Type-C Host / Type-A OTG")
                }
                OutlinedButton(onClick = { pendingUsbPreset = "B" }, enabled = !busy && s.optString("usbPresetNext") != "B") {
                    Text("B · Type-C OTG / Type-A Host")
                }
                Text("写入已校验的启动镜像，下次重启生效；当前是否插线不影响选择。重启后 USB ADB 端口会改变。", style = MaterialTheme.typography.bodySmall)
                }
                if (page == "日志") RemotePanel("日志") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("system", "nas", "wifi", "controller").forEach { type ->
                    FilterChip(logType == type, { logType = type }, label = { Text(type) })
                } }
                OutlinedButton(onClick = {
                    scope.launch {
                        busy = true
                        try { logText = withContext(Dispatchers.IO) { ControlApi(host.trim(), password).request("log?type=$logType") }.optString("output") }
                        catch (e: Exception) { status = e.message ?: "日志读取失败" }
                        finally { busy = false }
                    }
                }, enabled = !busy) { Text("读取日志") }
                if (logText.isNotBlank()) Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF20243A)) {
                    Box(Modifier.fillMaxWidth().heightIn(max = 350.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
                        SelectionContainer { Text(logText, style = MaterialTheme.typography.bodySmall, color = Color(0xFFDDE7FF)) }
                    }
                }
                }
                if (page == "连接") RemotePanel("控制密码") {
                OutlinedTextField(newPassword, { newPassword = it }, label = { Text("新控制密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                Button(onClick = { perform("password", JSONObject().put("password", newPassword), onSuccess = {
                    password = newPassword; newPassword = ""
                    if (rememberPassword) prefs.edit().putString("password", password).apply()
                }) }, enabled = !busy && newPassword.length in 4..64) { Text("修改控制密码") }
                Text("账号固定 admin。当前使用局域网 HTTP；不建议在不可信 Wi-Fi 中使用简单密码。远程 root 终端未开放。", style = MaterialTheme.typography.bodySmall)
                }
            }
            selectedWifi?.let { selected ->
                AlertDialog(onDismissRequest = { selectedWifi = null }, title = { Text("连接 $selected") },
                    text = { OutlinedTextField(staPassword, { staPassword = it }, Modifier.fillMaxWidth(),
                        label = { Text("Wi‑Fi 密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation()) },
                    confirmButton = { Button(onClick = {
                        selectedWifi = null
                        perform("wifi/connect", JSONObject().put("ssid", selected).put("password", staPassword))
                    }, enabled = staPassword.length in 8..63) { Text("连接") } },
                    dismissButton = { TextButton(onClick = { selectedWifi = null }) { Text("取消") } })
            }
            pendingUsbPreset?.let { mode ->
                AlertDialog(onDismissRequest = { pendingUsbPreset = null }, title = { Text("写入 USB 启动预设 $mode？") },
                    text = { Text("将校验并写入 RK3399 的 resource 与 boot 启动分区。当前连接不变，下次重启后生效；请保留完整固件以备恢复。") },
                    confirmButton = { Button(onClick = {
                        pendingUsbPreset = null
                        perform("usb-preset", JSONObject().put("mode", mode).put("confirm", "APPLY_USB_PRESET"))
                    }) { Text("确认写入") } },
                    dismissButton = { TextButton(onClick = { pendingUsbPreset = null }) { Text("取消") } })
            }
        }
    }
}

@Composable
private fun RemotePanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Color.White.copy(alpha = 0.88f)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Color(0xFF20243A))
            content()
        }
    }
}
