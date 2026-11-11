window.__ModuleLoader__.load({
  id: "dsh-cloud-tunnel",
  factory: (require) => {
    var module = { exports: {} };
    var exports = module.exports;

    var React = require("react");
    var inject = ["slots"];

    function CloudTunnelModal(props) {
      var onClose = props.onClose;
      var [loading, setLoading] = React.useState(true);
      var [data, setData] = React.useState(null);
      var [form, setForm] = React.useState({});
      var initializedRef = React.useRef(false);
      var [copied, setCopied] = React.useState("");
      var [showScript, setShowScript] = React.useState(false);
      var [serverScript, setServerScript] = React.useState("");
      var [saving, setSaving] = React.useState(false);
      var [showToken, setShowToken] = React.useState(false);
      var [rotating, setRotating] = React.useState(false);

      function fetchStatus(forceSyncForm) {
        fetch("/api/cloud-tunnel/status")
          .then(r => r.json())
          .then(d => {
            setData(d);
            if (!initializedRef.current || forceSyncForm) {
              setForm(Object.assign({}, d.config || {}, { password: "" }));
              initializedRef.current = true;
            }
            setLoading(false);
          })
          .catch(() => setLoading(false));
      }

      React.useEffect(() => {
        fetchStatus(false);
        var timer = setInterval(() => fetchStatus(false), 2000);
        return () => clearInterval(timer);
      }, []);

      function handleSave(overrideFields) {
        setSaving(true);
        var merged = Object.assign({}, form, overrideFields || {});
        var payload = Object.assign({}, merged, {
          serverPort: Number(merged.serverPort) || 22,
          remotePort: Number(merged.remotePort) || 58001,
          localPort: Number(merged.localPort) || 58000,
          lanDirectEnabled: Boolean(merged.lanDirectEnabled)
        });
        fetch("/api/cloud-tunnel/config", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(payload)
        })
          .then(r => r.json())
          .then(() => {
            setSaving(false);
            fetchStatus(true);
            setTimeout(() => fetchStatus(false), 1200);
          })
          .catch(() => setSaving(false));
      }

      function handleRotateToken() {
        if (!window.confirm("确定要重置 Bridge Token 吗？重置后所有已配对手机和浏览器会话都会立即失效，需要重新导入配对码或输入新 Token。")) {
          return;
        }
        setRotating(true);
        fetch("/api/cloud-tunnel/rotate-token", { method: "POST" })
          .then(r => r.json())
          .then(d => {
            setRotating(false);
            if (d && d.bridgeToken) {
              setData(prev => prev ? Object.assign({}, prev, { bridgeToken: d.bridgeToken }) : prev);
            }
            fetchStatus(false);
          })
          .catch(() => setRotating(false));
      }

      function handleReconnect() {
        setSaving(true);
        setForm(prev => Object.assign({}, prev, { enabled: true }));
        fetch("/api/cloud-tunnel/reconnect", { method: "POST" })
          .then(r => r.json())
          .then(() => {
            setSaving(false);
            fetchStatus(false);
            setTimeout(() => fetchStatus(false), 1500);
          })
          .catch(() => setSaving(false));
      }

      function handleToggle() {
        fetch("/api/cloud-tunnel/toggle", { method: "POST" })
          .then(r => r.json())
          .then(d => {
            setForm(prev => Object.assign({}, prev, { enabled: Boolean(d && d.enabled) }));
            fetchStatus(false);
          });
      }

      function copyText(text, label) {
        navigator.clipboard.writeText(text);
        setCopied(label);
        setTimeout(() => setCopied(""), 2000);
      }

      function loadScript() {
        if (serverScript) {
          setShowScript(!showScript);
          return;
        }
        fetch("/api/cloud-tunnel/server-script")
          .then(r => r.text())
          .then(txt => {
            setServerScript(txt);
            setShowScript(true);
          });
      }

      var status = data ? data.status : "connecting";
      var lastError = data && data.lastError ? data.lastError : "";
      var lanIps = (data && data.lanIps && data.lanIps.length) ? data.lanIps : ["127.0.0.1"];
      var lanIp = lanIps[0];
      var localPort = form.localPort || 58000;
      var lanDirectEnabled = Boolean(form.lanDirectEnabled);
      var lanUrl = lanDirectEnabled ? ("http://" + lanIp + ":" + localPort + "/") : "已关闭（仅监听 127.0.0.1）";
      var wanUrl = form.publicUrl || "";
      var bridgeToken = (data && data.bridgeToken) ? data.bridgeToken : "";
      var maskedToken = bridgeToken ? (bridgeToken.slice(0, 6) + "••••••••••••" + bridgeToken.slice(-4)) : "生成中...";
      var pairingCode = "";
      try {
        if (bridgeToken) {
          var cfgObj = {
            name: (data && data.hostName) ? data.hostName : "我的 DSH 电脑",
            localPort: Number(localPort) || 58000,
            lanUrls: lanDirectEnabled ? lanIps.map(ip => "http://" + ip + ":" + localPort) : [],
            wanUrl: wanUrl,
            token: bridgeToken
          };
          var b64 = btoa(unescape(encodeURIComponent(JSON.stringify(cfgObj))))
            .replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
          pairingCode = "dsh-tunnel://cfg?d=" + b64;
        }
      } catch (_) {}

      var statusColor = status === "connected" ? "#10b981" : status === "connecting" ? "#f59e0b" : status === "error" ? "#ef4444" : "#64748b";
      var statusText = status === "connected" ? "隧道已连通" : status === "connecting" ? "正在连接..." : status === "error" ? "连接异常" : "未连接";

      return React.createElement("div", {
        style: {
          position: "fixed",
          top: 0,
          left: 0,
          width: "100vw",
          height: "100vh",
          backgroundColor: "rgba(0, 0, 0, 0.72)",
          backdropFilter: "blur(6px)",
          zIndex: 999999,
          display: "flex",
          alignItems: "center",
          justifyContent: "center"
        },
        onClick: (e) => {
          if (e.target === e.currentTarget) onClose();
        }
      }, [
        React.createElement("div", {
          key: "card",
          style: {
            width: "92%",
            maxWidth: "560px",
            maxHeight: "90vh",
            background: "#0f172a",
            borderRadius: "16px",
            border: "1px solid rgba(255, 255, 255, 0.12)",
            color: "#f8fafc",
            display: "flex",
            flexDirection: "column",
            overflow: "hidden",
            boxShadow: "0 25px 50px -12px rgba(0,0,0,0.8)"
          }
        }, [
          // Header
          React.createElement("div", {
            key: "h",
            style: {
              padding: "16px 20px",
              background: "#1e293b",
              borderBottom: "1px solid rgba(255, 255, 255, 0.1)",
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between"
            }
          }, [
            React.createElement("div", { key: "title-box", style: { display: "flex", alignItems: "center", gap: "10px" } }, [
              React.createElement("span", { key: "dot", style: { width: "10px", height: "10px", borderRadius: "50%", background: statusColor } }),
              React.createElement("span", { key: "t", style: { fontSize: "16px", fontWeight: "600" } }, "云服务器远程穿透"),
              React.createElement("span", { key: "st", style: { fontSize: "12px", color: statusColor, background: "rgba(255,255,255,0.06)", padding: "2px 8px", borderRadius: "4px" } }, statusText)
            ]),
            React.createElement("button", {
              key: "cls",
              onClick: onClose,
              style: { background: "transparent", border: "none", color: "#94a3b8", cursor: "pointer", fontSize: "20px" }
            }, "✕")
          ]),

          // Body
          React.createElement("div", {
            key: "b",
            style: { padding: "20px", overflowY: "auto", flex: 1, display: "flex", flexDirection: "column", gap: "16px" }
          }, [
            // Error Banner (if any and not connected)
            (lastError && status !== "connected") ? React.createElement("div", {
              key: "err-box",
              style: {
                background: "rgba(239, 68, 68, 0.14)",
                border: "1px solid rgba(239, 68, 68, 0.35)",
                borderRadius: "8px",
                padding: "10px 12px",
                fontSize: "12px",
                color: "#fca5a5",
                lineHeight: "1.45",
                wordBreak: "break-word"
              }
            }, "⚠ " + lastError) : null,

            // Quick Access Cards
            React.createElement("div", {
              key: "links",
              style: { display: "grid", gridTemplateColumns: "1fr 1fr", gap: "12px" }
            }, [
              // LAN card
              React.createElement("div", {
                key: "lan",
                style: { background: "#1e293b", borderRadius: "10px", padding: "12px", border: "1px solid rgba(255,255,255,0.08)" }
              }, [
                React.createElement("div", { key: "lan-t", style: { fontSize: "12px", color: "#94a3b8", marginBottom: "4px" } }, "📶 局域网直连 (Wi-Fi)"),
                React.createElement("div", { key: "lan-u", style: { fontSize: "13px", fontWeight: "500", wordBreak: "break-all", marginBottom: "8px", color: lanDirectEnabled ? "#f8fafc" : "#94a3b8" } }, lanUrl),
                lanDirectEnabled ? React.createElement("button", {
                  key: "lan-btn",
                  onClick: () => copyText(lanUrl, "lan"),
                  style: { background: "#334155", border: "none", borderRadius: "6px", color: "#f8fafc", fontSize: "11px", padding: "4px 8px", cursor: "pointer" }
                }, copied === "lan" ? "✓ 已复制" : "复制地址") : React.createElement("span", {
                  key: "lan-tip",
                  style: { fontSize: "11px", color: "#34d399" }
                }, "🔒 外部不可见 (推荐)")
              ]),
              // WAN card
              React.createElement("div", {
                key: "wan",
                style: { background: "#1e293b", borderRadius: "10px", padding: "12px", border: "1px solid rgba(255,255,255,0.08)" }
              }, [
                React.createElement("div", { key: "wan-t", style: { fontSize: "12px", color: "#94a3b8", marginBottom: "4px" } }, "☁️ 公网远程 (4G/5G)"),
                React.createElement("div", { key: "wan-u", style: { fontSize: "13px", fontWeight: "500", wordBreak: "break-all", marginBottom: "8px" } }, wanUrl || "未配置公网地址"),
                wanUrl ? React.createElement("button", {
                  key: "wan-btn",
                  onClick: () => copyText(wanUrl, "wan"),
                  style: { background: "#334155", border: "none", borderRadius: "6px", color: "#f8fafc", fontSize: "11px", padding: "4px 8px", cursor: "pointer" }
                }, copied === "wan" ? "✓ 已复制" : "复制地址") : null
              ])
            ]),

            // Mobile Pairing Code Bar
            pairingCode ? React.createElement("div", {
              key: "pair-bar",
              style: {
                display: "flex",
                justifyContent: "space-between",
                alignItems: "center",
                gap: "10px",
                background: "rgba(56, 189, 248, 0.08)",
                border: "1px solid rgba(56, 189, 248, 0.25)",
                padding: "10px 14px",
                borderRadius: "10px"
              }
            }, [
              React.createElement("div", { key: "pt", style: { fontSize: "12px", color: "#bae6fd", lineHeight: "1.4" } },
                "📱 手机端 App 一键导入（已含安全 Token）：复制配置码并在手机 App「从剪贴板导入」粘贴"),
              React.createElement("button", {
                key: "pbtn",
                onClick: () => copyText(pairingCode, "pair"),
                style: {
                  background: "#0284c7",
                  border: "none",
                  borderRadius: "6px",
                  color: "#fff",
                  fontSize: "11px",
                  fontWeight: "600",
                  padding: "6px 10px",
                  whiteSpace: "nowrap",
                  cursor: "pointer"
                }
              }, copied === "pair" ? "✓ 已复制配对码" : "复制配置码")
            ]) : null,

            // Toggle Bar
            React.createElement("div", {
              key: "tgl-bar",
              style: { display: "flex", justifyContent: "space-between", alignItems: "center", background: "#1e293b", padding: "10px 14px", borderRadius: "8px" }
            }, [
              React.createElement("span", { key: "lb", style: { fontSize: "13px" } }, "穿透隧道开关"),
              React.createElement("button", {
                key: "tgl-btn",
                onClick: handleToggle,
                style: {
                  background: form.enabled ? "#10b981" : "#475569",
                  color: "#fff",
                  border: "none",
                  borderRadius: "20px",
                  padding: "4px 14px",
                  fontSize: "12px",
                  fontWeight: "600",
                  cursor: "pointer"
                }
              }, form.enabled ? "已开启" : "已关闭")
            ]),

            // Security & Bridge Access Control Card
            React.createElement("div", {
              key: "sec-box",
              style: { display: "flex", flexDirection: "column", gap: "10px", background: "rgba(16, 185, 129, 0.06)", border: "1px solid rgba(16, 185, 129, 0.22)", padding: "12px 14px", borderRadius: "10px" }
            }, [
              React.createElement("div", { key: "sec-hdr", style: { fontSize: "13px", fontWeight: "600", color: "#6ee7b7" } }, "🛡️ 网桥安全加固"),
              // LAN direct toggle
              React.createElement("div", {
                key: "lan-tgl-row",
                style: { display: "flex", justifyContent: "space-between", alignItems: "center", gap: "10px" }
              }, [
                React.createElement("div", { key: "lt-txt" }, [
                  React.createElement("div", { key: "lt-1", style: { fontSize: "12px", fontWeight: "500", color: "#f8fafc" } }, "允许同 Wi-Fi 局域网直连 (0.0.0.0)"),
                  React.createElement("div", { key: "lt-2", style: { fontSize: "11px", color: "#94a3b8", marginTop: "2px" } },
                    lanDirectEnabled
                      ? "已监听所有网卡，所有请求均强制校验 Bridge Token 与同源策略"
                      : "仅监听 127.0.0.1（推荐：公共 Wi-Fi 下外部完全不可见）")
                ]),
                React.createElement("button", {
                  key: "lan-tgl-btn",
                  onClick: () => {
                    var nextVal = !lanDirectEnabled;
                    setForm(Object.assign({}, form, { lanDirectEnabled: nextVal }));
                    handleSave({ lanDirectEnabled: nextVal });
                  },
                  style: {
                    background: lanDirectEnabled ? "#0284c7" : "#475569",
                    color: "#fff",
                    border: "none",
                    borderRadius: "20px",
                    padding: "4px 12px",
                    fontSize: "11px",
                    fontWeight: "600",
                    whiteSpace: "nowrap",
                    cursor: "pointer"
                  }
                }, lanDirectEnabled ? "已开启" : "已关闭")
              ]),
              // Bridge Token row
              React.createElement("div", { key: "tk-row", style: { display: "flex", flexDirection: "column", gap: "6px", borderTop: "1px solid rgba(255,255,255,0.08)", paddingTop: "8px" } }, [
                React.createElement("div", { key: "tk-lbl", style: { fontSize: "11px", color: "#cbd5e1" } },
                  "网桥访问令牌 (Bridge Token) — 无论是局域网直连还是云端网页登录都必须凭此 Token 鉴权："),
                React.createElement("div", { key: "tk-ctrl", style: { display: "flex", gap: "6px", alignItems: "center" } }, [
                  React.createElement("code", {
                    key: "tk-val",
                    style: { flex: 1, background: "#020617", border: "1px solid #334155", borderRadius: "6px", padding: "6px 8px", fontSize: "11px", color: "#38bdf8", wordBreak: "break-all" }
                  }, showToken ? bridgeToken : maskedToken),
                  React.createElement("button", {
                    key: "tk-eye",
                    onClick: () => setShowToken(!showToken),
                    style: { background: "#334155", border: "none", borderRadius: "6px", color: "#e2e8f0", fontSize: "11px", padding: "6px 8px", cursor: "pointer", whiteSpace: "nowrap" }
                  }, showToken ? "隐藏" : "显示"),
                  React.createElement("button", {
                    key: "tk-copy",
                    onClick: () => copyText(bridgeToken, "token"),
                    disabled: !bridgeToken,
                    style: { background: "#0284c7", border: "none", borderRadius: "6px", color: "#fff", fontSize: "11px", padding: "6px 8px", cursor: "pointer", whiteSpace: "nowrap" }
                  }, copied === "token" ? "✓ 已复制" : "复制"),
                  React.createElement("button", {
                    key: "tk-rot",
                    onClick: handleRotateToken,
                    disabled: rotating,
                    style: { background: "rgba(239, 68, 68, 0.2)", border: "1px solid rgba(239, 68, 68, 0.4)", borderRadius: "6px", color: "#fca5a5", fontSize: "11px", padding: "6px 8px", cursor: "pointer", whiteSpace: "nowrap" }
                  }, rotating ? "重置中..." : "重置")
                ])
              ])
            ]),

            // Form
            React.createElement("div", {
              key: "form",
              style: { display: "flex", flexDirection: "column", gap: "10px", background: "rgba(255,255,255,0.03)", padding: "14px", borderRadius: "10px" }
            }, [
              React.createElement("div", { key: "sec-t", style: { fontSize: "13px", fontWeight: "600", color: "#cbd5e1" } }, "⚙️ 通用服务器配置"),

              // Host & Port
              React.createElement("div", { key: "row1", style: { display: "grid", gridTemplateColumns: "2fr 1fr", gap: "8px" } }, [
                React.createElement("div", { key: "c1" }, [
                  React.createElement("label", { style: { fontSize: "11px", color: "#94a3b8" } }, "服务器 IP / 域名"),
                  React.createElement("input", {
                    placeholder: "例如 203.0.113.10 或 dsh.example.com",
                    value: form.serverHost || "",
                    onChange: e => setForm({ ...form, serverHost: e.target.value }),
                    style: { width: "100%", background: "#1e293b", border: "1px solid #334155", color: "#fff", borderRadius: "6px", padding: "6px 8px", fontSize: "12px", marginTop: "4px" }
                  })
                ]),
                React.createElement("div", { key: "c2" }, [
                  React.createElement("label", { style: { fontSize: "11px", color: "#94a3b8" } }, "SSH 端口"),
                  React.createElement("input", {
                    value: form.serverPort !== undefined ? form.serverPort : 22,
                    onChange: e => setForm({ ...form, serverPort: e.target.value }),
                    style: { width: "100%", background: "#1e293b", border: "1px solid #334155", color: "#fff", borderRadius: "6px", padding: "6px 8px", fontSize: "12px", marginTop: "4px" }
                  })
                ])
              ]),

              // User & Auth Type
              React.createElement("div", { key: "row2", style: { display: "grid", gridTemplateColumns: "1fr 1fr", gap: "8px" } }, [
                React.createElement("div", { key: "u" }, [
                  React.createElement("label", { style: { fontSize: "11px", color: "#94a3b8" } }, "SSH 用户名"),
                  React.createElement("input", {
                    value: form.serverUser || "root",
                    onChange: e => setForm({ ...form, serverUser: e.target.value }),
                    style: { width: "100%", background: "#1e293b", border: "1px solid #334155", color: "#fff", borderRadius: "6px", padding: "6px 8px", fontSize: "12px", marginTop: "4px" }
                  })
                ]),
                React.createElement("div", { key: "at" }, [
                  React.createElement("label", { style: { fontSize: "11px", color: "#94a3b8" } }, "认证方式"),
                  React.createElement("select", {
                    value: form.authType || "key",
                    onChange: e => setForm({ ...form, authType: e.target.value }),
                    style: { width: "100%", background: "#1e293b", border: "1px solid #334155", color: "#fff", borderRadius: "6px", padding: "6px 8px", fontSize: "12px", marginTop: "4px" }
                  }, [
                    React.createElement("option", { key: "k", value: "key" }, "私钥文件 (Key)"),
                    React.createElement("option", { key: "p", value: "password" }, "账号密码 (Password)")
                  ])
                ])
              ]),

              // Key Path or Password
              form.authType === "key" ? React.createElement("div", { key: "kp" }, [
                React.createElement("label", { style: { fontSize: "11px", color: "#94a3b8" } }, "本地私钥文件完整路径 (如 ~/.ssh/id_rsa)"),
                React.createElement("input", {
                  placeholder: "例如 ~/.ssh/id_rsa",
                  value: form.keyPath || "",
                  onChange: e => setForm({ ...form, keyPath: e.target.value }),
                  style: { width: "100%", background: "#1e293b", border: "1px solid #334155", color: "#fff", borderRadius: "6px", padding: "6px 8px", fontSize: "12px", marginTop: "4px" }
                })
              ]) : React.createElement("div", { key: "pw" }, [
                React.createElement("label", { style: { fontSize: "11px", color: "#94a3b8" } }, "SSH 登录密码"),
                React.createElement("input", {
                  type: "password",
                  placeholder: form.hasPassword ? "已保存密码（留空则保持不变）" : "请输入 SSH 登录密码",
                  value: form.password || "",
                  onChange: e => setForm({ ...form, password: e.target.value }),
                  style: { width: "100%", background: "#1e293b", border: "1px solid #334155", color: "#fff", borderRadius: "6px", padding: "6px 8px", fontSize: "12px", marginTop: "4px" }
                }),
                React.createElement("div", { key: "pw-warn", style: { fontSize: "11px", color: "#fbbf24", marginTop: "4px", lineHeight: "1.4" } },
                  "⚠️ 建议日常一律改用 SSH 私钥登录（虽然密码已不再写入临时 askpass 脚本，但仍会保存在本机配置文件中）。")
              ]),

              // Local & Remote Ports
              React.createElement("div", { key: "ports-row", style: { display: "grid", gridTemplateColumns: "1fr 1fr", gap: "8px" } }, [
                React.createElement("div", { key: "lp" }, [
                  React.createElement("label", { style: { fontSize: "11px", color: "#94a3b8" } }, "本地网桥端口"),
                  React.createElement("input", {
                    value: form.localPort !== undefined ? form.localPort : 58000,
                    onChange: e => setForm({ ...form, localPort: e.target.value }),
                    style: { width: "100%", background: "#1e293b", border: "1px solid #334155", color: "#fff", borderRadius: "6px", padding: "6px 8px", fontSize: "12px", marginTop: "4px" }
                  })
                ]),
                React.createElement("div", { key: "rp" }, [
                  React.createElement("label", { style: { fontSize: "11px", color: "#94a3b8" } }, "云服务器映射端口 (Nginx proxy_pass)"),
                  React.createElement("input", {
                    value: form.remotePort !== undefined ? form.remotePort : 58001,
                    onChange: e => setForm({ ...form, remotePort: e.target.value }),
                    style: { width: "100%", background: "#1e293b", border: "1px solid #334155", color: "#fff", borderRadius: "6px", padding: "6px 8px", fontSize: "12px", marginTop: "4px" }
                  })
                ])
              ]),

              // Public URL
              React.createElement("div", { key: "purl" }, [
                React.createElement("label", { style: { fontSize: "11px", color: "#94a3b8" } }, "公网访问完整地址 (如 https://dsh.example.com:8443)"),
                React.createElement("input", {
                  placeholder: "https://dsh.example.com:8443",
                  value: form.publicUrl || "",
                  onChange: e => setForm({ ...form, publicUrl: e.target.value }),
                  style: { width: "100%", background: "#1e293b", border: "1px solid #334155", color: "#fff", borderRadius: "6px", padding: "6px 8px", fontSize: "12px", marginTop: "4px" }
                })
              ]),

              // Action Buttons
              React.createElement("div", { key: "acts", style: { display: "flex", gap: "8px", marginTop: "8px" } }, [
                React.createElement("button", {
                  key: "save",
                  onClick: () => handleSave(),
                  disabled: saving,
                  style: {
                    flex: 1,
                    background: "#2563eb",
                    border: "none",
                    borderRadius: "6px",
                    color: "#fff",
                    padding: "8px 12px",
                    fontSize: "12px",
                    fontWeight: "600",
                    cursor: "pointer"
                  }
                }, saving ? "保存中..." : "保存并连接"),
                React.createElement("button", {
                  key: "reconn",
                  onClick: handleReconnect,
                  disabled: saving,
                  style: {
                    background: "#334155",
                    border: "none",
                    borderRadius: "6px",
                    color: "#f8fafc",
                    padding: "8px 12px",
                    fontSize: "12px",
                    cursor: "pointer"
                  }
                }, "重连"),
                React.createElement("button", {
                  key: "script-btn",
                  onClick: loadScript,
                  style: {
                    background: "rgba(255,255,255,0.08)",
                    border: "1px solid rgba(255,255,255,0.15)",
                    borderRadius: "6px",
                    color: "#cbd5e1",
                    padding: "8px 12px",
                    fontSize: "12px",
                    cursor: "pointer"
                  }
                }, showScript ? "收起脚本" : "📜 配置脚本")
              ])
            ]),

            // Server Script Drawer
            showScript ? React.createElement("div", {
              key: "script-box",
              style: { background: "#020617", border: "1px solid #334155", borderRadius: "8px", padding: "12px" }
            }, [
              React.createElement("div", { style: { display: "flex", justifyContent: "space-between", marginBottom: "8px" } }, [
                React.createElement("span", { style: { fontSize: "11px", color: "#94a3b8" } }, "复制并在您的云服务器运行此脚本："),
                React.createElement("button", {
                  onClick: () => copyText(serverScript, "script"),
                  style: { background: "#334155", border: "none", borderRadius: "4px", color: "#fff", fontSize: "10px", padding: "2px 6px", cursor: "pointer" }
                }, copied === "script" ? "✓ 已复制" : "复制全部脚本")
              ]),
              React.createElement("pre", {
                style: { margin: 0, fontSize: "11px", color: "#94a3b8", maxHeight: "160px", overflowY: "auto", whiteSpace: "pre-wrap" }
              }, serverScript)
            ]) : null
          ])
        ])
      ]);
    }

    function SidebarButton(props) {
      var wide = Boolean(props && props.wide);
      var [isOpen, setIsOpen] = React.useState(false);
      var [status, setStatus] = React.useState("connecting");

      React.useEffect(() => {
        var fn = () => {
          fetch("/api/cloud-tunnel/status")
            .then(r => r.json())
            .then(d => setStatus(d.status || "disconnected"))
            .catch(() => setStatus("disconnected"));
        };
        fn();
        var timer = setInterval(fn, 6000);
        return () => clearInterval(timer);
      }, []);

      var dotColor = status === "connected" ? "#10b981" : status === "connecting" ? "#f59e0b" : status === "error" ? "#ef4444" : "#64748b";

      return React.createElement(React.Fragment, null, [
        React.createElement("button", {
          key: "btn",
          type: "button",
          onClick: () => setIsOpen(true),
          title: "云服务器远程穿透",
          style: {
            background: "transparent",
            border: "none",
            borderRadius: "6px",
            cursor: "pointer",
            display: "flex",
            alignItems: "center",
            justifyContent: wide ? "flex-start" : "center",
            width: "100%",
            height: "36px",
            padding: wide ? "0 10px" : "0",
            color: "inherit",
            opacity: 0.9,
            position: "relative"
          }
        }, [
          React.createElement("svg", {
            key: "svg",
            width: "18",
            height: "18",
            viewBox: "0 0 24 24",
            fill: "none",
            stroke: "currentColor",
            strokeWidth: "2",
            strokeLinecap: "round",
            strokeLinejoin: "round"
          }, [
            React.createElement("path", { key: "c", d: "M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z" })
          ]),
          // Status indicator dot
          React.createElement("span", {
            key: "dot",
            style: {
              position: "absolute",
              top: "8px",
              right: wide ? "auto" : "8px",
              left: wide ? "22px" : "auto",
              width: "6px",
              height: "6px",
              borderRadius: "50%",
              backgroundColor: dotColor
            }
          }),
          wide ? React.createElement("span", {
            key: "lbl",
            style: { marginLeft: "8px", fontSize: "13px" }
          }, "云端穿透") : null
        ]),
        isOpen ? React.createElement(CloudTunnelModal, {
          key: "modal",
          onClose: () => setIsOpen(false)
        }) : null
      ]);
    }

    function apply(ctx) {
      if (!ctx.slots) return;

      try {
        ctx.slots.inject("sidebar.footer.action", () => {
          return ctx.slots.register({
            name: "sidebar.footer.action",
            id: "dsh-cloud-tunnel",
            order: 86
          }, SidebarButton);
        });
      } catch (err) {
        console.warn("[dsh-cloud-tunnel] sidebar slot registration failed:", err);
      }

      try {
        ctx.slots.inject("settings.section", () => {
          return ctx.slots.register({
            name: "settings.section",
            id: "cloud-tunnel",
            order: 96,
            label: () => "云端远程穿透"
          }, () => React.createElement(SidebarButton, { wide: true }));
        });
      } catch (err) {
        console.warn("[dsh-cloud-tunnel] settings section registration failed:", err);
      }
    }

    module.exports = {
      apply: apply,
      inject: inject
    };
    return module.exports;
  }
});
