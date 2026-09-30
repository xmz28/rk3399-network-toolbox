package com.example.iperf_wifi_tool

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.iperf_wifi_tool.ui.theme.Iperf_wifi_toolTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.max

class RemoteFilesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val service = runCatching {
            RkService.valueOf(intent.getStringExtra(EXTRA_SERVICE).orEmpty())
        }.getOrDefault(RkService.FTP)
        val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
        setContent {
            Iperf_wifi_toolTheme {
                RemoteFilesScreen(service, host, onBack = { finish() })
            }
        }
    }

    companion object {
        const val EXTRA_SERVICE = "service"
        const val EXTRA_HOST = "host"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteFilesScreen(initialService: RkService, initialHost: String, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val main = remember { Handler(Looper.getMainLooper()) }
    var host by remember { mutableStateOf(initialHost) }
    var portText by remember { mutableStateOf(initialService.port.toString()) }
    var username by remember { mutableStateOf("admin") }
    var password by remember { mutableStateOf("admin") }
    var share by remember { mutableStateOf("NAS") }
    var domain by remember { mutableStateOf("") }
    var path by remember {
        mutableStateOf(if (initialService == RkService.SAMBA) "/" else "/storage/emulated/0/NAS")
    }
    var entries by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    var selected by remember { mutableStateOf<RemoteEntry?>(null) }
    var connected by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("填写连接参数后点击“连接 / 刷新”") }
    var transferred by remember { mutableLongStateOf(0L) }
    var transferTotal by remember { mutableStateOf<Long?>(null) }
    var speedBytesPerSecond by remember { mutableDoubleStateOf(0.0) }
    var showNewFolder by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var pendingUpload by remember { mutableStateOf<Uri?>(null) }
    var pendingDownload by remember { mutableStateOf<Pair<RemoteEntry, Uri>?>(null) }

    fun connectionConfig(): RemoteConnectionConfig = RemoteConnectionConfig(
        initialService,
        host.trim(),
        portText.toIntOrNull() ?: initialService.port,
        username,
        password,
        share.trim().ifBlank { "NAS" },
        domain.trim(),
    )

    fun runOperation(label: String, block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            status = label
            try { block() } catch (e: Exception) {
                status = friendlyFileError(e)
            } finally { busy = false }
        }
    }

    fun refresh(targetPath: String = path) {
        if (host.isBlank()) { status = "请输入服务端地址"; return }
        connected = false
        runOperation("正在读取 $targetPath…") {
            val result = withContext(Dispatchers.IO) {
                RemoteFileClientFactory.create(connectionConfig()).list(targetPath)
            }
            path = normalizePath(targetPath)
            entries = result
            selected = null
            connected = true
            status = "已连接 · ${result.size} 项"
        }
    }

    fun transferProgress(startNanos: Long): TransferProgress = { current, total ->
        val elapsedSeconds = max((System.nanoTime() - startNanos) / 1_000_000_000.0, 0.001)
        main.post {
            transferred = current
            transferTotal = total
            speedBytesPerSecond = current / elapsedSeconds
        }
    }

    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingUpload = uri
    }
    val downloadPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val entry = selected
        if (uri != null && entry != null && !entry.directory) pendingDownload = entry to uri
    }

    LaunchedEffect(pendingUpload) {
        val uri = pendingUpload ?: return@LaunchedEffect
        pendingUpload = null
        val metadata = queryDocument(context, uri)
        val remotePath = joinRemote(path, metadata.first)
        runOperation("正在上传 ${metadata.first}…") {
            transferred = 0; transferTotal = metadata.second; speedBytesPerSecond = 0.0
            val started = System.nanoTime()
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    RemoteFileClientFactory.create(connectionConfig()).upload(
                        remotePath, input, metadata.second, transferProgress(started),
                    )
                } ?: error("无法读取所选文件")
            }
            status = "上传完成：${metadata.first} · ${formatBytes(transferred)} · ${formatSpeed(speedBytesPerSecond)}"
            val result = withContext(Dispatchers.IO) { RemoteFileClientFactory.create(connectionConfig()).list(path) }
            entries = result
        }
    }

    LaunchedEffect(pendingDownload) {
        val job = pendingDownload ?: return@LaunchedEffect
        pendingDownload = null
        val (entry, uri) = job
        runOperation("正在下载 ${entry.name}…") {
            transferred = 0; transferTotal = entry.size; speedBytesPerSecond = 0.0
            val started = System.nanoTime()
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                    RemoteFileClientFactory.create(connectionConfig()).download(
                        joinRemote(path, entry.name), output, entry.size, transferProgress(started),
                    )
                } ?: error("无法写入目标文件")
            }
            status = "下载完成：${entry.name} · ${formatBytes(transferred)} · ${formatSpeed(speedBytesPerSecond)}"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${initialService.title} 文件客户端") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(host, { host = it }, label = { Text("主机") }, singleLine = true, modifier = Modifier.weight(1f), enabled = !busy)
                        OutlinedTextField(
                            portText, { portText = it.filter(Char::isDigit).take(5) }, label = { Text("端口") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(110.dp), enabled = !busy,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(username, { username = it }, label = { Text("用户名") }, singleLine = true, modifier = Modifier.weight(1f), enabled = !busy)
                        OutlinedTextField(
                            password, { password = it }, label = { Text("密码") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f), enabled = !busy,
                        )
                    }
                    if (initialService == RkService.SAMBA) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(share, { share = it }, label = { Text("共享名") }, singleLine = true, modifier = Modifier.weight(1f), enabled = !busy)
                            OutlinedTextField(domain, { domain = it }, label = { Text("域（可空）") }, singleLine = true, modifier = Modifier.weight(1f), enabled = !busy)
                        }
                    }
                    if (initialService == RkService.SFTP) {
                        Text("测试环境模式：首次连接接受当前主机密钥，请确认目标确为 RK3399。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { refresh() }, enabled = !busy) { Text("连接 / 刷新") }
                OutlinedButton(onClick = { if (path != "/") refresh(parentPath(path)) }, enabled = !busy && path != "/") { Text("上级") }
                OutlinedButton(onClick = { showNewFolder = true }, enabled = !busy && connected) { Text("新建目录") }
                OutlinedButton(onClick = { uploadPicker.launch(arrayOf("*/*")) }, enabled = !busy && connected) { Text("上传") }
                OutlinedButton(
                    onClick = { selected?.let { downloadPicker.launch(it.name) } },
                    enabled = !busy && selected?.directory == false,
                ) { Text("下载") }
                OutlinedButton(onClick = { showDelete = true }, enabled = !busy && selected != null) { Text("删除") }
            }

            OutlinedTextField(
                value = path,
                onValueChange = { path = it },
                label = { Text("远端路径") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            if (busy) LinearProgressIndicator(
                progress = { transferTotal?.takeIf { it > 0 }?.let { transferred.toFloat() / it } ?: 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            if (transferred > 0) {
                Text("${formatBytes(transferred)} / ${transferTotal?.let(::formatBytes) ?: "未知"} · ${formatSpeed(speedBytesPerSecond)}", style = MaterialTheme.typography.bodySmall)
            }

            Card(Modifier.fillMaxWidth().weight(1f)) {
                if (entries.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (busy) "读取中…" else "目录为空或尚未连接") }
                } else LazyColumn(Modifier.fillMaxSize()) {
                    items(entries, key = { "${it.directory}:${it.name}" }) { entry ->
                        val chosen = selected == entry
                        Surface(
                            color = if (chosen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (entry.directory) refresh(joinRemote(path, entry.name)) else selected = entry
                            },
                        ) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (entry.directory) "📁" else "📄")
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(entry.name, fontWeight = if (entry.directory) FontWeight.SemiBold else FontWeight.Normal)
                                    Text(
                                        if (entry.directory) "目录" else formatBytes(entry.size) + entry.modifiedMillis?.let { " · ${DateFormat.getDateTimeInstance().format(Date(it))}" }.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    if (showNewFolder) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFolder = false },
            title = { Text("新建远端目录") },
            text = { OutlinedTextField(folderName, { folderName = it }, label = { Text("目录名") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val name = folderName.trim()
                    showNewFolder = false
                    if (name.isNotEmpty()) runOperation("正在新建 $name…") {
                        withContext(Dispatchers.IO) { RemoteFileClientFactory.create(connectionConfig()).makeDirectory(joinRemote(path, name)) }
                        status = "目录已创建：$name"
                        entries = withContext(Dispatchers.IO) { RemoteFileClientFactory.create(connectionConfig()).list(path) }
                    }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showNewFolder = false }) { Text("取消") } },
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("确认删除") },
            text = { Text("删除远端${if (selected?.directory == true) "目录" else "文件"}“${selected?.name}”？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    val entry = selected
                    showDelete = false
                    if (entry != null) runOperation("正在删除 ${entry.name}…") {
                        withContext(Dispatchers.IO) { RemoteFileClientFactory.create(connectionConfig()).delete(joinRemote(path, entry.name), entry.directory) }
                        status = "已删除：${entry.name}"
                        selected = null
                        entries = withContext(Dispatchers.IO) { RemoteFileClientFactory.create(connectionConfig()).list(path) }
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("取消") } },
        )
    }
}

private fun queryDocument(context: android.content.Context, uri: Uri): Pair<String, Long?> {
    var name = "upload_${System.currentTimeMillis()}"
    var size: Long? = null
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
        }
    }
    return name.replace('/', '_').replace('\\', '_') to size
}

internal fun normalizePath(path: String): String {
    val parts = path.replace('\\', '/').split('/').filter { it.isNotBlank() && it != "." }
    val clean = mutableListOf<String>()
    parts.forEach { if (it == "..") { if (clean.isNotEmpty()) clean.removeAt(clean.lastIndex) } else clean += it }
    return "/" + clean.joinToString("/")
}

internal fun joinRemote(parent: String, child: String): String = normalizePath("${normalizePath(parent)}/$child")
internal fun parentPath(path: String): String = normalizePath("${normalizePath(path)}/..")

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.2f MB".format(bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> "%.1f KB".format(bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}

private fun formatSpeed(bytesPerSecond: Double): String = "${formatBytes(bytesPerSecond.toLong())}/s"

private fun friendlyFileError(error: Exception): String {
    val message = generateSequence<Throwable>(error) { it.cause }.mapNotNull { it.message }.firstOrNull().orEmpty()
    return when {
        message.contains("authentication", true) || message.contains("logon", true) || message.contains("login", true) -> "登录失败，请检查用户名和密码"
        message.contains("access_denied", true) || message.contains("permission denied", true) -> "没有权限执行该文件操作"
        message.contains("not found", true) || message.contains("no such file", true) -> "远端文件或目录不存在"
        message.contains("connection refused", true) -> "连接被拒绝，请确认服务已启动且端口正确"
        message.contains("timed out", true) || message.contains("timeout", true) -> "连接超时，请检查网络和服务端"
        else -> "操作失败：${message.ifBlank { error.javaClass.simpleName }}"
    }
}
