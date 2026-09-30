package com.example.iperf_wifi_tool

import android.content.Context
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
        setContent { Iperf_wifi_toolTheme { RemoteControlScreen(host, this) } }
    }

    companion object { const val EXTRA_HOST = "host" }
}

private class ControlApi(private val host: String, private val password: String) {
    fun request(path: String, body: JSONObject? = null): JSONObject {
        require(host.matches(Regex("[A-Za-z0-9.:-]+"))) { "目标地址格式不正确" }
        val connection = (URL("http://$host:8765/api/v1/$path").openConnection() as HttpURLConnection)
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 65000
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
            if (!response.optBoolean("ok")) error(response.optString("error", response.optString("output", "操作失败")))
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
    var pendingUsbMode by remember { mutableStateOf<String?>(null) }
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
                apSsid = response.optString("ssid").takeUnless { it == "—" }.orEmpty()
                band = response.optString("wifiBand", "2g")
                channel = response.optString("channel", "1")
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
                status = response.optString("output").ifBlank { "操作成功" }
                onSuccess?.invoke()
                succeeded = true
            } catch (e: Exception) { status = e.message ?: "操作失败" }
            finally { busy = false }
            if (succeeded) refresh()
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("RK3399 远程控制") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(host, { host = it; authenticated = false }, Modifier.fillMaxWidth(), label = { Text("RK3399 地址") }, singleLine = true)
            OutlinedTextField(password, { password = it; authenticated = false }, Modifier.fillMaxWidth(), label = { Text("控制密码（账号 admin）") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            Row { Checkbox(rememberPassword, { rememberPassword = it }); Text("在本机记住密码", Modifier.padding(top = 12.dp)) }
            Button(onClick = ::refresh, enabled = !busy && host.isNotBlank() && password.isNotBlank()) { Text(if (authenticated) "刷新状态" else "验证密码并连接") }
            Text(status, color = if (authenticated) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (authenticated && snapshot != null) {
                val s = snapshot!!
                HorizontalDivider()
                Text("网络状态", style = MaterialTheme.typography.titleMedium)
                Text("有线 ${s.optString("ethRole")} · ${s.optString("ethIp")} · ${s.optString("ethMode")}")
                Text("Wi-Fi ${s.optString("wifiMode")} · ${s.optString("ssid")} · ${s.optString("wlanIp")} · ${s.optString("wifiBand")} CH ${s.optString("channel")}")
                HorizontalDivider()
                Text("NAS 与测试服务", style = MaterialTheme.typography.titleMedium)
                val services = s.optJSONArray("services")
                if (services != null) for (i in 0 until services.length()) {
                    val item = services.getJSONObject(i)
                    val id = item.optString("id")
                    Column {
                        Text("${item.optString("name")} · ${if (item.optBoolean("running")) "运行中" else "未运行"} · 端口 ${item.optInt("port")}")
                        Row {
                            if (item.optBoolean("controllable")) Switch(item.optBoolean("enabled"), { enabled ->
                                perform("service", JSONObject().put("id", id).put("enabled", enabled))
                            }, enabled = !busy)
                            if (item.optBoolean("editablePort")) {
                                var portText by remember(id) { mutableStateOf(item.optInt("port").toString()) }
                                OutlinedTextField(portText, { portText = it.filter(Char::isDigit).take(5) }, Modifier.width(110.dp), label = { Text("端口") }, singleLine = true)
                                TextButton(onClick = { perform("port", JSONObject().put("id", id).put("port", portText.toIntOrNull() ?: 0)) }, enabled = !busy) { Text("保存") }
                            }
                        }
                    }
                }
                HorizontalDivider()
                Text("Wi-Fi 模式与热点", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ perform("wifi", JSONObject().put("mode", "ap")) }, enabled = !busy) { Text("AP") }
                    OutlinedButton({ perform("wifi", JSONObject().put("mode", "sta")) }, enabled = !busy) { Text("STA") }
                }
                OutlinedTextField(apSsid, { apSsid = it }, Modifier.fillMaxWidth(), label = { Text("热点 SSID") }, singleLine = true)
                OutlinedTextField(apPassword, { apPassword = it }, Modifier.fillMaxWidth(), label = { Text("新热点密码（8–63 位）") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(band == "2g", { band = "2g"; channel = "1" }, label = { Text("2.4 GHz") })
                    FilterChip(band == "5g", { band = "5g"; channel = "36" }, label = { Text("5 GHz") })
                }
                val channels = if (band == "5g") listOf(36, 40, 44, 48, 52, 56, 60, 64, 149, 153, 157, 161, 165) else (1..13).toList()
                var expanded by remember { mutableStateOf(false) }
                Box { OutlinedButton({ expanded = true }) { Text("信道 $channel") }; DropdownMenu(expanded, { expanded = false }) {
                    channels.forEach { ch -> DropdownMenuItem(text = { Text("CH $ch") }, onClick = { channel = ch.toString(); expanded = false }) }
                } }
                Button(onClick = { perform("ap", JSONObject().put("ssid", apSsid).put("password", apPassword).put("band", band).put("channel", channel.toIntOrNull() ?: 0)) }, enabled = !busy && apPassword.length in 8..63) { Text("保存热点配置") }
                Text("切换 AP/STA 或修改热点会断开当前 Wi-Fi；DFS 52–64 只限屏蔽箱测试。", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text("网口模式", style = MaterialTheme.typography.titleMedium)
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
                HorizontalDivider()
                Text("USB 端口角色", style = MaterialTheme.typography.titleMedium)
                Text("Type-C · fe800000 · 固件 ${s.optString("usbCDeviceTree")} · 当前 ${s.optString("usbCMode")}（此固件不可运行时切换）")
                Text("USB3 Type-A · fe900000 · 固件 ${s.optString("usbADeviceTree")} · 当前 ${s.optString("usbAMode")}")
                Text("Gadget ${s.optString("usbGadgetState")} · 外设 ${s.optInt("usbAAttached")} 个", style = MaterialTheme.typography.bodySmall)
                val usbCableConnected = s.optString("usbAMode") != "host" && s.optString("usbGadgetState") !in setOf("", "—", "not attached")
                val usbCanSwitch = !busy && s.optBoolean("usbASwitchable") && !usbCableConnected && s.optInt("usbAAttached") == 0
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("host" to "Host", "peripheral" to "Device", "otg" to "OTG").forEach { (mode, label) ->
                        OutlinedButton(onClick = { pendingUsbMode = mode }, enabled = usbCanSwitch && s.optString("usbAMode") != mode) { Text(label) }
                    }
                }
                Text("先拔下电脑数据线或安全卸载 USB 外设，再刷新状态；切换可能断开 ADB，重启恢复默认角色。", style = MaterialTheme.typography.bodySmall)
                if (usbCableConnected) Text("电脑数据线仍连接，暂不能切换。", color = MaterialTheme.colorScheme.error)
                if (s.optInt("usbAAttached") > 0) Text("Type-A 上仍有外设，暂不能切换。", color = MaterialTheme.colorScheme.error)
                HorizontalDivider()
                Text("日志", style = MaterialTheme.typography.titleMedium)
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
                if (logText.isNotBlank()) Text(logText, style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text("控制密码", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(newPassword, { newPassword = it }, label = { Text("新控制密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                Button(onClick = { perform("password", JSONObject().put("password", newPassword), onSuccess = {
                    password = newPassword; newPassword = ""
                    if (rememberPassword) prefs.edit().putString("password", password).apply()
                }) }, enabled = !busy && newPassword.length in 4..64) { Text("修改控制密码") }
                Text("账号固定 admin。当前使用局域网 HTTP；不建议在不可信 Wi-Fi 中使用简单密码。远程 root 终端未开放。", style = MaterialTheme.typography.bodySmall)
            }
            pendingUsbMode?.let { mode ->
                AlertDialog(onDismissRequest = { pendingUsbMode = null }, title = { Text("确认切换 USB3 Type-A？") },
                    text = { Text("目标模式：$mode。操作只在本次运行有效，可能中断 USB ADB。请确认电脑数据线和外设均已拔下。") },
                    confirmButton = { Button(onClick = {
                        pendingUsbMode = null
                        perform("usb", JSONObject().put("port", "type_a").put("mode", mode))
                    }) { Text("确认切换") } },
                    dismissButton = { TextButton(onClick = { pendingUsbMode = null }) { Text("取消") } })
            }
        }
    }
}
