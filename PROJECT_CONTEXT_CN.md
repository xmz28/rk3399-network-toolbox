# RK3399 网络工具箱项目说明

## 1. 项目目标

本项目是配合 RK3399 使用的 Android 局域网测试与文件服务工具箱。当前应用名为
“RK3399 网络工具箱”，包名为 `com.example.iperf_wifi_tool`，最低支持 Android 9
（API 28）。

主要目标：

1. 自动找到 RK3399，避免反复手输 IP。
2. 集成 iperf3 TCP/UDP 压测。
3. 检测并访问 RK3399 上的 Samba、HTTP、FTP、SFTP 和 OpenSpeedTest。
4. 在 App 内完成 Samba、FTP、SFTP 文件浏览、上传、下载和删除。
5. 密码验证后远程管理 RK3399 服务、Wi-Fi 和网口，并查看状态与日志。
6. 查看 USB 角色；选择 Type-C Host / Type-A OTG（A）或 Type-C OTG / Type-A Host（B）
   启动预设，下次重启生效。

## 2. RK3399 默认地址和端口

| 服务 | 地址或端口 | 当前客户端能力 |
| --- | ---: | --- |
| 主机名 | `a.local` | 自动解析并优先选择 |
| Samba | `445` | SMB2/SMB3 内置文件客户端 |
| HTTP | `3923` | HTTP 握手、内置 WebView 打开 |
| FTP | `12345` | FTP 内置文件客户端、被动模式传输 |
| SFTP | `1234` | SFTP 内置文件客户端 |
| OpenSpeedTest | `3000` | HTTP 握手、内置 WebView 打开 |
| iperf3 | `5201` | 内置 iperf3 客户端完整压测 |
| 远程控制 | `8765` | 密码验证后读写控制接口 |

测试环境中 `a.local` 解析为 `192.168.31.1`，实际地址以现场 mDNS 结果为准。

## 3. mDNS / a.local 发现流程

入口：

- `app/src/main/java/com/example/iperf_wifi_tool/MdnsDiscovery.kt`
- `app/src/main/java/com/example/iperf_wifi_tool/MdnsHostResolver.kt`

发现顺序：

1. 使用 Android 当前活动网络解析 `a.local`。
2. 对部分 Java 解析 `.local` 失败的 ROM，调用无需 Root 的系统 `ping`，只提取解析出的 IP。
3. 尝试发送 mDNS A 记录查询。
4. 同时浏览 `_iperf3._tcp.local` DNS-SD 服务。
5. 所有自动发现均失败时仍可切换到手动 IP。

不要把 `a.local` 和 `_iperf3._tcp.local` 混为一谈：前者是主机名，后者是 DNS-SD
服务类型。

## 4. 主要代码入口

| 文件 | 作用 |
| --- | --- |
| `MainActivity.kt` | Compose 主界面、设备选择、服务面板、iperf3 参数 |
| `MdnsDiscovery.kt` | `a.local` 和 iperf3 DNS-SD 发现编排 |
| `MdnsHostResolver.kt` | Android/系统/mDNS 多级主机名解析 |
| `ServiceTools.kt` | 六项服务端口与协议握手检测 |
| `WebClientActivity.kt` | HTTP 和 OpenSpeedTest 内置页面 |
| `RemoteFilesActivity.kt` | Samba/FTP/SFTP 登录、目录和文件操作界面 |
| `RemoteFileClients.kt` | SMB2/3、FTP、SFTP 协议连接与流式传输实现 |
| `RemoteControlActivity.kt` | 分区式远程控制、NAS 共用改密、STA 扫描与连接、日志 |
| `IperfRunner.kt` | 启动、停止并解析内置 iperf3 输出 |
| `IperfCommand.kt` | iperf3 参数构造与输出解析 |

## 5. iperf3 行为

- TCP/UDP。
- 手机上传到 RK3399，或使用 `-R` 从 RK3399 下载到手机。
- 1、4、8、10 路并发。
- UDP UI 中填写的是总目标带宽，程序自动除以并发数后生成每流 `-b`。
- 支持 10 秒、30 秒、60 秒、5 分钟、1 小时和循环压测。
- 显示当前、平均、峰值、UDP jitter 和丢包。

APK 内含 iPerf 3.21 的 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` 二进制。

## 6. 已验证结果

远程控制默认账号/密码均为 `admin`；手机可记住密码，私有偏好数据不参加 Android
自动备份。控制接口使用局域网 HTTP Basic，不能暴露到公网；远程 root 终端未开放。
切换当前使用的 AP 或网口后，连接可能立即中断，需要重新连入并刷新状态。
当前远程页面已通过 `assembleDebug`，RK3399 实机接口完成密码拒绝、状态读取、
FTP 开关与端口操作验证；Redmi K30 5G 实机已显示远程 USB 状态和带线禁用态。
板上已通过有线 API 完成 AP→STA、STA 扫描、STA→AP 操作。NAS 共用改密实测
HTTP、FTP、Samba 使用新密码可登录，之后已恢复 `admin`；SFTP 共用 Primitive
FTPd 密码配置，但本轮未独立登录。远程页按概览、服务、Wi-Fi、连接、日志分区，
参考 `bleconnect` 的浅色渐变与卡片风格，信道按 2.4/5 GHz 分组展示。
手机端选择扫描结果并让 RK3399 接入加密热点的完整流程尚待已知热点密码验证。
板端已修复旧网口守护进程与 App 争夺 `eth0`、以及热点 DHCP/策略路由问题；
手机实测接入 RK3399 热点获 `192.168.31.116` 并能访问控制接口。远程页可查看
热点 DHCP 是否运行。
USB 控制页显示 Type-C、Type-A 当前角色与下一次启动的 A/B 预设。预设会写入
板上已备份且已校验的启动镜像，不要求拔掉当前线缆，也不会立即重启。A/B 的
设备树与 Gadget 控制器切换已在这台无 NPU 的板上重启验证；刷机仍有断电风险，
镜像仅适用于当前板和固件，详见 `rk3399_control/USB_PRESETS_CN.md`。

测试手机：Redmi K30 5G，Android 13（API 33）。目标设备：AIO RK3399。

- `a.local` 已在 App 内解析为 `192.168.31.1`。
- Samba 445 TCP 可连接。
- HTTP 3923 返回 HTTP 200。
- FTP 12345 返回 `220 Service ready for new user.`。
- SFTP 1234 返回 SSH banner。
- OpenSpeedTest 3000 返回 HTTP 200，并能在内置页面正常显示。
- iperf3 5201 TCP 可连接。
- 内置 iPerf 3.21 已在 Android 真机执行。
- Samba 使用 `NAS` 共享完成目录读取、上传、下载内容校验和删除闭环。
- FTP 使用 `/storage/emulated/0/NAS` 完成目录读取、上传、下载内容校验和删除闭环。
- SFTP 使用 `/storage/emulated/0/NAS` 完成目录读取、上传、下载内容校验和删除闭环。
- `testDebugUnitTest`、`assembleDebug` 和 `lintDebug` 已通过；Lint 为 0 errors，保留若干非阻断 warning。

注意：SFTP 服务端 banner 当前为
`SSH-2.0-${POM.ARTIFACTID}-${POM.VERSION}`，说明服务端构建变量未替换，但不影响 SSH
协议识别。

## 7. 构建方法

PowerShell：

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat --no-daemon --offline testDebugUnitTest assembleDebug lintDebug
```

APK：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 8. 内置文件操作

当前版本已经实现：

1. Samba/FTP/SFTP 原生登录和目录浏览。
2. 使用 Android 系统文件选择器选择本地文件并上传。
3. 下载远端文件到用户通过系统文件选择器指定的位置。
4. 新建目录、删除文件、返回上级目录和刷新。
5. 上传/下载进度、瞬时或平均速度、耗时及明确错误信息。
6. 默认参数可预填，但账号、密码、共享名和路径必须可修改。
7. 文件操作与测速共用同一套协议连接层，不把 TCP 端口在线当成文件传输成功。

当前 RK3399 测试配置使用用户名 `admin`，Samba 共享名为 `NAS`。密码、端口、共享名、
域和远端路径都能在页面中修改。Samba 默认路径是共享根目录 `/`；FTP/SFTP 默认路径是
`/storage/emulated/0/NAS`，避免普通文件操作误入 Android 系统根目录。

SFTP 页面会明确提示实验环境当前接受首次连接的主机密钥。Android 系统自带的裁剪版
Bouncy Castle Provider 不满足 SSHJ 算法需求，客户端会在建立 SFTP 会话前装入 APK
随带的新版 Provider。

## 9. 接手注意事项

- 自动连接必须优先使用明确的 `a.local` 结果，不要通过模糊设备名误连其他设备。
- 所有网络和文件 I/O 必须放到后台线程，UI 回调回到主线程。
- 上传测试产生的远端临时文件必须可识别并支持清理。
- 不要在日志、URI 或异常文本中输出明文密码。
- 构建成功不等于真机协议操作成功；Samba/FTP/SFTP 上传下载必须分别做真机验证。
