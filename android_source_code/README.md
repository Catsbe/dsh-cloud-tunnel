# DSH 云端直连 4.0 (DSH-Cloud)

包名 `com.deepseek.dshcloud`，专为 **局域网直连秒开 + 云端 mTLS 转发** 双轨架构打造的无第三方依赖原生 Android WebView 客户端。

## 核心特性

1. **内置 mTLS 客户端证书**：支持直接在 App 内导入 `.p12` 证书文件并持久化保存密码，不再触发系统证书选择器反复弹窗；同时也兼容 Android `KeyChain` 系统已安装证书及自定义服务端 CA / SHA-256 指纹固定。
2. **局域网 / 公网无感双轨秒切**：
   - 启动及重连时同时探测局域网 `/_dsh_ping`（约 650ms 快速判定）与公网 mTLS 通道，在相同 Wi-Fi 下毫秒级直连 PC；离开 Wi-Fi（切至 4G/5G）或局域网中断时自动切换云服务器 mTLS 转发并保留当前 WebUI 路径。
   - 当手机重新连回办公/家庭 Wi-Fi 时，后台自动检测电脑局域网网桥是否恢复，支持自动切回局域网直连。
3. **免手输一键配对**：配合 DSH 桌面端插件 `dsh-cloud-tunnel`（点击「复制配置码」生成 `dsh-tunnel://cfg?d=...`），在手机端一键粘贴或扫局域网即可完成全部配置。
4. **悬浮通道胶囊**：右上角可拖拽悬浮胶囊实时显示当前链路（绿=局域网直连 / 蓝=云端 mTLS）与延迟，点击可一键切换智能双轨、强制局域网、强制公网、测速或证书管理。

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
