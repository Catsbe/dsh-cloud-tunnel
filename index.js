import { spawn, execFile } from "node:child_process";
import { createHmac, createHash, randomBytes, timingSafeEqual } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, writeFileSync, unlinkSync, chmodSync, readdirSync } from "node:fs";
import { createServer, request as httpRequest } from "node:http";
import { connect as netConnect } from "node:net";
import { homedir, hostname, networkInterfaces } from "node:os";
import { dirname, join } from "node:path";

export const name = "dsh-cloud-tunnel";
export const inject = ["webServer"];

const CONFIG_FILE = join(homedir(), ".dsh", "cloud-tunnel-config.json");
const PID_FILE = join(homedir(), ".dsh", "cloud-tunnel-ssh.pid");
const BRIDGE_COOKIE_NAME = "dsh-bridge-session";
const BRIDGE_SESSION_TTL_MS = 7 * 24 * 3600 * 1000;
const DSH_COOKIE_TTL_MS = 24 * 3600 * 1000;

function encodeBase64Url(buffer) {
  return Buffer.from(buffer).toString("base64").replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/u, "");
}

function decodeBase64Url(str) {
  const b64 = String(str || "").replaceAll("-", "+").replaceAll("_", "/");
  const padding = "=".repeat((4 - (b64.length % 4)) % 4);
  return Buffer.from(b64 + padding, "base64");
}

function generateBridgeToken() {
  return encodeBase64Url(randomBytes(32));
}

function constantTimeEqualStr(a, b) {
  if (typeof a !== "string" || typeof b !== "string") return false;
  const ha = createHash("sha256").update(a, "utf8").digest();
  const hb = createHash("sha256").update(b, "utf8").digest();
  return timingSafeEqual(ha, hb);
}

function getDefaultConfig() {
  return {
    enabled: false,
    serverHost: "",
    serverPort: 22,
    serverUser: "root",
    authType: "key", // "key" | "password"
    keyPath: "",
    password: "",
    remotePort: 58001,
    localPort: 58000,
    publicUrl: "",
    autoStart: true,
    lanDirectEnabled: false,
    bridgeToken: ""
  };
}

function loadConfig() {
  let cfg = getDefaultConfig();
  let needsSave = false;
  try {
    if (existsSync(CONFIG_FILE)) {
      const raw = readFileSync(CONFIG_FILE, "utf8").replace(/^\uFEFF/, "");
      const data = JSON.parse(raw);
      cfg = { ...cfg, ...data };
      cfg.lanDirectEnabled = Boolean(data.lanDirectEnabled);
    } else {
      needsSave = true;
    }
  } catch (err) {
    console.warn(`[dsh-cloud-tunnel] Failed to parse config, using defaults:`, err.message);
    needsSave = true;
  }
  if (typeof cfg.bridgeToken !== "string" || cfg.bridgeToken.trim().length < 24) {
    cfg.bridgeToken = generateBridgeToken();
    needsSave = true;
  } else {
    cfg.bridgeToken = cfg.bridgeToken.trim();
  }
  if (needsSave) {
    saveConfig(cfg);
  }
  return cfg;
}

function saveConfig(cfg) {
  try {
    mkdirSync(dirname(CONFIG_FILE), { recursive: true });
    writeFileSync(CONFIG_FILE, JSON.stringify(cfg, null, 2), "utf8");
    if (process.platform !== "win32") {
      try { chmodSync(CONFIG_FILE, 0o600); } catch (_) {}
    }
  } catch (err) {
    console.error(`[dsh-cloud-tunnel] Failed to save config:`, err.message);
  }
}

function isCgnatIp(ip) {
  const parts = ip.split(".").map(Number);
  return parts.length === 4 && parts[0] === 100 && parts[1] >= 64 && parts[1] <= 127;
}

function getLanIps() {
  const nets = networkInterfaces();
  const candidates = [];
  const virtualRe = /vmware|vmnet|virtualbox|vbox|hyper-v|vethernet|wsl|tailscale|zerotier|clash|mihomo|tun|tap|docker|loopback/i;
  const physicalRe = /wi-?fi|wlan|ethernet|以太网|en\d|eth\d/i;

  for (const ifName of Object.keys(nets)) {
    for (const net of nets[ifName]) {
      if (net.family !== "IPv4" || net.internal || net.address.startsWith("169.254")) {
        continue;
      }
      let score = 0;
      if (isCgnatIp(net.address)) score -= 100;
      if (virtualRe.test(ifName)) score -= 50;
      if (physicalRe.test(ifName)) score += 30;
      if (net.address.startsWith("192.168.") || net.address.startsWith("10.")) score += 10;
      else if (/^172\.(1[6-9]|2\d|3[01])\./.test(net.address)) score += 10;
      candidates.push({ address: net.address, score });
    }
  }
  candidates.sort((a, b) => b.score - a.score);
  const nonVirtual = candidates.filter(c => c.score > -40).map(c => c.address);
  if (nonVirtual.length > 0) return nonVirtual;
  return candidates.length > 0 ? candidates.map(c => c.address) : ["127.0.0.1"];
}

// Read DSH signing secret from .credentials.yaml
function loadDshSecret() {
  try {
    const credPath = join(homedir(), ".dsh", ".credentials.yaml");
    if (!existsSync(credPath)) return null;
    const yaml = readFileSync(credPath, "utf8");
    const match = yaml.match(/secret:\s*([A-Za-z0-9_-]+)/);
    if (!match) return null;
    const value = match[1];
    const padding = "=".repeat((4 - value.length % 4) % 4);
    return Buffer.from(value.replaceAll("-", "+").replaceAll("_", "/") + padding, "base64");
  } catch (e) {
    console.error("[dsh-cloud-tunnel] Failed to load DSH secret:", e.message);
    return null;
  }
}

let cachedSecret = loadDshSecret();

function mintDshCookie(port) {
  if (!cachedSecret) cachedSecret = loadDshSecret();
  if (!cachedSecret) return null;

  const authority = `127.0.0.1:${port}`;
  const COOKIE_PREFIX = "dsh-auth-";
  const cName = COOKIE_PREFIX + encodeBase64Url(createHash("sha256").update(authority).digest());

  const now = Date.now();
  const issuedAt = now - 1000;
  const expiresAt = issuedAt + DSH_COOKIE_TTL_MS;
  const payload = { version: 1, authority, issuedAt, expiresAt };
  const body = encodeBase64Url(Buffer.from(JSON.stringify(payload), "utf8"));
  const sig = createHmac("sha256", cachedSecret).update(body).digest();
  const cookieVal = `v1.${body}.${encodeBase64Url(sig)}`;

  return { name: cName, value: cookieVal, header: `${cName}=${cookieVal}` };
}

function parseCookies(cookieHeader) {
  const map = new Map();
  if (typeof cookieHeader !== "string" || !cookieHeader) return map;
  for (const part of cookieHeader.split(";")) {
    const idx = part.indexOf("=");
    if (idx <= 0) continue;
    const k = part.slice(0, idx).trim();
    const v = part.slice(idx + 1).trim();
    if (k) map.set(k, v);
  }
  return map;
}

function stripBridgeAndDshCookies(cookieHeader) {
  if (typeof cookieHeader !== "string" || !cookieHeader) return "";
  const kept = [];
  for (const part of cookieHeader.split(";")) {
    const trimmed = part.trim();
    if (!trimmed) continue;
    const idx = trimmed.indexOf("=");
    const k = (idx > 0 ? trimmed.slice(0, idx) : trimmed).trim().toLowerCase();
    if (k === BRIDGE_COOKIE_NAME || k.startsWith("dsh-auth-")) {
      continue;
    }
    kept.push(trimmed);
  }
  return kept.join("; ");
}

function deriveBridgeSessionKey(bridgeToken) {
  return createHmac("sha256", String(bridgeToken || "")).update("dsh-bridge-session-v1").digest();
}

function mintBridgeSessionCookieValue(bridgeToken) {
  const now = Date.now();
  const payload = { v: 1, iat: now, exp: now + BRIDGE_SESSION_TTL_MS };
  const body = encodeBase64Url(Buffer.from(JSON.stringify(payload), "utf8"));
  const sig = encodeBase64Url(createHmac("sha256", deriveBridgeSessionKey(bridgeToken)).update(body).digest());
  return `v1.${body}.${sig}`;
}

function verifyBridgeSessionCookieValue(rawVal, bridgeToken) {
  if (typeof rawVal !== "string" || typeof bridgeToken !== "string" || !bridgeToken) return false;
  const parts = rawVal.split(".");
  if (parts.length !== 3 || parts[0] !== "v1") return false;
  const [, body, sig] = parts;
  if (!body || !sig) return false;
  const expectedSig = encodeBase64Url(createHmac("sha256", deriveBridgeSessionKey(bridgeToken)).update(body).digest());
  if (!constantTimeEqualStr(sig, expectedSig)) return false;
  try {
    const payload = JSON.parse(decodeBase64Url(body).toString("utf8"));
    return Boolean(payload && payload.v === 1 && typeof payload.exp === "number" && Date.now() < payload.exp);
  } catch {
    return false;
  }
}

function isSecureForwardedRequest(req) {
  if (req.socket && req.socket.encrypted) return true;
  const xfp = String(req.headers["x-forwarded-proto"] || "").split(",")[0].trim().toLowerCase();
  return xfp === "https";
}

function buildBridgeSessionSetCookie(req, bridgeToken) {
  const val = mintBridgeSessionCookieValue(bridgeToken);
  const maxAge = Math.floor(BRIDGE_SESSION_TTL_MS / 1000);
  const secureFlag = isSecureForwardedRequest(req) ? "; Secure" : "";
  return `${BRIDGE_COOKIE_NAME}=${val}; Path=/; HttpOnly; SameSite=Lax; Max-Age=${maxAge}${secureFlag}`;
}

function parseHostHeader(rawHost) {
  if (typeof rawHost !== "string") return null;
  const trimmed = rawHost.trim();
  if (!trimmed || /[\s/\\?#@]/.test(trimmed)) return null;
  try {
    const u = new URL(`http://${trimmed}`);
    return {
      raw: trimmed.toLowerCase(),
      host: u.host.toLowerCase(),
      hostname: u.hostname.toLowerCase()
    };
  } catch {
    return null;
  }
}

function getAllLocalIpv4s() {
  const nets = networkInterfaces();
  const out = [];
  for (const ifName of Object.keys(nets)) {
    for (const net of nets[ifName]) {
      if (net.family === "IPv4" && !net.internal) {
        out.push(net.address);
      }
    }
  }
  return out;
}

function cleanupStaleAskpassFiles() {
  try {
    const dshDir = join(homedir(), ".dsh");
    if (!existsSync(dshDir)) return;
    for (const file of readdirSync(dshDir)) {
      if (/^askpass_.*\.(js|cmd|sh)$/i.test(file)) {
        try { unlinkSync(join(dshDir, file)); } catch (_) {}
      }
    }
  } catch (_) {}
}

function renderBridgeAuthPage() {
  return `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>DSH 安全验证</title>
<style>
  *{box-sizing:border-box}
  body{margin:0;min-height:100vh;background:#0b1120;color:#e2e8f0;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;display:flex;align-items:center;justify-content:center;padding:20px}
  .card{width:100%;max-width:420px;background:#111827;border:1px solid #334155;border-radius:16px;padding:24px;box-shadow:0 20px 45px rgba(0,0,0,.5)}
  h1{margin:0 0 8px;font-size:18px;color:#f8fafc}
  p{margin:0 0 18px;font-size:13px;line-height:1.5;color:#94a3b8}
  label{display:block;font-size:12px;color:#cbd5e1;margin-bottom:6px}
  input{width:100%;padding:10px 12px;border-radius:8px;border:1px solid #334155;background:#0f172a;color:#f8fafc;font-size:14px;outline:none}
  input:focus{border-color:#38bdf8}
  button{margin-top:14px;width:100%;padding:11px;border:none;border-radius:8px;background:#2563eb;color:#fff;font-size:14px;font-weight:600;cursor:pointer}
  button:disabled{opacity:.6;cursor:not-allowed}
  .err{margin-top:12px;font-size:12px;color:#fca5a5;min-height:18px}
</style>
</head>
<body>
  <form class="card" id="f">
    <h1>🔒 DSH 远程网桥验证</h1>
    <p>请输入 PC 端「云服务器远程穿透」面板中的 <strong>Bridge Token</strong>（或直接粘贴 <code>dsh-tunnel://</code> 配对码）以继续访问。</p>
    <label for="tk">访问令牌 (Bridge Token)</label>
    <input id="tk" type="password" autocomplete="off" placeholder="粘贴 Bridge Token 或 dsh-tunnel://..." required>
    <button id="btn" type="submit">验证并进入 DSH</button>
    <div class="err" id="err"></div>
  </form>
  <script>
    function extractToken(raw) {
      var s = (raw || "").trim();
      if (s.indexOf("dsh-tunnel://") === 0) {
        try {
          var u = new URL(s);
          var d = u.searchParams.get("d");
          if (d) {
            var b64 = d.replace(/-/g, "+").replace(/_/g, "/");
            while (b64.length % 4) b64 += "=";
            var obj = JSON.parse(decodeURIComponent(escape(atob(b64))));
            if (obj && obj.token) return String(obj.token).trim();
          }
        } catch (_) {}
      }
      return s;
    }
    document.getElementById("f").addEventListener("submit", function(e) {
      e.preventDefault();
      var btn = document.getElementById("btn");
      var err = document.getElementById("err");
      var token = extractToken(document.getElementById("tk").value);
      err.textContent = "";
      if (!token) { err.textContent = "请输入有效令牌"; return; }
      btn.disabled = true;
      fetch("/_dsh_bridge_auth", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ token: token })
      }).then(function(r) {
        if (r.ok) { window.location.reload(); return; }
        return r.json().catch(function(){return {};}).then(function(d) {
          throw new Error(d.error || ("HTTP " + r.status));
        });
      }).catch(function(ex) {
        btn.disabled = false;
        err.textContent = "验证失败：" + (ex.message || "Token 无效");
      });
    });
  </script>
</body>
</html>`;
}

export function apply(ctx) {
  cleanupStaleAskpassFiles();
  let config = loadConfig();
  let bridgeServer = null;
  let currentBridgePort = null;
  let currentListenHost = null;
  const authFailures = new Map();
  let activeSshProc = null;
  let sshGeneration = 0;
  let sshQueue = Promise.resolve();
  let sshStatus = "disconnected"; // "connected" | "connecting" | "disconnected" | "error"
  let lastError = null;
  let retryTimer = null;
  let connectTimer = null;
  let isDisposing = false;

  function enqueueSshOp(fn) {
    const next = sshQueue.then(fn, fn);
    sshQueue = next.catch(() => {});
    return next;
  }

  function clearTimers() {
    if (retryTimer) {
      clearTimeout(retryTimer);
      retryTimer = null;
    }
    if (connectTimer) {
      clearTimeout(connectTimer);
      connectTimer = null;
    }
  }

  function removePidFile() {
    try {
      if (existsSync(PID_FILE)) unlinkSync(PID_FILE);
    } catch (_) {}
  }

  function cleanupStalePidFile() {
    return new Promise((resolve) => {
      try {
        if (!existsSync(PID_FILE)) return resolve();
        const pidStr = readFileSync(PID_FILE, "utf8").trim();
        removePidFile();
        const pid = Number(pidStr);
        if (!Number.isInteger(pid) || pid <= 0) return resolve();
        if (process.platform === "win32") {
          execFile("tasklist", ["/FI", `PID eq ${pid}`, "/FO", "CSV", "/NH"], { windowsHide: true }, (err, stdout) => {
            if (!err && stdout && /ssh\.exe/i.test(stdout)) {
              execFile("taskkill", ["/F", "/T", "/PID", String(pid)], { windowsHide: true }, () => resolve());
            } else {
              resolve();
            }
          });
        } else {
          try { process.kill(pid, "SIGTERM"); } catch (_) {}
          resolve();
        }
      } catch (_) {
        resolve();
      }
    });
  }

  function killProcessGracefully(proc) {
    return new Promise((resolve) => {
      if (!proc || proc.exitCode !== null || proc.killed) {
        return resolve();
      }
      let settled = false;
      const done = () => {
        if (settled) return;
        settled = true;
        clearTimeout(killTimeout);
        resolve();
      };
      proc.once("close", done);
      proc.once("exit", done);

      const killTimeout = setTimeout(() => {
        if (process.platform === "win32" && proc.pid) {
          execFile("taskkill", ["/F", "/T", "/PID", String(proc.pid)], { windowsHide: true }, () => done());
        } else {
          try { proc.kill("SIGKILL"); } catch (_) {}
          done();
        }
      }, 800);

      try {
        proc.kill("SIGTERM");
      } catch (_) {
        done();
      }
    });
  }

  function stopSsh(updateStatus = true) {
    const myGen = ++sshGeneration;
    clearTimers();
    if (updateStatus) {
      sshStatus = "disconnected";
      lastError = null;
    }
    return enqueueSshOp(async () => {
      const proc = activeSshProc;
      activeSshProc = null;
      if (proc) {
        await killProcessGracefully(proc);
      }
      removePidFile();
      if (updateStatus && myGen === sshGeneration) {
        sshStatus = "disconnected";
        lastError = null;
      }
    });
  }

  function formatSshError(stderrText, code, remotePort) {
    const msg = (stderrText || "").trim();
    if (/remote port forwarding failed/i.test(msg)) {
      return `服务器远程端口 ${remotePort} 已被占用（可能是残留连接尚未释放），正在等待重试`;
    }
    if (/Permission denied/i.test(msg)) {
      return "SSH 认证失败：请检查用户名、私钥文件或密码";
    }
    if (/Could not resolve hostname/i.test(msg)) {
      return "无法解析服务器地址，请检查 IP 或域名";
    }
    if (/Connection refused/i.test(msg)) {
      return "服务器拒绝连接，请检查服务器 IP 与 SSH 端口";
    }
    if (/timed out/i.test(msg)) {
      return "连接服务器超时，请检查网络、服务器 IP 或安全组防火墙";
    }
    if (msg) {
      const firstLine = msg.split(/\r?\n/).find(l => l.trim() && !l.startsWith("Warning: Permanently added")) || msg;
      return firstLine.trim().slice(0, 220);
    }
    return `SSH 意外退出 (退出代码: ${code})`;
  }

  function startSsh() {
    const myGen = ++sshGeneration;
    clearTimers();
    return enqueueSshOp(async () => {
      const prevProc = activeSshProc;
      activeSshProc = null;
      if (prevProc) {
        await killProcessGracefully(prevProc);
      }
      await cleanupStalePidFile();

      if (isDisposing || myGen !== sshGeneration) return;

    if (!config.enabled || !config.serverHost || !config.serverHost.trim()) {
      sshStatus = "disconnected";
      lastError = null;
      return;
    }

    sshStatus = "connecting";
    lastError = null;

    const host = config.serverHost.trim();
    const port = String(Number(config.serverPort) || 22);
    const user = (config.serverUser || "root").trim();
    const remotePort = String(Number(config.remotePort) || 58001);
    const localPort = String(Number(config.localPort) || 58000);

    const sshArgs = [
      "-N",
      "-T",
      "-R", `127.0.0.1:${remotePort}:127.0.0.1:${localPort}`,
      "-p", port,
      "-o", "StrictHostKeyChecking=accept-new",
      "-o", "ServerAliveInterval=15",
      "-o", "ServerAliveCountMax=3",
      "-o", "ExitOnForwardFailure=yes",
      "-o", "ConnectTimeout=10"
    ];

    const tempFiles = [];
    const env = { ...process.env };

    if (config.authType === "key") {
      let keyPath = (config.keyPath || "").trim();
      if (keyPath.startsWith("~")) {
        keyPath = join(homedir(), keyPath.slice(1));
      }
      if (keyPath && existsSync(keyPath)) {
        sshArgs.push("-o", "BatchMode=yes", "-i", keyPath);
      } else {
        lastError = `私钥文件未找到: ${keyPath || "(未填写)"}`;
        sshStatus = "error";
        console.error(`[dsh-cloud-tunnel] ${lastError}`);
        return;
      }
    } else if (config.authType === "password") {
      if (!config.password) {
        lastError = "未填写 SSH 登录密码";
        sshStatus = "error";
        return;
      }
      const id = `${Date.now()}_${randomBytes(4).toString("hex")}`;
      const askpassJs = join(homedir(), ".dsh", `askpass_${id}.js`);
      // Pass secret via child-only environment variable so no plaintext password is written to disk
      env.DSH_SSH_ASKPASS_SECRET = config.password;
      writeFileSync(askpassJs, `process.stdout.write(process.env.DSH_SSH_ASKPASS_SECRET || "");`, "utf8");
      if (process.platform !== "win32") {
        try { chmodSync(askpassJs, 0o600); } catch (_) {}
      }
      tempFiles.push(askpassJs);

      if (process.platform === "win32") {
        const askpassCmd = join(homedir(), ".dsh", `askpass_${id}.cmd`);
        const cmdContent = `@echo off\r\nset ELECTRON_RUN_AS_NODE=1\r\n"${process.execPath}" "${askpassJs}"\r\n`;
        writeFileSync(askpassCmd, cmdContent, "utf8");
        tempFiles.push(askpassCmd);
        env.SSH_ASKPASS = askpassCmd;
      } else {
        const askpassSh = join(homedir(), ".dsh", `askpass_${id}.sh`);
        const shContent = `#!/bin/sh\nELECTRON_RUN_AS_NODE=1 exec "${process.execPath}" "${askpassJs}"\n`;
        writeFileSync(askpassSh, shContent, "utf8");
        chmodSync(askpassSh, 0o700);
        tempFiles.push(askpassSh);
        env.SSH_ASKPASS = askpassSh;
      }
      env.SSH_ASKPASS_REQUIRE = "force";
      env.DISPLAY = ":0";
      sshArgs.push("-o", "PubkeyAuthentication=no", "-o", "PreferredAuthentications=password,keyboard-interactive");
    }

    sshArgs.push(`${user}@${host}`);

    const cleanupTempFiles = () => {
      for (const f of tempFiles) {
        try {
          if (existsSync(f)) unlinkSync(f);
        } catch (_) {}
      }
    };

    try {
      const proc = spawn("ssh", sshArgs, {
        env,
        windowsHide: true,
        stdio: ["ignore", "pipe", "pipe"]
      });
      activeSshProc = proc;
      if (proc.pid) {
        try { writeFileSync(PID_FILE, String(proc.pid), "utf8"); } catch (_) {}
      }

      let stderrBuf = "";

      connectTimer = setTimeout(() => {
        if (proc !== activeSshProc || myGen !== sshGeneration || isDisposing) return;
        if (sshStatus === "connecting") {
          sshStatus = "connected";
          lastError = null;
          cleanupTempFiles();
          console.log(`[dsh-cloud-tunnel] SSH tunnel established -> ${user}@${host}:${remotePort}`);
        }
      }, 3500);

      proc.stderr.on("data", (data) => {
        if (proc !== activeSshProc || myGen !== sshGeneration) return;
        const msg = data.toString();
        stderrBuf += msg;
        console.warn(`[dsh-cloud-tunnel] SSH stderr: ${msg}`);
      });

      proc.on("close", (code) => {
        cleanupTempFiles();
        if (proc !== activeSshProc || myGen !== sshGeneration) {
          return;
        }
        clearTimers();
        activeSshProc = null;
        removePidFile();

        if (isDisposing) return;

        if (!config.enabled) {
          sshStatus = "disconnected";
          lastError = null;
          return;
        }

        lastError = formatSshError(stderrBuf, code, remotePort);
        sshStatus = "error";
        console.log(`[dsh-cloud-tunnel] SSH tunnel exited with code ${code}: ${lastError}`);

        retryTimer = setTimeout(() => {
          if (isDisposing || !config.enabled || myGen !== sshGeneration) return;
          console.log(`[dsh-cloud-tunnel] Reconnecting SSH tunnel...`);
          startSsh();
        }, 5000);
      });

      proc.on("error", (err) => {
        cleanupTempFiles();
        if (proc !== activeSshProc || myGen !== sshGeneration) return;
        clearTimers();
        activeSshProc = null;
        removePidFile();
        lastError = `无法启动 SSH: ${err.message}`;
        sshStatus = "error";
        console.error(`[dsh-cloud-tunnel] ${lastError}`);
      });
      } catch (e) {
        cleanupTempFiles();
        lastError = e.message;
        sshStatus = "error";
        console.error(`[dsh-cloud-tunnel] Failed to spawn SSH:`, e.message);
      }
    });
  }

  function getAllowedHosts(localPort) {
    const hosts = new Set([
      `127.0.0.1:${localPort}`,
      `localhost:${localPort}`,
      `[::1]:${localPort}`
    ]);
    let publicPort = null;
    try {
      const rawUrl = String(config.publicUrl || "").trim();
      if (rawUrl) {
        const u = new URL(rawUrl.includes("://") ? rawUrl : `https://${rawUrl}`);
        if (u.host) hosts.add(u.host.toLowerCase());
        if (u.hostname) hosts.add(u.hostname.toLowerCase());
        if (u.port) publicPort = u.port;
      }
    } catch (_) {}
    const srvHost = String(config.serverHost || "").trim().toLowerCase();
    if (srvHost && !/[\s/\\?#@]/.test(srvHost)) {
      hosts.add(srvHost);
      if (publicPort) hosts.add(`${srvHost}:${publicPort}`);
    }
    if (config.lanDirectEnabled) {
      for (const ip of getAllLocalIpv4s()) {
        hosts.add(`${ip}:${localPort}`);
      }
    }
    return hosts;
  }

  function validateBridgeSecurity(req, localPort, isUpgrade) {
    if (typeof req.url !== "string" || !req.url.startsWith("/") || /[\r\n]/.test(req.url)) {
      return { ok: false, status: 400, reason: "Invalid request target" };
    }
    const parsedHost = parseHostHeader(req.headers.host);
    const allowedHosts = getAllowedHosts(localPort);
    if (!parsedHost || (!allowedHosts.has(parsedHost.raw) && !allowedHosts.has(parsedHost.host))) {
      return { ok: false, status: 403, reason: "Host header not allowed" };
    }

    const sfs = typeof req.headers["sec-fetch-site"] === "string"
      ? req.headers["sec-fetch-site"].trim().toLowerCase()
      : "";
    if (sfs === "cross-site") {
      return { ok: false, status: 403, reason: "Cross-site request blocked" };
    }

    if (req.headers.origin !== undefined) {
      const rawOrigin = String(req.headers.origin).trim();
      if (!rawOrigin || rawOrigin.toLowerCase() === "null") {
        return { ok: false, status: 403, reason: "Null origin blocked" };
      }
      let originUrl;
      try {
        originUrl = new URL(rawOrigin);
      } catch {
        return { ok: false, status: 403, reason: "Malformed Origin header" };
      }
      if (originUrl.protocol !== "http:" && originUrl.protocol !== "https:") {
        return { ok: false, status: 403, reason: "Unsupported Origin scheme" };
      }
      const oHost = originUrl.host.toLowerCase();
      const oHostname = originUrl.hostname.toLowerCase();
      if (oHostname !== parsedHost.hostname || (!allowedHosts.has(oHost) && !allowedHosts.has(oHostname))) {
        return { ok: false, status: 403, reason: "Origin mismatch" };
      }
    } else if (isUpgrade || (req.method !== "GET" && req.method !== "HEAD")) {
      if (req.headers.referer !== undefined) {
        try {
          const refUrl = new URL(String(req.headers.referer));
          const rHost = refUrl.host.toLowerCase();
          const rHostname = refUrl.hostname.toLowerCase();
          if (rHostname !== parsedHost.hostname || (!allowedHosts.has(rHost) && !allowedHosts.has(rHostname))) {
            return { ok: false, status: 403, reason: "Referer mismatch" };
          }
        } catch {
          return { ok: false, status: 403, reason: "Malformed Referer header" };
        }
      }
    }

    return { ok: true };
  }

  function getClientKey(req) {
    return (req.socket && req.socket.remoteAddress) ? String(req.socket.remoteAddress) : "unknown";
  }

  function isRateLimited(clientKey) {
    const now = Date.now();
    const rec = authFailures.get(clientKey);
    if (!rec) return false;
    if (now - rec.windowStart > 60000) {
      authFailures.delete(clientKey);
      return false;
    }
    return rec.count >= 20;
  }

  function recordAuthFailure(clientKey) {
    const now = Date.now();
    const rec = authFailures.get(clientKey);
    if (!rec || now - rec.windowStart > 60000) {
      authFailures.set(clientKey, { count: 1, windowStart: now });
    } else {
      rec.count += 1;
    }
  }

  function clearAuthFailures(clientKey) {
    authFailures.delete(clientKey);
  }

  function extractBridgeTokenFromReq(req) {
    const hdrToken = req.headers["x-dsh-bridge-token"];
    if (typeof hdrToken === "string" && hdrToken.trim()) {
      return { token: hdrToken.trim(), source: "x-header" };
    }
    const authHdr = req.headers.authorization;
    if (typeof authHdr === "string") {
      const m = authHdr.match(/^Bearer\s+(.+)$/i);
      if (m && m[1] && m[1].trim()) {
        return { token: m[1].trim(), source: "bearer" };
      }
    }
    return { token: "", source: null };
  }

  function authenticateRequest(req) {
    const cookies = parseCookies(req.headers.cookie || "");
    const sessionCookie = cookies.get(BRIDGE_COOKIE_NAME);
    const hasValidSession = Boolean(sessionCookie && verifyBridgeSessionCookieValue(sessionCookie, config.bridgeToken));

    const { token, source } = extractBridgeTokenFromReq(req);
    const hasHeaderToken = Boolean(token);
    const headerTokenValid = hasHeaderToken && constantTimeEqualStr(token, config.bridgeToken);

    if (headerTokenValid) {
      return { authenticated: true, via: "token", tokenSource: source, hasValidSession };
    }
    if (hasValidSession) {
      return { authenticated: true, via: "session", tokenSource: null, hasValidSession: true };
    }
    return { authenticated: false, badHeaderToken: hasHeaderToken };
  }

  function closeBridgeServer() {
    return new Promise((resolve) => {
      if (!bridgeServer) return resolve();
      const srv = bridgeServer;
      bridgeServer = null;
      currentBridgePort = null;
      currentListenHost = null;
      if (!srv.listening) return resolve();
      let done = false;
      const finish = () => {
        if (done) return;
        done = true;
        clearTimeout(t);
        resolve();
      };
      const t = setTimeout(finish, 1000);
      try {
        if (typeof srv.closeAllConnections === "function") {
          srv.closeAllConnections();
        }
        srv.close(() => finish());
      } catch (_) {
        finish();
      }
    });
  }

  async function ensureBridge(targetDshPort) {
    const localPort = Number(config.localPort) || 58000;
    const listenHost = config.lanDirectEnabled ? "0.0.0.0" : "127.0.0.1";
    if (bridgeServer && bridgeServer.listening && currentBridgePort === localPort && currentListenHost === listenHost) {
      return;
    }
    await closeBridgeServer();

    bridgeServer = createServer((req, res) => {
      res.setHeader("X-Content-Type-Options", "nosniff");

      const sec = validateBridgeSecurity(req, localPort, false);
      if (!sec.ok) {
        res.writeHead(sec.status, {
          "Content-Type": "application/json; charset=utf-8",
          "Cache-Control": "no-store"
        });
        res.end(JSON.stringify({ error: sec.reason }));
        return;
      }

      const reqPath = (req.url || "/").split("?")[0];
      const clientKey = getClientKey(req);

      if (reqPath === "/_dsh_bridge_auth") {
        if (req.method !== "POST") {
          res.writeHead(405, { "Content-Type": "application/json", "Cache-Control": "no-store" });
          res.end(JSON.stringify({ ok: false, error: "Method not allowed" }));
          return;
        }
        if (isRateLimited(clientKey)) {
          res.writeHead(429, { "Content-Type": "application/json", "Cache-Control": "no-store" });
          res.end(JSON.stringify({ ok: false, error: "尝试次数过多，请稍后再试" }));
          return;
        }
        let body = "";
        let tooLarge = false;
        req.on("data", (chunk) => {
          body += chunk;
          if (body.length > 4096) {
            tooLarge = true;
            req.destroy();
          }
        });
        req.on("end", () => {
          if (tooLarge) {
            if (!res.headersSent) {
              res.writeHead(413, { "Content-Type": "application/json", "Cache-Control": "no-store" });
              res.end(JSON.stringify({ ok: false, error: "Payload too large" }));
            }
            return;
          }
          let candidate = extractBridgeTokenFromReq(req).token;
          if (!candidate && body) {
            try {
              const parsed = JSON.parse(body);
              if (parsed && typeof parsed.token === "string") {
                candidate = parsed.token.trim();
              }
            } catch (_) {}
          }
          if (!candidate || !constantTimeEqualStr(candidate, config.bridgeToken)) {
            recordAuthFailure(clientKey);
            res.writeHead(401, { "Content-Type": "application/json", "Cache-Control": "no-store" });
            res.end(JSON.stringify({ ok: false, error: "Bridge Token 无效" }));
            return;
          }
          clearAuthFailures(clientKey);
          res.writeHead(200, {
            "Content-Type": "application/json; charset=utf-8",
            "Cache-Control": "no-store",
            "Set-Cookie": buildBridgeSessionSetCookie(req, config.bridgeToken)
          });
          res.end(JSON.stringify({ ok: true }));
        });
        return;
      }

      if (reqPath === "/_dsh_bridge_logout" && req.method === "POST") {
        const secureFlag = isSecureForwardedRequest(req) ? "; Secure" : "";
        res.writeHead(200, {
          "Content-Type": "application/json",
          "Cache-Control": "no-store",
          "Set-Cookie": `${BRIDGE_COOKIE_NAME}=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0${secureFlag}`
        });
        res.end(JSON.stringify({ ok: true }));
        return;
      }

      if (isRateLimited(clientKey)) {
        res.writeHead(429, { "Content-Type": "application/json", "Cache-Control": "no-store" });
        res.end(JSON.stringify({ error: "Too many failed auth attempts" }));
        return;
      }

      const auth = authenticateRequest(req);
      if (!auth.authenticated) {
        if (auth.badHeaderToken) {
          recordAuthFailure(clientKey);
        }
        if (reqPath === "/_dsh_ping") {
          res.writeHead(401, {
            "Content-Type": "application/json; charset=utf-8",
            "Cache-Control": "no-store"
          });
          res.end(JSON.stringify({
            status: "unauthorized",
            plugin: "dsh-cloud-tunnel",
            authRequired: true
          }));
          return;
        }
        const accept = String(req.headers.accept || "");
        if (req.method === "GET" && accept.includes("text/html") && !reqPath.startsWith("/api/")) {
          res.writeHead(401, {
            "Content-Type": "text/html; charset=utf-8",
            "Cache-Control": "no-store",
            "X-Frame-Options": "DENY"
          });
          res.end(renderBridgeAuthPage());
          return;
        }
        res.writeHead(401, {
          "Content-Type": "application/json; charset=utf-8",
          "Cache-Control": "no-store"
        });
        res.end(JSON.stringify({ error: "Unauthorized: valid Bridge Token or session required" }));
        return;
      }

      clearAuthFailures(clientKey);

      if (reqPath === "/_dsh_ping") {
        const headers = {
          "Content-Type": "application/json; charset=utf-8",
          "Cache-Control": "no-store"
        };
        if (auth.via === "token" && !auth.hasValidSession) {
          headers["Set-Cookie"] = buildBridgeSessionSetCookie(req, config.bridgeToken);
        }
        res.writeHead(200, headers);
        res.end(JSON.stringify({
          status: "ok",
          plugin: "dsh-cloud-tunnel",
          auth: true,
          hostName: hostname(),
          localPort,
          lanIps: config.lanDirectEnabled ? getLanIps() : [],
          publicUrl: config.publicUrl || "",
          time: Date.now()
        }));
        return;
      }

      const localOrigin = `http://127.0.0.1:${targetDshPort}`;
      const clientReqHeaders = { ...req.headers };
      delete clientReqHeaders["x-dsh-bridge-token"];
      if (auth.tokenSource === "bearer") {
        delete clientReqHeaders.authorization;
      }
      clientReqHeaders.host = `127.0.0.1:${targetDshPort}`;
      if (clientReqHeaders.origin !== undefined) {
        clientReqHeaders.origin = localOrigin;
      }
      if (clientReqHeaders.referer !== undefined) {
        try {
          const refUrl = new URL(clientReqHeaders.referer);
          clientReqHeaders.referer = `${localOrigin}${refUrl.pathname}${refUrl.search}`;
        } catch {
          clientReqHeaders.referer = `${localOrigin}/`;
        }
      }
      if (typeof clientReqHeaders["sec-fetch-site"] === "string") {
        clientReqHeaders["sec-fetch-site"] = "same-origin";
      }

      // Inject DSH auth cookie only after bridge authentication succeeds
      const cookieInfo = mintDshCookie(targetDshPort);
      const sanitizedCookie = stripBridgeAndDshCookies(clientReqHeaders.cookie || "");
      if (cookieInfo) {
        clientReqHeaders.cookie = sanitizedCookie ? `${sanitizedCookie}; ${cookieInfo.header}` : cookieInfo.header;
      } else if (sanitizedCookie) {
        clientReqHeaders.cookie = sanitizedCookie;
      } else {
        delete clientReqHeaders.cookie;
      }

      const proxyReq = httpRequest({
        hostname: "127.0.0.1",
        port: targetDshPort,
        path: req.url,
        method: req.method,
        headers: clientReqHeaders,
      }, (proxyRes) => {
        const resHeaders = { ...proxyRes.headers };
        if (auth.via === "token" && !auth.hasValidSession) {
          const cookieStr = buildBridgeSessionSetCookie(req, config.bridgeToken);
          const existing = resHeaders["set-cookie"];
          if (Array.isArray(existing)) {
            resHeaders["set-cookie"] = [...existing, cookieStr];
          } else if (typeof existing === "string") {
            resHeaders["set-cookie"] = [existing, cookieStr];
          } else {
            resHeaders["set-cookie"] = cookieStr;
          }
        }
        res.writeHead(proxyRes.statusCode, resHeaders);
        proxyRes.pipe(res);
      });

      proxyReq.on("error", (err) => {
        if (!res.headersSent) {
          res.writeHead(502, { "Content-Type": "text/plain; charset=utf-8" });
          res.end(`无法连接到本地 DSH 核心服务 (${err.message})`);
        }
      });

      req.on("error", () => {
        proxyReq.destroy();
      });

      req.pipe(proxyReq);
    });

    // WebSocket / Upgrade proxy (authenticated & origin-checked)
    bridgeServer.on("upgrade", (req, clientSocket, head) => {
      const sec = validateBridgeSecurity(req, localPort, true);
      if (!sec.ok) {
        try {
          clientSocket.write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n");
        } catch (_) {}
        clientSocket.destroy();
        return;
      }

      const clientKey = getClientKey(req);
      if (isRateLimited(clientKey)) {
        try {
          clientSocket.write("HTTP/1.1 429 Too Many Requests\r\nConnection: close\r\n\r\n");
        } catch (_) {}
        clientSocket.destroy();
        return;
      }

      const auth = authenticateRequest(req);
      if (!auth.authenticated) {
        if (auth.badHeaderToken) {
          recordAuthFailure(clientKey);
        }
        try {
          clientSocket.write("HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n");
        } catch (_) {}
        clientSocket.destroy();
        return;
      }

      clearAuthFailures(clientKey);
      const cookieInfo = mintDshCookie(targetDshPort);
      const localOrigin = `http://127.0.0.1:${targetDshPort}`;

      const proxySocket = netConnect(targetDshPort, "127.0.0.1", () => {
        proxySocket.write(`${req.method} ${req.url} HTTP/${req.httpVersion}\r\n`);
        let cookieWritten = false;
        for (let i = 0; i < req.rawHeaders.length; i += 2) {
          const key = req.rawHeaders[i];
          const val = req.rawHeaders[i + 1];
          const lk = key.toLowerCase();
          if (lk === "x-dsh-bridge-token") {
            continue;
          } else if (lk === "authorization" && auth.tokenSource === "bearer") {
            continue;
          } else if (lk === "host") {
            proxySocket.write(`Host: 127.0.0.1:${targetDshPort}\r\n`);
          } else if (lk === "origin") {
            proxySocket.write(`Origin: ${localOrigin}\r\n`);
          } else if (lk === "referer") {
            let rewrittenRef = `${localOrigin}/`;
            try {
              const refUrl = new URL(val);
              rewrittenRef = `${localOrigin}${refUrl.pathname}${refUrl.search}`;
            } catch {}
            proxySocket.write(`Referer: ${rewrittenRef}\r\n`);
          } else if (lk === "sec-fetch-site") {
            proxySocket.write(`Sec-Fetch-Site: same-origin\r\n`);
          } else if (lk === "cookie") {
            cookieWritten = true;
            const cleaned = stripBridgeAndDshCookies(val);
            if (cookieInfo) {
              const combined = cleaned ? `${cleaned}; ${cookieInfo.header}` : cookieInfo.header;
              proxySocket.write(`Cookie: ${combined}\r\n`);
            } else if (cleaned) {
              proxySocket.write(`Cookie: ${cleaned}\r\n`);
            }
          } else {
            proxySocket.write(`${key}: ${val}\r\n`);
          }
        }
        if (!cookieWritten && cookieInfo) {
          proxySocket.write(`Cookie: ${cookieInfo.header}\r\n`);
        }
        proxySocket.write("\r\n");
        if (head && head.length > 0) {
          proxySocket.write(head);
        }
        clientSocket.pipe(proxySocket);
        proxySocket.pipe(clientSocket);
      });

      proxySocket.on("error", () => clientSocket.destroy());
      clientSocket.on("error", () => proxySocket.destroy());
    });

    await new Promise((resolve) => {
      let settled = false;
      const finish = () => {
        if (settled) return;
        settled = true;
        resolve();
      };
      const srv = bridgeServer;
      const onListenError = (err) => {
        console.error(`[dsh-cloud-tunnel] Bridge server error on ${listenHost}:${localPort}:`, err.message);
        lastError = `本地网桥端口 ${listenHost}:${localPort} 启动失败: ${err.message}`;
        currentBridgePort = null;
        currentListenHost = null;
        if (bridgeServer === srv) bridgeServer = null;
        try { srv.close(); } catch (_) {}
        finish();
      };
      srv.once("error", onListenError);
      srv.listen(localPort, listenHost, () => {
        srv.off("error", onListenError);
        srv.on("error", (err) => {
          console.error(`[dsh-cloud-tunnel] Bridge runtime error:`, err.message);
        });
        currentBridgePort = localPort;
        currentListenHost = listenHost;
        console.log(`[dsh-cloud-tunnel] Local bridge active on ${listenHost}:${localPort} -> 127.0.0.1:${targetDshPort}`);
        finish();
      });
    });
  }

  // Register API routes into DSH internal Web Server
  ctx.inject(["webServer"], (webCtx) => {
    const dshPort = webCtx.webServer.port;
    console.log(`[dsh-cloud-tunnel] DSH internal webServer port detected: ${dshPort}`);

    const routeDisposers = [];
    webCtx.effect(() => () => {
      while (routeDisposers.length) {
        const dispose = routeDisposers.pop();
        try { dispose(); } catch {}
      }
    }, "dsh-cloud-tunnel: routes");

    const registerRoute = (route) => {
      routeDisposers.push(webCtx.webServer.register(route));
    };

    // API: GET Status
    registerRoute({
      kind: "exact",
      path: "/api/cloud-tunnel/status",
      handler: (req, res) => {
        res.writeHead(200, { "Content-Type": "application/json", "Cache-Control": "no-store" });
        res.end(JSON.stringify({
          status: sshStatus,
          lastError,
          hostName: hostname(),
          lanIps: getLanIps(),
          dshPort,
          listenHost: currentListenHost || (config.lanDirectEnabled ? "0.0.0.0" : "127.0.0.1"),
          bridgeToken: config.bridgeToken,
          config: {
            enabled: config.enabled,
            serverHost: config.serverHost,
            serverPort: config.serverPort,
            serverUser: config.serverUser,
            authType: config.authType,
            keyPath: config.keyPath,
            hasPassword: Boolean(config.password),
            remotePort: config.remotePort,
            localPort: config.localPort,
            publicUrl: config.publicUrl,
            autoStart: config.autoStart,
            lanDirectEnabled: Boolean(config.lanDirectEnabled)
          }
        }));
      }
    });

    // API: POST Save Config
    registerRoute({
      kind: "exact",
      path: "/api/cloud-tunnel/config",
      handler: (req, res) => {
        if (req.method !== "POST") {
          res.writeHead(405).end();
          return;
        }
        let body = "";
        let tooLarge = false;
        req.on("data", (chunk) => {
          body += chunk;
          if (body.length > 65536) {
            tooLarge = true;
            req.destroy();
          }
        });
        req.on("end", async () => {
          if (tooLarge) {
            if (!res.headersSent) res.writeHead(413).end();
            return;
          }
          try {
            const updated = JSON.parse(body || "{}");
            const parsePort = (v, fallback) => {
              const n = Number(v);
              return Number.isInteger(n) && n >= 1 && n <= 65535 ? n : fallback;
            };
            const nextConfig = {
              ...config,
              ...updated,
              serverPort: parsePort(updated.serverPort, config.serverPort || 22),
              remotePort: parsePort(updated.remotePort, config.remotePort || 58001),
              localPort: parsePort(updated.localPort, config.localPort || 58000),
              lanDirectEnabled: updated.lanDirectEnabled !== undefined
                ? Boolean(updated.lanDirectEnabled)
                : Boolean(config.lanDirectEnabled),
              bridgeToken: config.bridgeToken
            };
            if (nextConfig.authType === "password" && !updated.password && config.password) {
              nextConfig.password = config.password;
            }
            if (nextConfig.authType === "key" && updated.clearPassword) {
              nextConfig.password = "";
            }
            delete nextConfig.hasPassword;
            delete nextConfig.clearPassword;
            config = nextConfig;
            saveConfig(config);
            await ensureBridge(dshPort);
            if (config.enabled) {
              startSsh();
            } else {
              await stopSsh(true);
            }
            res.writeHead(200, { "Content-Type": "application/json" });
            res.end(JSON.stringify({ success: true, status: sshStatus }));
          } catch (e) {
            res.writeHead(400, { "Content-Type": "application/json" });
            res.end(JSON.stringify({ error: e.message }));
          }
        });
      }
    });

    // API: POST Rotate Bridge Token
    registerRoute({
      kind: "exact",
      path: "/api/cloud-tunnel/rotate-token",
      handler: (req, res) => {
        if (req.method !== "POST") {
          res.writeHead(405).end();
          return;
        }
        config.bridgeToken = generateBridgeToken();
        saveConfig(config);
        res.writeHead(200, { "Content-Type": "application/json", "Cache-Control": "no-store" });
        res.end(JSON.stringify({ success: true, bridgeToken: config.bridgeToken }));
      }
    });

    // API: POST Reconnect
    registerRoute({
      kind: "exact",
      path: "/api/cloud-tunnel/reconnect",
      handler: (req, res) => {
        if (req.method !== "POST") {
          res.writeHead(405).end();
          return;
        }
        config.enabled = true;
        saveConfig(config);
        ensureBridge(dshPort).then(() => startSsh());
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ success: true, status: "connecting" }));
      }
    });

    // API: POST Toggle
    registerRoute({
      kind: "exact",
      path: "/api/cloud-tunnel/toggle",
      handler: (req, res) => {
        if (req.method !== "POST") {
          res.writeHead(405).end();
          return;
        }
        let body = "";
        let tooLarge = false;
        req.on("data", (chunk) => {
          body += chunk;
          if (body.length > 4096) {
            tooLarge = true;
            req.destroy();
          }
        });
        req.on("end", async () => {
          if (tooLarge) {
            if (!res.headersSent) res.writeHead(413).end();
            return;
          }
          try {
            const updated = JSON.parse(body || "{}");
            if (typeof updated.enabled !== "boolean") {
              res.writeHead(400, { "Content-Type": "application/json" });
              res.end(JSON.stringify({ error: "enabled must be a boolean" }));
              return;
            }
            // Execute the target shown to the user instead of blindly flipping a stale config value.
            config.enabled = updated.enabled;
            saveConfig(config);
            if (config.enabled) {
              await startSsh();
            } else {
              await stopSsh(true);
            }
            res.writeHead(200, { "Content-Type": "application/json" });
            res.end(JSON.stringify({ enabled: config.enabled, status: sshStatus }));
          } catch (e) {
            res.writeHead(400, { "Content-Type": "application/json" });
            res.end(JSON.stringify({ error: e.message }));
          }
        });
      }
    });

    // API: GET Generator Script
    registerRoute({
      kind: "exact",
      path: "/api/cloud-tunnel/server-script",
      handler: (req, res) => {
        let domain = "yourdomain.example.com";
        let publicPort = 8443;
        try {
          const rawUrl = (config.publicUrl || "").trim();
          if (rawUrl) {
            const parsedUrl = new URL(rawUrl.includes("://") ? rawUrl : `https://${rawUrl}`);
            if (parsedUrl.hostname && /^[a-zA-Z0-9.-]+$/.test(parsedUrl.hostname)) {
              domain = parsedUrl.hostname;
            }
            if (parsedUrl.port) {
              const p = Number(parsedUrl.port);
              if (Number.isInteger(p) && p >= 1 && p <= 65535) publicPort = p;
            } else if (parsedUrl.protocol === "https:") {
              publicPort = 443;
            }
          }
        } catch (_) {}
        const rawRemotePort = Number(config.remotePort);
        const remotePort = Number.isInteger(rawRemotePort) && rawRemotePort >= 1 && rawRemotePort <= 65535 ? rawRemotePort : 58001;

        const script = `#!/bin/bash
# ==============================================================
# DSH 云服务器一键配置脚本 (Nginx + mTLS 双向认证)
# 适用于: Ubuntu / Debian / Alibaba Cloud Linux
# 可选环境变量:
#   SSL_CERT=/path/to/fullchain.pem SSL_KEY=/path/to/privkey.pem
#   P12_PASS=自定义客户端证书导入密码
# ==============================================================
set -e

echo "=== 1. 安装 Nginx 与 OpenSSL (如果尚未安装) ==="
if ! command -v nginx >/dev/null 2>&1; then
    apt update && apt install -y nginx
fi
if ! command -v openssl >/dev/null 2>&1; then
    apt update && apt install -y openssl
fi

echo "=== 2. 创建 mTLS 专用证书目录 ==="
mkdir -p /etc/nginx/dsh_mtls
cd /etc/nginx/dsh_mtls

echo "=== 3. 生成内部私有 CA 根证书 ==="
if [ ! -f "ca.key" ]; then
    openssl genrsa -out ca.key 2048
    openssl req -new -x509 -days 3650 -key ca.key -out ca.crt -subj "/CN=DSH-Internal-CA/O=DSH/C=CN"
fi

echo "=== 4. 签发客户端专属证书 ==="
P12_PASS="\${P12_PASS:-\$(openssl rand -base64 12 | tr -dc 'A-Za-z0-9' | head -c 12)}"
openssl genrsa -out client.key 2048
openssl req -new -key client.key -out client.csr -subj "/CN=Mobile-Client/O=DSH/C=CN"
openssl x509 -req -days 3650 -in client.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out client.crt

# 打包为手机可直接导入的 .p12 格式
openssl pkcs12 -export -clcerts -in client.crt -inkey client.key -out dsh-client.p12 -passout "pass:\${P12_PASS}"

if [ -n "\${SSL_CERT:-}" ] && [ -n "\${SSL_KEY:-}" ]; then
    SERVER_CERT="\$SSL_CERT"
    SERVER_KEY="\$SSL_KEY"
else
    if [ ! -f "/etc/nginx/dsh_mtls/server.crt" ] || [ ! -f "/etc/nginx/dsh_mtls/server.key" ]; then
        echo "=== 未指定域名 SSL 证书，生成临时自签名服务端证书 (手机 App 支持一键信任指纹) ==="
        openssl req -x509 -nodes -days 3650 -newkey rsa:2048 \\
            -keyout /etc/nginx/dsh_mtls/server.key \\
            -out /etc/nginx/dsh_mtls/server.crt \\
            -subj "/CN=${domain}/O=DSH/C=CN"
    fi
    SERVER_CERT="/etc/nginx/dsh_mtls/server.crt"
    SERVER_KEY="/etc/nginx/dsh_mtls/server.key"
fi

echo "=== 5. 配置 Nginx ${publicPort} 端口 ==="
mkdir -p /etc/nginx/conf.d
cat << NGINX_EOF > /etc/nginx/conf.d/dsh_${publicPort}.conf
server {
    listen ${publicPort} ssl;
    http2 on;
    server_name ${domain};

    # 如需更换为域名正式 SSL 证书，请修改以下两行路径
    ssl_certificate     \${SERVER_CERT};
    ssl_certificate_key \${SERVER_KEY};

    ssl_client_certificate /etc/nginx/dsh_mtls/ca.crt;
    ssl_verify_client on;

    gzip on;
    gzip_vary on;
    gzip_min_length 1024;
    gzip_proxied any;
    gzip_types text/plain text/css application/javascript application/json image/svg+xml;

    location / {
        proxy_pass http://127.0.0.1:${remotePort};
        proxy_http_version 1.1;
        proxy_set_header Upgrade \\$http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host \\$http_host;
        proxy_set_header X-Real-IP \\$remote_addr;
        proxy_set_header X-Forwarded-For \\$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \\$scheme;
        proxy_read_timeout 86400s;
        proxy_send_timeout 86400s;
    }
}
NGINX_EOF

if ! grep -q "include /etc/nginx/conf.d/\\*.conf;" /etc/nginx/nginx.conf; then
    awk 'BEGIN{done=0} {print} !done && /^[[:space:]]*http[[:space:]]*\\{/{print "    include /etc/nginx/conf.d/*.conf;"; done=1}' /etc/nginx/nginx.conf > /tmp/nginx.conf.dsh && mv /tmp/nginx.conf.dsh /etc/nginx/nginx.conf
fi

if command -v ufw >/dev/null 2>&1; then
    ufw allow ${publicPort}/tcp comment "DSH mTLS" 2>/dev/null || true
fi

# 让 SSH 服务端更快回收断网残留隧道，避免重连时端口占用
mkdir -p /etc/ssh/sshd_config.d
cat << 'SSH_EOF' > /etc/ssh/sshd_config.d/dsh_tunnel.conf
ClientAliveInterval 15
ClientAliveCountMax 3
SSH_EOF
systemctl reload ssh 2>/dev/null || systemctl reload sshd 2>/dev/null || true

nginx -t && systemctl reload nginx

echo "=========================================================="
echo "   Nginx 与 mTLS 配置完成！"
echo "   监听端口: ${publicPort} (请确保云安全组和本机防火墙 nftables/ufw 已放行)"
echo "   客户端证书位置: /etc/nginx/dsh_mtls/dsh-client.p12"
echo "   本次随机生成证书密码: \${P12_PASS}"
echo "=========================================================="
`;
        res.writeHead(200, { "Content-Type": "text/plain; charset=utf-8" });
        res.end(script);
      }
    });

    ensureBridge(dshPort).then(() => {
      if (!isDisposing && config.autoStart && config.enabled) {
        startSsh();
      }
    });
  });

  // Cleanup on DSH shutdown or plugin unload
  ctx.effect(() => async () => {
    isDisposing = true;
    await Promise.all([stopSsh(false), closeBridgeServer()]);
    cleanupStaleAskpassFiles();
    console.log(`[dsh-cloud-tunnel] Disposed cleanly.`);
  }, "dsh-cloud-tunnel: lifecycle");
}
