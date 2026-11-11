# DSH 云端直连 4.0 (DSH-Cloud)

包名 `com.deepseek.dshcloud`，专为 **局域网直连秒开 + 云端 mTLS 转发** 双轨架构打造的无第三方依赖原生 Android WebView 客户端。

## 核心特性

1. **多电脑设备中心与手动选择**：启动默认进入「设备中心」，同时探测所有已绑定电脑的局域网与公网 mTLS 在线状态并显示延迟；支持局域网 `/24` 扫描自动识别已绑定电脑或发现新主机，由用户手动点击选择要连接的电脑及链路（不再启动即默认自动连入），连接后可通过右上角悬浮胶囊随时切换电脑。
2. **网桥 Token + 内置 mTLS 双向鉴权**：自动解析配对码中的 `bridgeToken` 并在握手/WebView 加载前完成网桥会话鉴权；支持直接在 App 内导入/按设备隔离存储 `.p12` 客户端证书文件（多电脑同证书也可一键复用）与持久化密码，同时兼容 Android `KeyChain` 系统证书及自定义服务端 CA / SHA-256 指纹固定。
3. **局域网 / 公网无感双轨秒切**：
   - 选中某台电脑连接时优先检测局域网 `/_dsh_ping` 与公网 mTLS 通道；离开 Wi-Fi（切至 4G/5G）或局域网中断时自动切换云服务器 mTLS 转发并保留当前 WebUI 路径。
   - 当手机重新连回办公/家庭 Wi-Fi 时，后台自动检测当前连接电脑的局域网网桥是否恢复，支持自动切回局域网直连。
4. **免手输一键配对**：配合 DSH 桌面端插件 `dsh-cloud-tunnel`（点击「复制配置码」生成含 `token` 的 `dsh-tunnel://cfg?d=...`），在手机端一键粘贴或扫局域网即可完成绑定。

## 构建说明

### 方式一：使用 PowerShell 轻量化脚本构建（无需 Gradle 下载依赖）

前置要求：JDK 17+、`ANDROID_HOME`（已安装 `build-tools` 与 `platforms`）。

1. （可选）正式发布签名：复制 `signing.properties.example` 为 `signing.properties` 并填入你自己的 `.jks` 路径与口令（`signing.properties` 与 `*.jks` 均已加入 `.gitignore`，不会提交到 Git）。如果不提供，脚本会自动在临时目录生成一次性的本地测试签名证书。
2. 在 `android_source_code` 目录执行：

```powershell
.\build_apk.ps1
```

产物输出为 `android_source_code\build-out\DSH-Cloud-V4.0.apk`。

### 方式二：Android Studio / Gradle

直接使用 Android Studio 打开 `android_source_code/` 目录即可编译调试。
