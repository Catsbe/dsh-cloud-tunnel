window.__ModuleLoader__.load({
  id: "dsh-cloud-tunnel",
  factory: (require) => {
    var module = { exports: {} };
    var exports = module.exports;

    var React = require("react");
    var inject = ["slots"];

    // Self-contained ISO-18004 QR Code SVG generator (Byte mode, ECC Level L, Versions 1..20)
    var encodeQrSvgDataUri = (function () {
      var EXP = new Uint8Array(512);
      var LOG = new Uint8Array(256);
      var x = 1;
      for (var i = 0; i < 255; i++) {
        EXP[i] = x;
        LOG[x] = i;
        x <<= 1;
        if (x & 0x100) x ^= 0x11d;
      }
      for (var j = 255; j < 512; j++) EXP[j] = EXP[j - 255];

      function gfMul(a, b) {
        if (a === 0 || b === 0) return 0;
        return EXP[LOG[a] + LOG[b]];
      }

      function rsPoly(ecLen) {
        var p = [1];
        for (var i = 0; i < ecLen; i++) {
          var next = new Array(p.length + 1).fill(0);
          var factor = EXP[i];
          for (var j = 0; j < p.length; j++) {
            next[j] ^= p[j];
            next[j + 1] ^= gfMul(p[j], factor);
          }
          p = next;
        }
        return p;
      }

      function rsEncode(data, ecLen) {
        var gen = rsPoly(ecLen);
        var res = new Uint8Array(data.length + ecLen);
        res.set(data);
        for (var i = 0; i < data.length; i++) {
          var coef = res[i];
          if (coef !== 0) {
            for (var j = 0; j < gen.length; j++) {
              res[i + j] ^= gfMul(gen[j], coef);
            }
          }
        }
        return res.slice(data.length);
      }

      var VERSIONS = [
        null,
        [26, 7, 1, 19, 0, 0], [44, 10, 1, 34, 0, 0], [70, 15, 1, 55, 0, 0],
        [100, 20, 1, 80, 0, 0], [134, 26, 1, 108, 0, 0], [172, 18, 2, 68, 0, 0],
        [196, 20, 2, 78, 0, 0], [242, 24, 2, 97, 0, 0], [292, 30, 2, 116, 0, 0],
        [346, 18, 2, 68, 2, 69], [404, 20, 4, 81, 0, 0], [466, 24, 2, 92, 2, 93],
        [532, 26, 4, 107, 0, 0], [581, 30, 3, 115, 1, 116], [655, 22, 5, 87, 1, 88],
        [733, 24, 5, 98, 1, 99], [815, 28, 1, 107, 5, 108], [901, 30, 5, 120, 1, 121],
        [991, 28, 3, 113, 4, 114], [1085, 28, 3, 107, 5, 108]
      ];

      var ALIGN_POS = [
        null,
        [], [6, 18], [6, 22], [6, 26], [6, 30], [6, 34],
        [6, 22, 38], [6, 24, 42], [6, 26, 46], [6, 28, 50],
        [6, 30, 54], [6, 32, 58], [6, 34, 62],
        [6, 26, 46, 66], [6, 26, 48, 70], [6, 26, 50, 74],
        [6, 30, 54, 78], [6, 30, 56, 82], [6, 30, 58, 86], [6, 34, 62, 90]
      ];

      function encode(text) {
        var bytes = new TextEncoder().encode(text);
        var ver = 1;
        for (; ver < VERSIONS.length; ver++) {
          var info = VERSIONS[ver];
          var dataCap = info[2] * info[3] + info[4] * info[5];
          var lenBits = ver < 10 ? 8 : 16;
          if (4 + lenBits + bytes.length * 8 <= dataCap * 8) break;
        }
        if (ver >= VERSIONS.length) return "";

        var vInfo = VERSIONS[ver];
        var ecPerBlk = vInfo[1], b1 = vInfo[2], d1 = vInfo[3], b2 = vInfo[4], d2 = vInfo[5];
        var dataCapBytes = b1 * d1 + b2 * d2;
        var lenBits2 = ver < 10 ? 8 : 16;

        var bits = [];
        function pushBits(val, n) {
          for (var k = n - 1; k >= 0; k--) bits.push((val >> k) & 1);
        }
        pushBits(4, 4);
        pushBits(bytes.length, lenBits2);
        for (var i = 0; i < bytes.length; i++) pushBits(bytes[i], 8);
        var maxBits = dataCapBytes * 8;
        var termLen = Math.min(4, maxBits - bits.length);
        for (var t = 0; t < termLen; t++) bits.push(0);
        while (bits.length % 8 !== 0) bits.push(0);
        var padBytes = [0xec, 0x11];
        var pi = 0;
        while (bits.length < maxBits) {
          pushBits(padBytes[pi % 2], 8);
          pi++;
        }

        var dataBytes = new Uint8Array(dataCapBytes);
        for (var i2 = 0; i2 < dataCapBytes; i2++) {
          var v = 0;
          for (var b = 0; b < 8; b++) v = (v << 1) | bits[i2 * 8 + b];
          dataBytes[i2] = v;
        }

        var blocks = [];
        var ecs = [];
        var offset = 0;
        for (var bi = 0; bi < b1; bi++) {
          var sl = dataBytes.slice(offset, offset + d1);
          offset += d1;
          blocks.push(sl);
          ecs.push(rsEncode(sl, ecPerBlk));
        }
        for (var bi2 = 0; bi2 < b2; bi2++) {
          var sl2 = dataBytes.slice(offset, offset + d2);
          offset += d2;
          blocks.push(sl2);
          ecs.push(rsEncode(sl2, ecPerBlk));
        }

        var interleaved = [];
        var maxD = Math.max(d1, d2);
        for (var col = 0; col < maxD; col++) {
          for (var blkIdx = 0; blkIdx < blocks.length; blkIdx++) {
            if (col < blocks[blkIdx].length) interleaved.push(blocks[blkIdx][col]);
          }
        }
        for (var eCol = 0; eCol < ecPerBlk; eCol++) {
          for (var ecIdx = 0; ecIdx < ecs.length; ecIdx++) {
            interleaved.push(ecs[ecIdx][eCol]);
          }
        }

        var codeBits = [];
        for (var ci = 0; ci < interleaved.length; ci++) {
          var cw = interleaved[ci];
          for (var kb = 7; kb >= 0; kb--) codeBits.push((cw >> kb) & 1);
        }
        var remBits = ver === 1 ? 0 : (ver <= 6 ? 7 : (ver <= 13 ? 0 : 3));
        for (var rb = 0; rb < remBits; rb++) codeBits.push(0);

        var size = ver * 4 + 17;
        var base = [];
        var func = [];
        for (var rr = 0; rr < size; rr++) {
          base.push(new Int8Array(size).fill(-1));
          func.push(new Uint8Array(size));
        }

        function setFunc(r, c, dark) {
          if (r < 0 || c < 0 || r >= size || c >= size) return;
          base[r][c] = dark ? 1 : 0;
          func[r][c] = 1;
        }

        function placeFinder(top, left) {
          for (var dr = -1; dr <= 7; dr++) {
            for (var dc = -1; dc <= 7; dc++) {
              var r = top + dr, c = left + dc;
              if (r < 0 || c < 0 || r >= size || c >= size) continue;
              var inBox = dr >= 0 && dr <= 6 && dc >= 0 && dc <= 6;
              var dark = inBox && (
                dr === 0 || dr === 6 || dc === 0 || dc === 6 ||
                (dr >= 2 && dr <= 4 && dc >= 2 && dc <= 4)
              );
              setFunc(r, c, dark);
            }
          }
        }

        placeFinder(0, 0);
        placeFinder(0, size - 7);
        placeFinder(size - 7, 0);

        var pos = ALIGN_POS[ver];
        for (var pr = 0; pr < pos.length; pr++) {
          for (var pc = 0; pc < pos.length; pc++) {
            var ar = pos[pr], ac = pos[pc];
            if (func[ar][ac]) continue;
            for (var adr = -2; adr <= 2; adr++) {
              for (var adc = -2; adc <= 2; adc++) {
                setFunc(ar + adr, ac + adc, Math.max(Math.abs(adr), Math.abs(adc)) !== 1);
              }
            }
          }
        }

        for (var ti = 8; ti < size - 8; ti++) {
          if (!func[6][ti]) setFunc(6, ti, ti % 2 === 0);
          if (!func[ti][6]) setFunc(ti, 6, ti % 2 === 0);
        }

        setFunc(size - 8, 8, true);
        for (var fi = 0; fi < 9; fi++) {
          if (fi !== 6) {
            if (!func[8][fi]) setFunc(8, fi, false);
            if (!func[fi][8]) setFunc(fi, 8, false);
          }
        }
        for (var fi2 = 0; fi2 < 8; fi2++) {
          if (!func[8][size - 1 - fi2]) setFunc(8, size - 1 - fi2, false);
          if (fi2 < 7 && !func[size - 1 - fi2][8]) setFunc(size - 1 - fi2, 8, false);
        }

        if (ver >= 7) {
          var vRem = ver;
          for (var vi = 0; vi < 12; vi++) vRem = (vRem << 1) ^ (((vRem >> 11) & 1) * 0x1f25);
          var vBits = (ver << 12) | vRem;
          for (var vi2 = 0; vi2 < 18; vi2++) {
            var bit = ((vBits >> vi2) & 1) === 1;
            var a = Math.floor(vi2 / 3), b2pos = (vi2 % 3) + size - 11;
            setFunc(a, b2pos, bit);
            setFunc(b2pos, a, bit);
          }
        }

        var bitIdx = 0;
        var upward = true;
        for (var right = size - 1; right >= 1; right -= 2) {
          if (right === 6) right = 5;
          for (var zi = 0; zi < size; zi++) {
            var zr = upward ? (size - 1 - zi) : zi;
            for (var lr = 0; lr < 2; lr++) {
              var zc = right - lr;
              if (!func[zr][zc]) {
                base[zr][zc] = bitIdx < codeBits.length ? codeBits[bitIdx++] : 0;
              }
            }
          }
          upward = !upward;
        }

        var maskFn = [
          function (r, c) { return (r + c) % 2 === 0; },
          function (r, c) { return r % 2 === 0; },
          function (r, c) { return c % 3 === 0; },
          function (r, c) { return (r + c) % 3 === 0; },
          function (r, c) { return (Math.floor(r / 2) + Math.floor(c / 3)) % 2 === 0; },
          function (r, c) { return ((r * c) % 2) + ((r * c) % 3) === 0; },
          function (r, c) { return (((r * c) % 2) + ((r * c) % 3)) % 2 === 0; },
          function (r, c) { return (((r + c) % 2) + ((r * c) % 3)) % 2 === 0; }
        ];

        function applyFormat(grid, maskId) {
          var fmtData = (1 << 3) | maskId;
          var rem = fmtData << 10;
          for (var i = 14; i >= 10; i--) {
            if ((rem >> i) & 1) rem ^= (0x537 << (i - 10));
          }
          var fmt = ((fmtData << 10) | rem) ^ 0x5412;
          for (var k = 0; k < 15; k++) {
            var dark = ((fmt >> k) & 1) === 1 ? 1 : 0;
            if (k < 6) grid[k][8] = dark;
            else if (k === 6) grid[7][8] = dark;
            else if (k === 7) grid[8][8] = dark;
            else if (k === 8) grid[8][7] = dark;
            else grid[8][14 - k] = dark;

            if (k < 8) grid[8][size - 1 - k] = dark;
            else grid[size - 15 + k][8] = dark;
          }
        }

        function penalty(g) {
          var score = 0;
          for (var r = 0; r < size; r++) {
            var run = 1;
            for (var c = 1; c < size; c++) {
              if (g[r][c] === g[r][c - 1]) {
                run++;
                if (run === 5) score += 3;
                else if (run > 5) score += 1;
              } else run = 1;
            }
          }
          for (var c2 = 0; c2 < size; c2++) {
            var run2 = 1;
            for (var r2 = 1; r2 < size; r2++) {
              if (g[r2][c2] === g[r2 - 1][c2]) {
                run2++;
                if (run2 === 5) score += 3;
                else if (run2 > 5) score += 1;
              } else run2 = 1;
            }
          }
          for (var r3 = 0; r3 < size - 1; r3++) {
            for (var c3 = 0; c3 < size - 1; c3++) {
              var val = g[r3][c3];
              if (val === g[r3][c3 + 1] && val === g[r3 + 1][c3] && val === g[r3 + 1][c3 + 1]) score += 3;
            }
          }
          var p1 = [1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0];
          var p2 = [0, 0, 0, 0, 1, 0, 1, 1, 1, 0, 1];
          for (var r4 = 0; r4 < size; r4++) {
            for (var c4 = 0; c4 <= size - 11; c4++) {
              var m1 = true, m2 = true, m3 = true, m4 = true;
              for (var k = 0; k < 11; k++) {
                if (g[r4][c4 + k] !== p1[k]) m1 = false;
                if (g[r4][c4 + k] !== p2[k]) m2 = false;
                if (g[c4 + k][r4] !== p1[k]) m3 = false;
                if (g[c4 + k][r4] !== p2[k]) m4 = false;
              }
              if (m1 || m2) score += 40;
              if (m3 || m4) score += 40;
            }
          }
          var darkCount = 0;
          for (var r5 = 0; r5 < size; r5++) for (var c5 = 0; c5 < size; c5++) if (g[r5][c5]) darkCount++;
          var pct = (darkCount * 100) / (size * size);
          score += Math.floor(Math.abs(pct - 50) / 5) * 10;
          return score;
        }

        var bestGrid = null;
        var bestScore = Infinity;
        for (var m = 0; m < 8; m++) {
          var g = [];
          for (var gr = 0; gr < size; gr++) {
            var row = new Uint8Array(size);
            for (var gc = 0; gc < size; gc++) {
              row[gc] = func[gr][gc] ? base[gr][gc] : (maskFn[m](gr, gc) ? (base[gr][gc] ^ 1) : base[gr][gc]);
            }
            g.push(row);
          }
          applyFormat(g, m);
          var sc = penalty(g);
          if (sc < bestScore) {
            bestScore = sc;
            bestGrid = g;
          }
        }

        var quiet = 4;
        var total = size + quiet * 2;
        var parts = [];
        for (var sr = 0; sr < size; sr++) {
          for (var sc2 = 0; sc2 < size; sc2++) {
            if (bestGrid[sr][sc2]) {
              parts.push("M" + (sc2 + quiet) + "," + (sr + quiet) + "h1v1h-1z");
            }
          }
        }
        var svg = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 " + total + " " + total + "' shape-rendering='crispEdges'>" +
          "<rect width='" + total + "' height='" + total + "' fill='#ffffff'/>" +
          "<path d='" + parts.join("") + "' fill='#0f172a'/></svg>";
        return "data:image/svg+xml;charset=utf-8," + encodeURIComponent(svg);
      }

      return encode;
    })();

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
      var [toggling, setToggling] = React.useState(false);
      var [requestError, setRequestError] = React.useState("");
      var [showServerForm, setShowServerForm] = React.useState(false);

      function fetchStatus(forceSyncForm) {
        return fetch("/api/cloud-tunnel/status")
          .then(r => {
            if (!r.ok) throw new Error("HTTP " + r.status);
            return r.json();
          })
          .then(d => {
            setData(d);
            setRequestError("");
            if (!initializedRef.current || forceSyncForm) {
              setForm(Object.assign({}, d.config || {}, { password: "" }));
              initializedRef.current = true;
            }
            setLoading(false);
          })
          .catch(() => {
            setData(prev => Object.assign({}, prev || {}, { status: "unknown" }));
            setRequestError("无法获取隧道实时状态，请检查本地服务连接");
            setLoading(false);
          });
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
        if (toggling) return;
        var currentStatus = data && data.status;
        if (["connected", "connecting", "disconnected", "error"].indexOf(currentStatus) < 0) return;
        var nextEnabled = currentStatus !== "connected" && currentStatus !== "connecting";
        setToggling(true);
        setRequestError("");
        fetch("/api/cloud-tunnel/toggle", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ enabled: nextEnabled })
        })
          .then(r => {
            if (!r.ok) throw new Error("HTTP " + r.status);
            return r.json();
          })
          .then(d => {
            setForm(prev => Object.assign({}, prev, { enabled: Boolean(d && d.enabled) }));
            return fetchStatus(false);
          })
          .catch(err => setRequestError("切换隧道失败：" + (err.message || "网络错误")))
          .finally(() => setToggling(false));
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

      var status = data && data.status ? data.status : "unknown";
      var lastError = requestError || (data && data.lastError ? data.lastError : "");
      var lanIps = (data && data.lanIps && data.lanIps.length) ? data.lanIps : ["127.0.0.1"];
      var lanIp = lanIps[0];
      var localPort = form.localPort || 58000;
      var lanDirectEnabled = Boolean(form.lanDirectEnabled);
      var cloudTunnelButtonText = toggling ? "处理中..." : status === "connecting" ? "连接中..." : status === "connected" ? "已开启" : status === "unknown" ? "状态未知" : "已关闭";
      var cloudTunnelButtonColor = status === "connected" ? "#10b981" : status === "connecting" ? "#f59e0b" : status === "error" ? "#ef4444" : "#475569";
      var lanUrl = "http://" + lanIp + ":" + localPort + "/";
      var wanUrl = form.publicUrl || "";
      var bridgeToken = (data && data.bridgeToken) ? data.bridgeToken : "";
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
      var qrDataUri = pairingCode ? encodeQrSvgDataUri(pairingCode) : "";

      var statusColor = status === "connected" ? "#10b981" : status === "connecting" ? "#f59e0b" : status === "error" ? "#ef4444" : status === "unknown" ? "#64748b" : (lanDirectEnabled ? "#38bdf8" : "#64748b");
      var statusText = status === "connected" ? "云端隧道已连通" : status === "connecting" ? "正在连接..." : status === "error" ? "连接异常" : status === "unknown" ? "状态未知" : (lanDirectEnabled ? "同 Wi-Fi 直连已就绪" : "未开启远程");

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
              React.createElement("span", { key: "t", style: { fontSize: "16px", fontWeight: "600" } }, "手机远程连接"),
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
            style: { padding: "18px 20px", overflowY: "auto", flex: 1, display: "flex", flexDirection: "column", gap: "14px" }
          }, [
            // Error Banner (if any and not connected)
            (requestError || (lastError && status !== "connected")) ? React.createElement("div", {
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

            // Hero QR Pairing Card
            React.createElement("div", {
              key: "qr-card",
              style: {
                display: "flex",
                gap: "16px",
                alignItems: "center",
                background: "rgba(56, 189, 248, 0.07)",
                border: "1px solid rgba(56, 189, 248, 0.25)",
                borderRadius: "12px",
                padding: "14px"
              }
            }, [
              qrDataUri ? React.createElement("div", {
                key: "qr-wrap",
                style: {
                  background: "#ffffff",
                  padding: "6px",
                  borderRadius: "10px",
                  flexShrink: 0,
                  boxShadow: "0 4px 14px rgba(0,0,0,0.35)"
                }
              }, [
                React.createElement("img", {
                  key: "qr-img",
                  src: qrDataUri,
                  alt: "手机扫码绑定二维码",
                  style: { width: "148px", height: "148px", display: "block", imageRendering: "pixelated" }
                })
              ]) : null,
              React.createElement("div", {
                key: "qr-info",
                style: { flex: 1, display: "flex", flexDirection: "column", gap: "8px", minWidth: 0 }
              }, [
                React.createElement("div", { key: "qt1", style: { fontSize: "14px", fontWeight: "600", color: "#f8fafc" } }, "📱 手机扫码一键绑定"),
                React.createElement("div", { key: "qt2", style: { fontSize: "12px", color: "#cbd5e1", lineHeight: "1.45" } },
                  "打开手机「DSH 云端直连」App 点击扫码，自动填入所有连接地址与认证令牌，无需手动填写："),
                React.createElement("div", { key: "qt3", style: { fontSize: "11px", color: "#94a3b8", lineHeight: "1.5" } }, [
                  React.createElement("div", { key: "q-lan" }, "📶 同 Wi-Fi 直连：" + (lanDirectEnabled ? ("已包含 (" + lanIp + ":" + localPort + ")") : "未开启")),
                  React.createElement("div", { key: "q-wan" }, "☁️ 云端中转：" + (wanUrl ? wanUrl : "未配置（外网 4G/5G 时才需要）"))
                ]),
                React.createElement("div", { key: "qr-btns", style: { display: "flex", flexWrap: "wrap", gap: "6px", marginTop: "2px" } }, [
                  pairingCode ? React.createElement("button", {
                    key: "pbtn",
                    onClick: () => copyText(pairingCode, "pair"),
                    style: { background: "#0284c7", border: "none", borderRadius: "6px", color: "#fff", fontSize: "11px", fontWeight: "600", padding: "5px 10px", cursor: "pointer" }
                  }, copied === "pair" ? "✓ 已复制配对码" : "复制配置码") : null,
                  React.createElement("button", {
                    key: "tk-eye",
                    onClick: () => setShowToken(!showToken),
                    style: { background: "#334155", border: "none", borderRadius: "6px", color: "#e2e8f0", fontSize: "11px", padding: "5px 10px", cursor: "pointer" }
                  }, showToken ? "收起令牌" : "查看令牌"),
                  React.createElement("button", {
                    key: "tk-rot",
                    onClick: handleRotateToken,
                    disabled: rotating,
                    style: { background: "rgba(239, 68, 68, 0.16)", border: "1px solid rgba(239, 68, 68, 0.35)", borderRadius: "6px", color: "#fca5a5", fontSize: "11px", padding: "5px 10px", cursor: "pointer" }
                  }, rotating ? "重置中..." : "换新令牌")
                ])
              ])
            ]),

            showToken ? React.createElement("div", {
              key: "tk-drawer",
              style: { display: "flex", alignItems: "center", gap: "8px", background: "#1e293b", padding: "8px 12px", borderRadius: "8px" }
            }, [
              React.createElement("span", { key: "l", style: { fontSize: "11px", color: "#94a3b8", whiteSpace: "nowrap" } }, "当前令牌:"),
              React.createElement("code", { key: "v", style: { flex: 1, fontSize: "11px", color: "#38bdf8", wordBreak: "break-all" } }, bridgeToken),
              React.createElement("button", {
                key: "c",
                onClick: () => copyText(bridgeToken, "token"),
                style: { background: "#334155", border: "none", borderRadius: "6px", color: "#fff", fontSize: "11px", padding: "4px 8px", cursor: "pointer" }
              }, copied === "token" ? "✓ 已复制" : "复制")
            ]) : null,

            // Two Connection Modes Card
            React.createElement("div", {
              key: "modes-box",
              style: { display: "flex", flexDirection: "column", gap: "10px", background: "#1e293b", padding: "12px 14px", borderRadius: "10px", border: "1px solid rgba(255,255,255,0.08)" }
            }, [
              // Mode 1: Same Wi-Fi Direct
              React.createElement("div", {
                key: "m-lan",
                style: { display: "flex", justifyContent: "space-between", alignItems: "center", gap: "12px" }
              }, [
                React.createElement("div", { key: "ml-txt", style: { flex: 1 } }, [
                  React.createElement("div", { key: "ml-1", style: { fontSize: "13px", fontWeight: "600", color: "#f8fafc" } }, "📶 方式一：同一 Wi-Fi 下令牌直连"),
                  React.createElement("div", { key: "ml-2", style: { fontSize: "11px", color: "#94a3b8", marginTop: "2px" } },
                    lanDirectEnabled
                      ? ("已开启（" + lanUrl + "）— 同一 Wi-Fi 的手机扫上方二维码即可凭令牌秒连")
                      : "已关闭 — 仅允许下方云服务器隧道访问，同一 Wi-Fi 下无法扫描发现本机")
                ]),
                React.createElement("button", {
                  key: "lan-tgl-btn",
                  onClick: () => {
                    var nextVal = !lanDirectEnabled;
                    setForm(Object.assign({}, form, { lanDirectEnabled: nextVal }));
                    handleSave({ lanDirectEnabled: nextVal });
                  },
                  style: {
                    background: lanDirectEnabled ? "#10b981" : "#475569",
                    color: "#fff",
                    border: "none",
                    borderRadius: "20px",
                    padding: "5px 14px",
                    fontSize: "12px",
                    fontWeight: "600",
                    whiteSpace: "nowrap",
                    cursor: "pointer"
                  }
                }, lanDirectEnabled ? "已开启" : "已关闭")
              ]),

              // Mode 2: Cloud Server Relay
              React.createElement("div", {
                key: "m-wan",
                style: { display: "flex", justifyContent: "space-between", alignItems: "center", gap: "12px", borderTop: "1px solid rgba(255,255,255,0.08)", paddingTop: "10px" }
              }, [
                React.createElement("div", { key: "mw-txt", style: { flex: 1 } }, [
                  React.createElement("div", { key: "mw-1", style: { fontSize: "13px", fontWeight: "600", color: "#f8fafc" } }, "☁️ 方式二：云服务器中转（外网 4G/5G）"),
                  React.createElement("div", { key: "mw-2", style: { fontSize: "11px", color: "#94a3b8", marginTop: "2px" } },
                    wanUrl
                      ? ("公网地址: " + wanUrl + "（手机端需选定 .p12 证书连接）")
                      : "未填写公网地址；外出通过 4G/5G 访问时请在下方配置服务器并开启")
                ]),
                React.createElement("button", {
                  key: "tgl-btn",
                  onClick: handleToggle,
                  disabled: toggling || status === "unknown",
                  style: {
                    background: cloudTunnelButtonColor,
                    color: "#fff",
                    border: "none",
                    borderRadius: "20px",
                    padding: "5px 14px",
                    fontSize: "12px",
                    fontWeight: "600",
                    whiteSpace: "nowrap",
                    cursor: toggling || status === "unknown" ? "not-allowed" : "pointer",
                    opacity: toggling || status === "unknown" ? 0.7 : 1
                  }
                }, cloudTunnelButtonText)
              ])
            ]),

            // Collapsible Cloud Server Configuration Header
            React.createElement("button", {
              key: "srv-collapse-btn",
              type: "button",
              onClick: () => setShowServerForm(!showServerForm),
              style: {
                display: "flex",
                justifyContent: "space-between",
                alignItems: "center",
                width: "100%",
                background: "rgba(255,255,255,0.04)",
                border: "1px solid rgba(255,255,255,0.1)",
                borderRadius: "10px",
                padding: "10px 14px",
                color: "#cbd5e1",
                fontSize: "12px",
                cursor: "pointer"
              }
            }, [
              React.createElement("span", { key: "sl", style: { fontWeight: "600" } },
                "⚙️ 云服务器中转配置" + (form.serverHost ? ("（" + form.serverHost + "）") : "（仅外网 4G/5G 需要，同 Wi-Fi 可不填）")),
              React.createElement("span", { key: "sr", style: { color: "#94a3b8" } }, showServerForm ? "收起 ▲" : "展开设置 ▼")
            ]),

            // Form
            showServerForm ? React.createElement("div", {
              key: "form",
              style: { display: "flex", flexDirection: "column", gap: "10px", background: "rgba(255,255,255,0.03)", padding: "14px", borderRadius: "10px", border: "1px solid rgba(255,255,255,0.06)" }
            }, [
              React.createElement("div", { key: "sec-t", style: { fontSize: "12px", color: "#94a3b8" } }, "只在同 Wi-Fi 下使用可不填；需要外网 4G/5G 远程访问时，请填写云服务器信息并保存："),

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
                  React.createElement("label", { style: { fontSize: "11px", color: "#94a3b8" } }, "本机接入端口 (默认 58000)"),
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
            ]) : null,

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
