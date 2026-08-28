package com.example.iperf_wifi_tool

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.Duration
import java.security.Security
import java.util.EnumSet

data class RemoteConnectionConfig(
    val service: RkService,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val share: String = "NAS",
    val domain: String = "",
)

data class RemoteEntry(
    val name: String,
    val directory: Boolean,
    val size: Long = 0,
    val modifiedMillis: Long? = null,
)

typealias TransferProgress = (transferred: Long, total: Long?) -> Unit

interface RemoteFileClient {
    fun list(path: String): List<RemoteEntry>
    fun makeDirectory(path: String)
    fun delete(path: String, directory: Boolean)
    fun upload(path: String, input: InputStream, total: Long?, progress: TransferProgress)
    fun download(path: String, output: OutputStream, total: Long?, progress: TransferProgress)
}

object RemoteFileClientFactory {
    fun create(config: RemoteConnectionConfig): RemoteFileClient = when (config.service) {
        RkService.FTP -> FtpRemoteFileClient(config)
        RkService.SFTP -> SftpRemoteFileClient(config)
        RkService.SAMBA -> SmbRemoteFileClient(config)
        else -> error("${config.service.title} 不是文件协议")
    }
}

private class FtpRemoteFileClient(private val config: RemoteConnectionConfig) : RemoteFileClient {
    private fun connect(): FTPClient = FTPClient().apply {
        connectTimeout = 5_000
        defaultTimeout = 5_000
        setDataTimeout(Duration.ofSeconds(15))
        controlEncoding = "UTF-8"
        connect(config.host, config.port)
        if (!login(config.username, config.password)) error("FTP 登录失败：$replyString")
        enterLocalPassiveMode()
        setFileType(FTP.BINARY_FILE_TYPE)
    }

    private inline fun <T> withClient(block: (FTPClient) -> T): T {
        val client = connect()
        return try { block(client) } finally {
            try { client.logout() } catch (_: Exception) {}
            if (client.isConnected) try { client.disconnect() } catch (_: Exception) {}
        }
    }

    override fun list(path: String): List<RemoteEntry> = withClient { client ->
        client.listFiles(ftpPath(path)).map { file ->
            RemoteEntry(file.name, file.isDirectory, file.size, file.timestampInstant?.toEpochMilli())
        }.filterNot { it.name == "." || it.name == ".." }.sortedEntries()
    }

    override fun makeDirectory(path: String) = withClient { client ->
        if (!client.makeDirectory(ftpPath(path))) error("FTP 新建目录失败：${client.replyString}")
    }

    override fun delete(path: String, directory: Boolean) = withClient { client ->
        val ok = if (directory) client.removeDirectory(ftpPath(path)) else client.deleteFile(ftpPath(path))
        if (!ok) error("FTP 删除失败：${client.replyString}")
    }

    override fun upload(path: String, input: InputStream, total: Long?, progress: TransferProgress) = withClient { client ->
        val counted = ProgressInputStream(input, total, progress)
        if (!client.storeFile(ftpPath(path), counted)) error("FTP 上传失败：${client.replyString}")
    }

    override fun download(path: String, output: OutputStream, total: Long?, progress: TransferProgress) = withClient { client ->
        val counted = ProgressOutputStream(output, total, progress)
        if (!client.retrieveFile(ftpPath(path), counted)) error("FTP 下载失败：${client.replyString}")
    }

    private fun ftpPath(path: String) = if (path.isBlank()) "/" else path
}

private class SftpRemoteFileClient(private val config: RemoteConnectionConfig) : RemoteFileClient {
    private inline fun <T> withClient(block: (net.schmizz.sshj.sftp.SFTPClient) -> T): T {
        installBundledBouncyCastle()
        val ssh = SSHClient()
        ssh.addHostKeyVerifier(PromiscuousVerifier())
        ssh.connect(config.host, config.port)
        try {
            ssh.authPassword(config.username, config.password)
            ssh.newSFTPClient().use { return block(it) }
        } finally {
            try { ssh.disconnect() } catch (_: Exception) {}
            try { ssh.close() } catch (_: Exception) {}
        }
    }

    override fun list(path: String): List<RemoteEntry> = withClient { sftp ->
        sftp.ls(unixPath(path)).map { item ->
            RemoteEntry(item.name, item.isDirectory, item.attributes.size, item.attributes.mtime * 1_000L)
        }.filterNot { it.name == "." || it.name == ".." }.sortedEntries()
    }

    override fun makeDirectory(path: String) = withClient { it.mkdir(unixPath(path)) }

    override fun delete(path: String, directory: Boolean) = withClient {
        if (directory) it.rmdir(unixPath(path)) else it.rm(unixPath(path))
    }

    override fun upload(path: String, input: InputStream, total: Long?, progress: TransferProgress) = withClient { sftp ->
        sftp.open(unixPath(path), EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { remote ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var offset = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                remote.write(offset, buffer, 0, count)
                offset += count
                progress(offset, total)
            }
        }
    }

    override fun download(path: String, output: OutputStream, total: Long?, progress: TransferProgress) = withClient { sftp ->
        sftp.open(unixPath(path), EnumSet.of(OpenMode.READ)).use { remote ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var offset = 0L
            while (true) {
                val count = remote.read(offset, buffer, 0, buffer.size)
                if (count <= 0) break
                output.write(buffer, 0, count)
                offset += count
                progress(offset, total)
            }
        }
    }

    private fun unixPath(path: String) = if (path.isBlank()) "/" else path

    private fun installBundledBouncyCastle() = synchronized(SftpRemoteFileClient::class.java) {
        // Android 自带的裁剪版 BC 会抢占同名 Provider，SSHJ 随后找不到所需 SHA-256 实现。
        val current = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        if (current == null || current.version < 1.8) {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }
}

private class SmbRemoteFileClient(private val config: RemoteConnectionConfig) : RemoteFileClient {
    private inline fun <T> withShare(block: (DiskShare) -> T): T {
        SMBClient().use { client ->
            client.connect(config.host, config.port).use { connection ->
                val auth = AuthenticationContext(config.username, config.password.toCharArray(), config.domain)
                connection.authenticate(auth).use { session ->
                    val connected = session.connectShare(config.share)
                    if (connected !is DiskShare) {
                        connected.close()
                        error("${config.share} 不是磁盘共享")
                    }
                    connected.use { return block(it) }
                }
            }
        }
    }

    override fun list(path: String): List<RemoteEntry> = withShare { share ->
        share.list(smbPath(path)).map { item ->
            val directory = item.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
            RemoteEntry(item.fileName, directory, item.endOfFile, item.lastWriteTime.toEpochMillis())
        }.filterNot { it.name == "." || it.name == ".." }.sortedEntries()
    }

    override fun makeDirectory(path: String) = withShare { it.mkdir(smbPath(path)) }

    override fun delete(path: String, directory: Boolean) = withShare {
        if (directory) it.rmdir(smbPath(path), false) else it.rm(smbPath(path))
    }

    override fun upload(path: String, input: InputStream, total: Long?, progress: TransferProgress) {
        withShare { share ->
        share.openFile(
            smbPath(path),
            EnumSet.of(AccessMask.GENERIC_WRITE),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        ).use { remote ->
            ProgressOutputStream(remote.outputStream, total, progress).use { output -> input.copyTo(output) }
        }
    }
    }

    override fun download(path: String, output: OutputStream, total: Long?, progress: TransferProgress) {
        withShare { share ->
        share.openFile(
            smbPath(path),
            EnumSet.of(AccessMask.GENERIC_READ),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        ).use { remote ->
            ProgressInputStream(remote.inputStream, total, progress).use { input -> input.copyTo(output) }
        }
    }
    }

    private fun smbPath(path: String): String = path.trim('/').replace('/', '\\')
}

private class ProgressInputStream(
    input: InputStream,
    private val total: Long?,
    private val progress: TransferProgress,
) : FilterInputStream(input) {
    private var count = 0L
    override fun read(): Int = super.read().also { if (it >= 0) report(1) }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, length).also { if (it > 0) report(it) }
    private fun report(delta: Int) { count += delta; progress(count, total) }
}

private class ProgressOutputStream(
    output: OutputStream,
    private val total: Long?,
    private val progress: TransferProgress,
) : FilterOutputStream(output) {
    private var count = 0L
    override fun write(value: Int) { out.write(value); report(1) }
    override fun write(buffer: ByteArray, offset: Int, length: Int) { out.write(buffer, offset, length); report(length) }
    private fun report(delta: Int) { count += delta; progress(count, total) }
}

private fun List<RemoteEntry>.sortedEntries(): List<RemoteEntry> =
    sortedWith(compareByDescending<RemoteEntry> { it.directory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
