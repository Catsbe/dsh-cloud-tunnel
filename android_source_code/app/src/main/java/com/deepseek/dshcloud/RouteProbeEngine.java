package com.deepseek.dshcloud;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertPathValidatorException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

public class RouteProbeEngine {

    public static class NetworkState {
        public boolean hasAnyNetwork;
        public boolean hasWifiOrEthernet;
        public boolean hasCellular;
        public boolean hasVpn;
        public String localWifiIpv4 = "";
    }

    public static class ProbeResult {
        public boolean ok;
        public boolean authRequired;
        public boolean isLan;
        public int httpCode;
        public long latencyMs;
        public String url;
        public String hostName = "";
        public String publicUrl = "";
        public String matchedProfileId = "";
        public String matchedProfileName = "";
        public List<String> lanIps = new ArrayList<>();
        public String errorDetail = "";
    }

    public static class MachineProbeStatus {
        public String profileId = "";
        public boolean probing = false;
        public ProbeResult lanResult;
        public ProbeResult wanResult;

        public boolean isLanOk() {
            return lanResult != null && lanResult.ok;
        }

        public boolean isWanOk() {
            return wanResult != null && wanResult.ok;
        }

        public boolean anyReachable() {
            return isLanOk() || isWanOk();
        }
    }

    public static class DualProbeDecision {
        public ProbeResult chosen;
        public boolean chosenIsLan;
        public ProbeResult lanSummary;
        public ProbeResult wanSummary;
        public NetworkState networkState;
    }

    public interface ScanCallback {
        void onFound(ProbeResult item);
        void onFinished(List<ProbeResult> allFound, String errorMsg);
    }

    public static NetworkState inspectNetwork(Context ctx) {
        NetworkState state = new NetworkState();
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return state;

        Network active = cm.getActiveNetwork();
        if (active != null) {
            NetworkCapabilities acaps = cm.getNetworkCapabilities(active);
            if (acaps != null) {
                state.hasAnyNetwork = acaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        || acaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || acaps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
            }
        }

        Network[] all = cm.getAllNetworks();
        for (Network net : all) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps == null) continue;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                state.hasWifiOrEthernet = true;
                state.hasAnyNetwork = true;
                if (state.localWifiIpv4.isEmpty()) {
                    LinkProperties lp = cm.getLinkProperties(net);
                    if (lp != null) {
                        for (LinkAddress la : lp.getLinkAddresses()) {
                            InetAddress addr = la.getAddress();
                            if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && addr.isSiteLocalAddress()) {
                                state.localWifiIpv4 = addr.getHostAddress();
                                break;
                            }
                        }
                    }
                }
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                state.hasCellular = true;
                state.hasAnyNetwork = true;
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                state.hasVpn = true;
            }
        }
        return state;
    }

    public static ProbeResult probeSingle(Context ctx, TunnelConfig cfg, String baseUrl, boolean isLan, int connectMs, int readMs) {
        ProbeResult res = new ProbeResult();
        res.url = baseUrl;
        res.isLan = isLan;

        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            res.ok = false;
            res.errorDetail = isLan ? "未配置局域网地址" : "未配置公网转发地址";
            return res;
        }

        long t0 = SystemClock.elapsedRealtime();
        HttpURLConnection conn = null;
        try {
            String pingUrl = baseUrl + "/_dsh_ping?t=" + System.currentTimeMillis();
            URL u = new URL(pingUrl);
            conn = (HttpURLConnection) (isLan ? u.openConnection(Proxy.NO_PROXY) : u.openConnection());
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Accept", "application/json");
            if (cfg != null && cfg.bridgeToken != null && !cfg.bridgeToken.trim().isEmpty()) {
                conn.setRequestProperty("X-DSH-Bridge-Token", cfg.bridgeToken.trim());
            }

            if (conn instanceof HttpsURLConnection) {
                HttpsURLConnection https = (HttpsURLConnection) conn;
                SSLContext sslCtx = ClientCertManager.buildMtlsSslContext(ctx, cfg);
                https.setSSLSocketFactory(sslCtx.getSocketFactory());
                if (cfg != null && cfg.trustedServerCertSha256 != null && !cfg.trustedServerCertSha256.trim().isEmpty()) {
                    final String pinned = cfg.trustedServerCertSha256.trim();
                    final HostnameVerifier defVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
                    https.setHostnameVerifier((hostname, session) -> {
                        if (defVerifier.verify(hostname, session)) return true;
                        return matchesPinnedPeer(session, pinned);
                    });
                }
            }

            int code = conn.getResponseCode();
            res.httpCode = code;
            res.latencyMs = Math.max(1, SystemClock.elapsedRealtime() - t0);

            InputStream stream = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            String body = readSmallBody(stream);

            if (code == 200) {
                try {
                    JSONObject json = new JSONObject(body);
                    String status = json.optString("status", "");
                    String plugin = json.optString("plugin", "");
                    if ("ok".equals(status) && "dsh-cloud-tunnel".equals(plugin)) {
                        res.ok = true;
                        res.hostName = json.optString("hostName", "");
                        res.publicUrl = json.optString("publicUrl", "");
                        JSONArray ips = json.optJSONArray("lanIps");
                        if (ips != null) {
                            for (int i = 0; i < ips.length(); i++) {
                                String ip = ips.optString(i, "");
                                if (!ip.isEmpty()) res.lanIps.add(ip);
                            }
                        }
                        return res;
                    }
                } catch (Exception ignored) {
                }
                res.ok = false;
                res.errorDetail = "目标端口非 DSH 网桥 (HTTP 200)";
                return res;
            }

            res.ok = false;
            if (code == 400) {
                res.errorDetail = "mTLS 客户端证书缺失或不被服务器信任 (HTTP 400)";
            } else if (code == 401) {
                try {
                    JSONObject json = new JSONObject(body);
                    if ("dsh-cloud-tunnel".equals(json.optString("plugin", "")) && json.optBoolean("authRequired", false)) {
                        res.authRequired = true;
                        res.errorDetail = (cfg == null || cfg.bridgeToken == null || cfg.bridgeToken.trim().isEmpty())
                                ? "缺少 Bridge Token，请导入配对码或手动输入令牌"
                                : "Bridge Token 无效或已在电脑端重置 (HTTP 401)";
                        return res;
                    }
                } catch (Exception ignored) {
                }
                res.errorDetail = "网桥鉴权失败 (HTTP 401)";
            } else if (code == 403) {
                res.errorDetail = "网桥安全策略拒绝访问 (HTTP 403)";
            } else if (code == 502 || code == 503 || code == 504) {
                res.errorDetail = "云服务器 mTLS 已连通，但电脑端 SSH 隧道未在线 (HTTP " + code + ")";
            } else if (code >= 300 && code < 400) {
                res.errorDetail = "遇到重定向 (HTTP " + code + ")，可能处于 Wi-Fi 认证页";
            } else {
                res.errorDetail = "HTTP 错误 " + code;
            }
            return res;
        } catch (Exception e) {
            res.latencyMs = Math.max(1, SystemClock.elapsedRealtime() - t0);
            res.ok = false;
            res.errorDetail = translateProbeException(e, connectMs, isLan);
            return res;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }

    public static List<String> authenticateBridgeSession(Context ctx, TunnelConfig cfg, String baseUrl, boolean isLan) {
        List<String> cookies = new ArrayList<>();
        if (baseUrl == null || baseUrl.trim().isEmpty()) return cookies;
        if (cfg == null || cfg.bridgeToken == null || cfg.bridgeToken.trim().isEmpty()) return cookies;

        HttpURLConnection conn = null;
        try {
            URL u = new URL(baseUrl + "/_dsh_bridge_auth");
            conn = (HttpURLConnection) (isLan ? u.openConnection(Proxy.NO_PROXY) : u.openConnection());
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(isLan ? 900 : 3500);
            conn.setReadTimeout(isLan ? 900 : 3500);
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(false);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("X-DSH-Bridge-Token", cfg.bridgeToken.trim());

            if (conn instanceof HttpsURLConnection) {
                HttpsURLConnection https = (HttpsURLConnection) conn;
                SSLContext sslCtx = ClientCertManager.buildMtlsSslContext(ctx, cfg);
                https.setSSLSocketFactory(sslCtx.getSocketFactory());
                if (cfg.trustedServerCertSha256 != null && !cfg.trustedServerCertSha256.trim().isEmpty()) {
                    final String pinned = cfg.trustedServerCertSha256.trim();
                    final HostnameVerifier defVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
                    https.setHostnameVerifier((hostname, session) -> {
                        if (defVerifier.verify(hostname, session)) return true;
                        return matchesPinnedPeer(session, pinned);
                    });
                }
            }

            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write("{}".getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                java.util.Map<String, List<String>> hdrs = conn.getHeaderFields();
                if (hdrs != null) {
                    for (java.util.Map.Entry<String, List<String>> entry : hdrs.entrySet()) {
                        if (entry.getKey() != null && "set-cookie".equalsIgnoreCase(entry.getKey()) && entry.getValue() != null) {
                            cookies.addAll(entry.getValue());
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
        return cookies;
    }

    private static boolean matchesPinnedPeer(SSLSession session, String pinnedSha256) {
        try {
            java.security.cert.Certificate[] certs = session.getPeerCertificates();
            if (certs != null && certs.length > 0 && certs[0] instanceof X509Certificate) {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                byte[] d = md.digest(certs[0].getEncoded());
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < d.length; i++) {
                    if (i > 0) sb.append(':');
                    sb.append(String.format(java.util.Locale.US, "%02X", d[i] & 0xFF));
                }
                return pinnedSha256.equalsIgnoreCase(sb.toString());
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static String translateProbeException(Exception e, int timeoutMs, boolean isLan) {
        if (e instanceof SocketTimeoutException) {
            return isLan ? ("局域网探测超时 (" + timeoutMs + "ms)") : ("公网连接超时 (" + timeoutMs + "ms)");
        }
        if (e instanceof ConnectException) {
            return isLan ? "无法连接到电脑局域网端口" : "无法连接云服务器端口";
        }
        if (e instanceof UnknownHostException) {
            return "域名解析失败";
        }
        if (e instanceof SSLPeerUnverifiedException) {
            return "服务端域名与证书不匹配";
        }
        if (e instanceof SSLHandshakeException) {
            Throwable cause = e.getCause();
            if (cause instanceof CertPathValidatorException) {
                return "服务端 HTTPS 证书不受信任";
            }
            return "mTLS 握手失败，请检查客户端 .p12 证书或服务器";
        }
        String msg = e.getMessage();
        return (msg == null || msg.isEmpty()) ? e.getClass().getSimpleName() : msg;
    }

    private static String readSmallBody(InputStream is) {
        if (is == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[1024];
            int total = 0;
            int n;
            while ((n = br.read(buf)) != -1 && total < 16384) {
                sb.append(buf, 0, n);
                total += n;
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static DualProbeDecision probeBestRoute(Context ctx, TunnelConfig cfg) {
        DualProbeDecision decision = new DualProbeDecision();
        NetworkState netState = inspectNetwork(ctx);
        decision.networkState = netState;

        List<String> lanUrls = cfg.getNormalizedLanUrls();
        String wanUrl = cfg.getNormalizedWanUrl();
        String mode = cfg.routeMode == null ? TunnelConfig.ROUTE_AUTO : cfg.routeMode;

        if (TunnelConfig.ROUTE_LAN_ONLY.equals(mode)) {
            ProbeResult bestLan = probeLanListBest(ctx, cfg, lanUrls, 900);
            decision.lanSummary = bestLan;
            ProbeResult skippedWan = new ProbeResult();
            skippedWan.isLan = false;
            skippedWan.url = wanUrl;
            skippedWan.errorDetail = "当前模式为「仅局域网」";
            decision.wanSummary = skippedWan;
            if (bestLan.ok) {
                decision.chosen = bestLan;
                decision.chosenIsLan = true;
            }
            return decision;
        }

        if (TunnelConfig.ROUTE_WAN_ONLY.equals(mode)) {
            ProbeResult skippedLan = new ProbeResult();
            skippedLan.isLan = true;
            skippedLan.url = lanUrls.isEmpty() ? "" : lanUrls.get(0);
            skippedLan.errorDetail = "当前模式为「仅公网转发」";
            decision.lanSummary = skippedLan;

            ProbeResult wanRes = probeSingle(ctx, cfg, wanUrl, false, 3500, 3500);
            decision.wanSummary = wanRes;
            if (wanRes.ok) {
                decision.chosen = wanRes;
                decision.chosenIsLan = false;
            }
            return decision;
        }

        // AUTO dual-track mode
        boolean canTryLan = !lanUrls.isEmpty() && (netState.hasWifiOrEthernet || netState.hasVpn || !netState.hasCellular);
        if (!canTryLan) {
            ProbeResult skippedLan = new ProbeResult();
            skippedLan.isLan = true;
            skippedLan.url = lanUrls.isEmpty() ? "" : lanUrls.get(0);
            skippedLan.errorDetail = lanUrls.isEmpty() ? "未配置局域网地址" : "当前未连接 Wi-Fi，已秒切公网探测";
            decision.lanSummary = skippedLan;

            ProbeResult wanRes = probeSingle(ctx, cfg, wanUrl, false, 3500, 3500);
            decision.wanSummary = wanRes;
            if (wanRes.ok) {
                decision.chosen = wanRes;
                decision.chosenIsLan = false;
            }
            return decision;
        }

        int poolSize = Math.min(8, lanUrls.size() + 1);
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);

        final AtomicReference<ProbeResult> lanWinner = new AtomicReference<>(null);
        final AtomicReference<ProbeResult> lanFirstErr = new AtomicReference<>(null);
        final AtomicInteger remainingLan = new AtomicInteger(lanUrls.size());
        final CountDownLatch lanWinOrDoneLatch = new CountDownLatch(1);
        final List<Future<ProbeResult>> lanFutures = new ArrayList<>();

        for (String lanU : lanUrls) {
            lanFutures.add(pool.submit(() -> {
                ProbeResult r = probeSingle(ctx, cfg, lanU, true, 650, 650);
                if (r.ok) {
                    lanWinner.compareAndSet(null, r);
                    lanWinOrDoneLatch.countDown();
                } else {
                    lanFirstErr.compareAndSet(null, r);
                }
                if (remainingLan.decrementAndGet() == 0) {
                    lanWinOrDoneLatch.countDown();
                }
                return r;
            }));
        }

        Future<ProbeResult> wanFuture = pool.submit(() -> probeSingle(ctx, cfg, wanUrl, false, 3500, 3500));

        try {
            lanWinOrDoneLatch.await(700, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }

        ProbeResult fastLan = lanWinner.get();
        if (fastLan != null && fastLan.ok) {
            decision.chosen = fastLan;
            decision.chosenIsLan = true;
            decision.lanSummary = fastLan;
            ProbeResult pendingWan = new ProbeResult();
            pendingWan.isLan = false;
            pendingWan.url = wanUrl;
            pendingWan.errorDetail = "局域网已秒连，未等待公网";
            decision.wanSummary = pendingWan;
            pool.shutdownNow();
            return decision;
        }

        // LAN did not win within fast window; wait for WAN probe
        ProbeResult wanRes;
        try {
            wanRes = wanFuture.get(4000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            wanRes = new ProbeResult();
            wanRes.isLan = false;
            wanRes.url = wanUrl;
            wanRes.errorDetail = "公网探测超时";
        }
        decision.wanSummary = wanRes;

        // Check if LAN finished cleanly meanwhile
        ProbeResult lateLan = lanWinner.get();
        if (lateLan != null) {
            decision.lanSummary = lateLan;
        } else {
            ProbeResult err = lanFirstErr.get();
            if (err == null) {
                err = new ProbeResult();
                err.isLan = true;
                err.url = lanUrls.get(0);
                err.errorDetail = "局域网不可达";
            }
            decision.lanSummary = err;
        }

        if (wanRes.ok) {
            decision.chosen = wanRes;
            decision.chosenIsLan = false;
        } else if (lateLan != null && lateLan.ok) {
            decision.chosen = lateLan;
            decision.chosenIsLan = true;
        }

        pool.shutdownNow();
        return decision;
    }

    private static ProbeResult probeLanListBest(Context ctx, TunnelConfig cfg, List<String> lanUrls, int timeoutMs) {
        if (lanUrls.isEmpty()) {
            ProbeResult empty = new ProbeResult();
            empty.isLan = true;
            empty.errorDetail = "未配置局域网地址";
            return empty;
        }
        if (lanUrls.size() == 1) {
            return probeSingle(ctx, cfg, lanUrls.get(0), true, timeoutMs, timeoutMs);
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, lanUrls.size()));
        AtomicReference<ProbeResult> winner = new AtomicReference<>(null);
        AtomicReference<ProbeResult> firstErr = new AtomicReference<>(null);
        AtomicInteger rem = new AtomicInteger(lanUrls.size());
        CountDownLatch latch = new CountDownLatch(1);
        for (String u : lanUrls) {
            pool.submit(() -> {
                ProbeResult r = probeSingle(ctx, cfg, u, true, timeoutMs, timeoutMs);
                if (r.ok) {
                    winner.compareAndSet(null, r);
                    latch.countDown();
                } else {
                    firstErr.compareAndSet(null, r);
                }
                if (rem.decrementAndGet() == 0) latch.countDown();
            });
        }
        try {
            latch.await(timeoutMs + 200L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        pool.shutdownNow();
        if (winner.get() != null) return winner.get();
        return firstErr.get() != null ? firstErr.get() : probeSingle(ctx, cfg, lanUrls.get(0), true, timeoutMs, timeoutMs);
    }

    public static MachineProbeStatus probeProfileAllTracks(Context ctx, TunnelConfig cfg) {
        MachineProbeStatus st = new MachineProbeStatus();
        st.profileId = cfg != null ? cfg.ensureId() : "";
        if (cfg == null) return st;

        if (!TunnelConfig.CERT_NONE.equals(cfg.certMode)) {
            ClientCertManager.loadActiveIdentity(ctx, cfg);
        }

        NetworkState netState = inspectNetwork(ctx);
        List<String> lanUrls = cfg.getNormalizedLanUrls();
        String wanUrl = cfg.getNormalizedWanUrl();
        String mode = cfg.routeMode == null ? TunnelConfig.ROUTE_AUTO : cfg.routeMode;

        if (TunnelConfig.ROUTE_LAN_ONLY.equals(mode)) {
            st.lanResult = probeLanListBest(ctx, cfg, lanUrls, 800);
            ProbeResult skippedWan = new ProbeResult();
            skippedWan.isLan = false;
            skippedWan.url = wanUrl;
            skippedWan.errorDetail = "仅局域网模式";
            st.wanResult = skippedWan;
            return st;
        }

        if (TunnelConfig.ROUTE_WAN_ONLY.equals(mode)) {
            ProbeResult skippedLan = new ProbeResult();
            skippedLan.isLan = true;
            skippedLan.url = lanUrls.isEmpty() ? "" : lanUrls.get(0);
            skippedLan.errorDetail = "仅公网模式";
            st.lanResult = skippedLan;
            st.wanResult = probeSingle(ctx, cfg, wanUrl, false, 3200, 3200);
            return st;
        }

        boolean canTryLan = !lanUrls.isEmpty() && (netState.hasWifiOrEthernet || netState.hasVpn || !netState.hasCellular);
        ExecutorService pairPool = Executors.newFixedThreadPool(2);
        Future<ProbeResult> lanF = pairPool.submit(() -> {
            if (lanUrls.isEmpty()) {
                ProbeResult empty = new ProbeResult();
                empty.isLan = true;
                empty.errorDetail = "未配置局域网地址";
                return empty;
            }
            if (!canTryLan) {
                ProbeResult skipped = new ProbeResult();
                skipped.isLan = true;
                skipped.url = lanUrls.get(0);
                skipped.errorDetail = "当前非 Wi-Fi 网络";
                return skipped;
            }
            return probeLanListBest(ctx, cfg, lanUrls, 750);
        });
        Future<ProbeResult> wanF = pairPool.submit(() -> probeSingle(ctx, cfg, wanUrl, false, 3200, 3200));

        try {
            st.lanResult = lanF.get(2500, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            ProbeResult err = new ProbeResult();
            err.isLan = true;
            err.errorDetail = "局域网探测超时";
            st.lanResult = err;
        }
        try {
            st.wanResult = wanF.get(3800, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            ProbeResult err = new ProbeResult();
            err.isLan = false;
            err.url = wanUrl;
            err.errorDetail = "公网探测超时";
            st.wanResult = err;
        }
        pairPool.shutdownNow();
        return st;
    }

    public static void scanLocalSubnetForDsh(Context ctx, TunnelConfig cfg, int port, ScanCallback callback) {
        List<TunnelConfig> single = new ArrayList<>();
        if (cfg != null) single.add(cfg);
        scanLocalSubnetWithProfiles(ctx, single, port, callback);
    }

    public static void scanLocalSubnetWithProfiles(Context ctx, List<TunnelConfig> boundProfiles, int port, ScanCallback callback) {
        new Thread(() -> {
            NetworkState net = inspectNetwork(ctx);
            if (!net.hasWifiOrEthernet || net.localWifiIpv4.isEmpty()) {
                callback.onFinished(new ArrayList<>(), "未检测到手机 Wi-Fi 局域网 IPv4，请先连接与电脑同一局域网的 Wi-Fi");
                return;
            }
            int lastDot = net.localWifiIpv4.lastIndexOf('.');
            if (lastDot <= 0) {
                callback.onFinished(new ArrayList<>(), "无法解析当前 Wi-Fi 网段: " + net.localWifiIpv4);
                return;
            }
            String prefix = net.localWifiIpv4.substring(0, lastDot + 1);
            List<ProbeResult> found = new ArrayList<>();
            ExecutorService pool = Executors.newFixedThreadPool(36);
            CountDownLatch latch = new CountDownLatch(254);

            for (int i = 1; i <= 254; i++) {
                final String targetIp = prefix + i;
                pool.submit(() -> {
                    try {
                        if (!targetIp.equals(net.localWifiIpv4)) {
                            String url = "http://" + targetIp + ":" + port;
                            ProbeResult pr = probeSingle(ctx, null, url, true, 260, 320);
                            if (pr.authRequired && boundProfiles != null && !boundProfiles.isEmpty()) {
                                for (TunnelConfig p : boundProfiles) {
                                    if (p != null && p.bridgeToken != null && !p.bridgeToken.trim().isEmpty()) {
                                        ProbeResult authPr = probeSingle(ctx, p, url, true, 450, 450);
                                        if (authPr.ok) {
                                            authPr.matchedProfileId = p.id != null ? p.id : "";
                                            authPr.matchedProfileName = p.profileName != null ? p.profileName : "";
                                            pr = authPr;
                                            break;
                                        }
                                    }
                                }
                            }
                            if (pr.ok || pr.authRequired) {
                                synchronized (found) {
                                    found.add(pr);
                                }
                                callback.onFound(pr);
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            try {
                latch.await(9, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            pool.shutdownNow();
            callback.onFinished(found, null);
        }, "dsh-lan-scan").start();
    }
}
