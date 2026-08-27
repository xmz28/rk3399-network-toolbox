# RK3399 网络工具箱项目说明

## 1. 项目目标

本项目是配合 RK3399 使用的 Android 局域网测试与文件服务工具箱。当前应用名为
“RK3399 网络工具箱”，包名为 `com.example.iperf_wifi_tool`，最低支持 Android 9
（API 28）。

主要目标：

1. 自动找到 RK3399，避免反复手输 IP。
2. 集成 iperf3 TCP/UDP 压测。
3. 检测并访问 RK3399 上的 Samba、HTTP、FTP、SFTP 和 OpenSpeedTest。
4. 后续在 App 内完成文件浏览、上传、下载、删除及协议读写测速。

## 2. RK3399 默认地址和端口

| 服务 | 地址或端口 | 当前客户端能力 |
| --- | ---: | --- |
| 主机名 | `a.local` | 自动解析并优先选择 |
| Samba | `445` | TCP 连通检测、客户端地址入口 |
| HTTP | `3923` | HTTP 握手、内置 WebView 打开 |
| FTP | `12345` | `220` banner 验证、客户端地址入口 |
| SFTP | `1234` | SSH banner 验证、客户端地址入口 |
| OpenSpeedTest | `3000` | HTTP 握手、内置 WebView 打开 |
| iperf3 | `5201` | 内置 iperf3 客户端完整压测 |

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

测试手机：Redmi K30 5G，Android 13（API 33）。目标设备：AIO RK3399。

- `a.local` 已在 App 内解析为 `192.168.31.1`。
- Samba 445 TCP 可连接。
- HTTP 3923 返回 HTTP 200。
- FTP 12345 返回 `220 Service ready for new user.`。
- SFTP 1234 返回 SSH banner。
- OpenSpeedTest 3000 返回 HTTP 200，并能在内置页面正常显示。
- iperf3 5201 TCP 可连接。
- 内置 iPerf 3.21 已在 Android 真机执行。
- `testDebugUnitTest`、`assembleDebug`、`lintDebug` 通过；Lint 为 0 errors。

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

## 8. 下一阶段：内置文件操作

当前基线提交之后实施以下能力：

1. Samba/FTP/SFTP 原生登录和目录浏览。
2. 使用 Android 系统文件选择器选择本地文件并上传。
3. 下载远端文件到用户通过系统文件选择器指定的位置。
4. 新建目录、删除文件、返回上级目录和刷新。
5. 上传/下载进度、瞬时或平均速度、耗时及明确错误信息。
6. 默认参数可预填，但账号、密码、共享名和路径必须可修改。
7. 文件操作与测速共用同一套协议连接层，不把 TCP 端口在线当成文件传输成功。

## 9. 接手注意事项

- 自动连接必须优先使用明确的 `a.local` 结果，不要通过模糊设备名误连其他设备。
- 所有网络和文件 I/O 必须放到后台线程，UI 回调回到主线程。
- 上传测试产生的远端临时文件必须可识别并支持清理。
- 不要在日志、URI 或异常文本中输出明文密码。
- 构建成功不等于真机协议操作成功；Samba/FTP/SFTP 上传下载必须分别做真机验证。
