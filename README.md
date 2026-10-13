# RK3399 Network Toolbox

配合 RK3399 使用的 Android 网络工具箱：自动发现设备、iperf3 测速、局域网文件访问，以及板端服务、网络和 USB 存储的远程控制。

手机端 App 名为 **RK3399 网络工具箱**，最低 Android 9（API 28），无需手机 root。

## 功能

- 自动解析 a.local，并发现 iperf3 DNS-SD 服务；支持手动 IP
- TCP / UDP、上传 / 下载、多路并发和循环压测
- 检测 Samba、HTTP、FTP、SFTP、OpenSpeedTest 和 iperf3 服务
- 内置 SMB2 / SMB3、FTP、SFTP 文件浏览、上传、下载和删除
- 内置网页访问文件管理与 OpenSpeedTest
- 密码认证后远程管理 RK3399 服务、热点、网口和日志
- 远程配置 IPv4 单 WAN 路由、板载/USB 自动角色、WiFi WWAN、全 LAN，以及 120 秒确认/回滚
- 实验性 STA+AP 并发入口与实际热点信道显示（热点跟随上游频段/信道）
- 查看 USB 角色并选择当前固件的下次启动预设
- 查看外部 USB 卷容量，远程安全弹出、挂载及格式化

详细代码入口、协议行为及历史实测范围见 [中文项目说明](PROJECT_CONTEXT_CN.md)。

## 配套项目

| 项目 | 作用 |
| --- | --- |
| [RK3399 Android NAS](https://github.com/xmz28/rk3399-android-nas) | 板端 NAS 与服务部署 |
| [RK3399 Control](https://github.com/xmz28/rk3399-control) | 板端 Android 控制台与 8765 控制 API |

测速和文件访问需要目标服务运行；远程管理需要板端控制台。多盘存储管理需要相应 NAS 脚本，旧版 NAS 源码快照不包含全部配套内容，后续更新需同步使用。

USB 启动预设仅适用于当前 AIO-3399C-AI 板子及对应固件，不对其他板子提供支持。镜像位于板子，本仓库不包含设备固件。

## 快速开始

1. 使用 Android Studio 打开源码工程并同步依赖。
2. 构建 Debug APK，安装到 Android 9 或更新的手机。
3. 手机与 RK3399 接入可互通的局域网，使用自动发现或填写设备 IP。
4. 选择测速、文件服务或远程控制；输入目标服务的账号密码。

NAS 文件服务密码与远程控制密码是两套独立凭据。远程控制账号固定为 admin，板端初始密码为 admin，修改后在手机端填写新密码。

## 从源码构建

- Gradle Wrapper：9.4.1；Android Gradle Plugin：9.2.1
- Gradle JVM：JDK 21（toolchain 配置）
- compileSdk：36，minor API 1；targetSdk：36
- minSdk：28；当前 versionName：1.2

PowerShell：

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon
```

依赖已缓存时可加 `--offline`。APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

项目文件夹已改为 rk3399_network_toolbox。为保持现有开发和覆盖安装，Gradle rootProject.name 仍为 iperf_wifi_tool，applicationId 仍为 com.example.iperf_wifi_tool。

## 内置 iPerf3

仓库包含 iPerf3 3.21 的 arm64-v8a、armeabi-v7a、x86、x86_64 Android 可执行文件，按 libiperf3.so 命名打包。它们是执行程序，不是本项目自行实现的 JNI 库。

二进制来源、SHA-256 和完整许可见 [第三方声明](NOTICE.md)。许可文本同时放在 APK assets/third_party 中，保留对应版权材料。本次不升级内置测速程序。

## 安全与项目状态

- 保留现有局域网 HTTP Basic 控制协议和默认账号，便于配套开发。
- App 已禁用云备份，保存密码的两份配置另明确排除出设备迁移；普通覆盖安装仍保留本机数据。
- 当前 SFTP 接受任意服务器主机密钥，尚未实现指纹确认或固定。只连接自己信任的局域网设备。
- 本次整理不改变测速、文件访问或远程控制流程，不提供签名密钥或发布 APK。
- 历史实测记录不代表本次重新执行所有设备验证，详见中文说明和 [安全说明](SECURITY.md)。

## 致谢与许可

本仓库自有代码和文档以 [MIT License](LICENSE) 发布。iPerf3、Android 二进制构建项目及 Gradle/Maven 依赖保留各自许可，见 [NOTICE.md](NOTICE.md)。
