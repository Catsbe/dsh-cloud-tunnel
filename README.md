# DSH 云服务器远程穿透 (dsh-cloud-tunnel)

通过任意拥有固定公网 IP 的 Linux 云服务器建立 SSH 反向穿透隧道，配合服务端 **Nginx + mTLS 双向证书校验**，将 DeepSeek Harness (DSH) 官方 WebUI 安全、免密地转发到移动端，并在手机端实现 **局域网直连秒开 / 公网 mTLS 无感切换**。

---

## 📦 仓库结构

- `index.js` / `client.js` / `package.json` / `cordis.patch.yml`：DSH 桌面端插件（本地 HTTP/WebSocket 网桥 + SSH 反向隧道守护 + WebUI 管理面板 + 手机配对码生成）
- `android_source_code/`：配套 Android 客户端源码（原生 `WebView` + 内置 `.p12` mTLS 证书管理 + 局域网/公网双轨秒切引擎）

---

## 🌟 核心特性

1. **通用云端支持**：任意 Linux 服务器（Ubuntu / Debian / Alibaba Cloud Linux 等）均可部署。
2. **双重 SSH 认证**：支持 **私钥文件**（如 `~/.ssh/id_rsa`）或 **账号密码** 登录。
3. **零信任双层鉴权**：
   - **公网侧**：Nginx mTLS 双向证书校验，仅允许持有对应 `.p12` 客户端证书的设备完成 TLS 握手；
   - **网桥侧**：默认仅监听 `127.0.0.1`（外部不可见）；即使手动开启「同 Wi-Fi 局域网直连」，所有 HTTP / WebSocket 请求也必须通过强随机 `Bridge Token`（及 `HttpOnly; SameSite=Lax` 会话 Cookie）鉴权，并严格校验 `Host`、`Origin`、`Sec-Fetch-Site`，禁用通配 CORS 与 Private Network Access。
4. **动态端口透明桥接**：自动追踪 DSH 每次启动绑定的随机本地端口，桥接到本地网桥端口（默认 `58000`），仅在网桥鉴权与同源校验全部通过后才注入 DSH 会话签名 Cookie。
5. **双轨秒切架构**：
   - **同一 Wi-Fi 下**（需在面板开启局域网直连并完成配对码/Token 导入）：手机端毫秒级探测本地网桥 `/_dsh_ping`，优先走 `http://<内网IP>:<localPort>` 直连 PC，零云端延迟、不走公网流量；
   - **外出 4G/5G**：自动切换 `https://<域名>:<公网端口>` 走云服务器 mTLS 隧道，断线重连后保留当前 WebUI 路径。
6. **无感生命周期守护**：随 DSH 启动/关闭自动拉起或清理 SSH 隧道进程，断网唤醒后自动重连；密码认证不再向临时 `askpass` 脚本写入明文密码（仍建议优先使用 SSH 私钥登录）。

---

## 🖥️ PC 端插件安装与配置

### 1. 安装插件到 DSH

**方式 A：从 GitHub 直接安装（推荐）**

在命令行运行（或在 DSH 设置 → 插件管理中从 GitHub 安装）：

```sh
dsh plugin add github:Catsbe/dsh-cloud-tunnel
```

**方式 B：本地开发调试安装**

将仓库根目录下的插件文件复制（或软链接）到 DSH 插件目录（如 `~/.dsh/custom-plugins/dsh-cloud-tunnel`），并在当前 profile 中启用 `dsh-cloud-tunnel`。

### 2. 配置文件示例
插件运行后会在 `~/.dsh/cloud-tunnel-config.json` 生成配置（也可以直接在 DSH 左下角「云服务器远程穿透」弹窗中可视化填写）：

```json
{
  "enabled": false,
  "serverHost": "your-server-ip-or-domain",
  "serverPort": 22,
  "serverUser": "root",
  "authType": "key",
  "keyPath": "~/.ssh/id_rsa",
  "password": "",
  "remotePort": 58001,
  "localPort": 58000,
  "publicUrl": "https://dsh.example.com:8443",
  "autoStart": true,
  "lanDirectEnabled": false,
  "bridgeToken": "自动生成的 256-bit 随机令牌"
}
```

---

## ☁️ 云服务器部署

在全新 Linux 服务器上，点击插件面板中的 **「📜 配置脚本」** 按钮复制自动生成的初始化脚本并运行，即可完成：
1. 安装 Nginx 与 OpenSSL；
2. 生成私有 CA（`/etc/nginx/dsh_mtls/ca.crt`）与手机端证书（`/etc/nginx/dsh_mtls/dsh-client.p12`，每次自动随机生成强密码并在脚本末尾输出）；
3. 配置 Nginx mTLS 双向校验与 WebSocket 反向代理到 `127.0.0.1:<remotePort>`；
4. 配置 `sshd` 心跳超时回收并尝试放行防火墙端口。

> **安全建议**：
> - 请在云厂商控制台安全组及本机防火墙（`ufw` / `nftables`）中放行你的公网 mTLS 端口（如 `8443/tcp`）。
> - 若服务器同时运行重要业务，建议对 SSH `22` 端口配置固定源 IP 白名单，或使用受限专用隧道账号（`AllowTcpForwarding remote`、`PermitOpen none`、`ForceCommand /bin/false`）。

---

## 📱 Android 端构建

进入 `android_source_code/` 目录查看 [android_source_code/README.md](android_source_code/README.md)，按说明配置环境或直接运行 `.\build_apk.ps1` 编译安装包。
