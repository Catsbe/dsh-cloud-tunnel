package com.deepseek.dshcloud;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.http.SslCertificate;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.security.KeyChain;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ClientCertRequest;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

public class MainActivity extends Activity {

    private static final int REQ_PICK_P12 = 1001;
    private static final int REQ_PICK_SERVER_CA = 1002;
    private static final int REQ_WEB_FILE_CHOOSER = 1003;
    private static final int REQ_CAMERA_PERMISSION = 1004;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean probingInFlight = new AtomicBoolean(false);
    private final AtomicInteger listProbeGen = new AtomicInteger(0);
    private final AtomicBoolean lanScanInFlight = new AtomicBoolean(false);

    private List<TunnelConfig> boundProfiles = new ArrayList<>();
    private final Map<String, RouteProbeEngine.MachineProbeStatus> profileStatuses = new LinkedHashMap<>();
    private final List<RouteProbeEngine.ProbeResult> scannedLanResults = new ArrayList<>();
    private TunnelConfig activeConfig;
    private String lastWebViewProfileId = "";

    private FrameLayout rootLayout;
    private WebView webView;
    private View statusCardOverlay;
    private TextView tvStatusTitle;
    private TextView tvStatusDetails;
    private ProgressBar pbConnecting;
    private Button btnRetryConnect;
    private Button btnOpenConfigFromStatus;
    private Button btnBackToListFromStatus;

    private LinearLayout routePill;
    private View pillDot;
    private TextView tvPillText;

    private LinearLayout deviceCenterOverlay;
    private TextView tvDeviceSummary;
    private LinearLayout boundListContainer;
    private LinearLayout discoveredSection;
    private TextView tvLanScanStatus;
    private LinearLayout discoveredListContainer;
    private Button btnScanLanDevices;
    private Button btnRefreshAll;
    private boolean isDeviceCenterVisible = true;

    private ConfigOverlayHelper configHelper;
    private View configOverlayView;
    private boolean isConfigOpen = false;
    private String pendingCertTargetProfileId = "";

    private volatile String activeBaseUrl = "";
    private volatile boolean activeIsLan = false;
    private volatile long activeLatencyMs = 0;
    private volatile boolean lanUpgradeHintAvailable = false;
    private volatile long lastRouteSwitchAt = 0;
    private volatile boolean mainFrameErrorShown = false;
    private long lastBackPressAt = 0;

    private Uri pendingP12Uri;
    private ValueCallback<Uri[]> fileUploadCallback;
    private PermissionRequest pendingWebPermissionRequest;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final Runnable debouncedNetworkCheck = this::onNetworkChangedSettled;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        reloadBoundProfiles();
        buildUi();
        setupWebView();
        registerNetworkWatcher();

        // Always start at Device Center so user sees which machines are reachable and chooses manually
        showDeviceCenter(true);
        handleIncomingPairingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingPairingIntent(intent);
    }

    private void reloadBoundProfiles() {
        boundProfiles = TunnelConfig.loadAll(this);
        if (activeConfig != null) {
            boolean found = false;
            for (TunnelConfig p : boundProfiles) {
                if (p.ensureId().equals(activeConfig.id)) {
                    activeConfig = p;
                    found = true;
                    break;
                }
            }
            if (!found) activeConfig = null;
        }
    }

    private void handleIncomingPairingIntent(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        Uri data = intent.getData();
        if ("dsh-tunnel".equalsIgnoreCase(data.getScheme())) {
            try {
                TunnelConfig.PairingParseResult parsed = TunnelConfig.parsePairingCode(data.toString());
                openConfigOverlay(TunnelConfig.newEmpty(), true);
                configHelper.applyParsedPairing(parsed);
            } catch (Exception e) {
                toast("导入配对链接失败: " + e.getMessage());
            }
        }
    }

    private void buildUi() {
        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.parseColor("#0F172A"));
        rootLayout.setFitsSystemWindows(true);

        // 1. Fullscreen WebView
        webView = new WebView(this);
        rootLayout.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // 2. Status / Error Overlay Card
        statusCardOverlay = buildStatusOverlay();
        statusCardOverlay.setVisibility(View.GONE);
        rootLayout.addView(statusCardOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // 3. Draggable Floating Route Pill
        routePill = buildFloatingPill();
        routePill.setVisibility(View.GONE);
        FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        pillLp.gravity = Gravity.TOP | Gravity.END;
        pillLp.topMargin = dp(10);
        pillLp.rightMargin = dp(12);
        rootLayout.addView(routePill, pillLp);

        rootLayout.post(this::restorePillPosition);

        // 4. Device Center Home Overlay (Multi-machine selector & live status)
        deviceCenterOverlay = buildDeviceCenterOverlay();
        rootLayout.addView(deviceCenterOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // 5. Setup / Config Overlay
        configHelper = new ConfigOverlayHelper(this, new ConfigOverlayHelper.HostActions() {
            @Override
            public void onPickP12File(String targetProfileId) {
                pendingCertTargetProfileId = targetProfileId;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "application/x-pkcs12", "application/pkcs12", "application/octet-stream"
                });
                startActivityForResult(Intent.createChooser(intent, "选择 .p12 客户端证书"), REQ_PICK_P12);
            }

            @Override
            public void onPickServerCaFile(String targetProfileId) {
                pendingCertTargetProfileId = targetProfileId;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                startActivityForResult(Intent.createChooser(intent, "选择服务端 CA 证书 (.crt/.pem)"), REQ_PICK_SERVER_CA);
            }

            @Override
            public void onRequestSystemCert(ConfigOverlayHelper.KeyChainAliasCallback cb) {
                String currentAlias = activeConfig != null ? activeConfig.systemCertAlias : "";
                KeyChain.choosePrivateKeyAlias(MainActivity.this, alias -> {
                    mainHandler.post(() -> {
                        if (alias != null && !alias.isEmpty()) {
                            WebView.clearClientCertPreferences(() -> cb.onAliasPicked(alias));
                        } else {
                            toast("未选择系统证书");
                        }
                    });
                }, new String[]{"RSA", "EC"}, null, null, -1, currentAlias);
            }

            @Override
            public void onConfigSavedToList(TunnelConfig updatedConfig) {
                reloadBoundProfiles();
                closeConfigOverlay();
                showDeviceCenter(true);
                toast("已保存「" + updatedConfig.profileName + "」到设备列表");
            }

            @Override
            public void onConfigSavedAndConnect(TunnelConfig updatedConfig) {
                reloadBoundProfiles();
                closeConfigOverlay();
                connectToProfile(updatedConfig, null);
            }

            @Override
            public void onProfileDeleted(String profileId) {
                TunnelConfig.deleteProfile(MainActivity.this, profileId);
                if (activeConfig != null && profileId.equals(activeConfig.id)) {
                    activeConfig = null;
                    activeBaseUrl = "";
                    webView.loadUrl("about:blank");
                }
                profileStatuses.remove(profileId);
                reloadBoundProfiles();
                closeConfigOverlay();
                showDeviceCenter(true);
                toast("已删除电脑配置");
            }

            @Override
            public void onCloseRequested() {
                closeConfigOverlay();
            }
        });
        TunnelConfig initialDraft = boundProfiles.isEmpty() ? TunnelConfig.newEmpty() : boundProfiles.get(0);
        configOverlayView = configHelper.buildView(initialDraft);
        configOverlayView.setVisibility(View.GONE);
        rootLayout.addView(configOverlayView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(rootLayout);
    }

    private LinearLayout buildDeviceCenterOverlay() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0B0F19"));
        root.setClickable(true);
        root.setPadding(dp(16), dp(18), dp(16), dp(14));

        TextView title = new TextView(this);
        title.setText("DSH 云端直连 · 设备中心");
        title.setTextColor(Color.parseColor("#F8FAFC"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        tvDeviceSummary = new TextView(this);
        tvDeviceSummary.setText("正在准备设备列表...");
        tvDeviceSummary.setTextColor(Color.parseColor("#38BDF8"));
        tvDeviceSummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvDeviceSummary.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams sumLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sumLp.topMargin = dp(6);
        sumLp.bottomMargin = dp(12);
        root.addView(tvDeviceSummary, sumLp);

        // Primary Add button
        Button btnAdd = makeStyledBtn("➕ 绑定新电脑 (粘贴配对码 / 手动配置)", "#059669", "#FFFFFF");
        btnAdd.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        btnAdd.setTypeface(Typeface.DEFAULT_BOLD);
        btnAdd.setOnClickListener(v -> openConfigOverlay(TunnelConfig.newEmpty(), true));
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        addLp.bottomMargin = dp(10);
        root.addView(btnAdd, addLp);

        // Refresh & LAN Scan Row
        LinearLayout toolsRow = new LinearLayout(this);
        toolsRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        trLp.bottomMargin = dp(12);

        btnRefreshAll = makeStyledBtn("🔄 刷新在线状态", "#2563EB", "#FFFFFF");
        btnRefreshAll.setOnClickListener(v -> probeAllBoundProfiles());
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        rLp.rightMargin = dp(8);
        toolsRow.addView(btnRefreshAll, rLp);

        btnScanLanDevices = makeStyledBtn("🔍 扫描局域网", "#0284C7", "#FFFFFF");
        btnScanLanDevices.setOnClickListener(v -> runDeviceCenterLanScan());
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        toolsRow.addView(btnScanLanDevices, sLp);
        root.addView(toolsRow, trLp);

        // Scrollable Lists
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout scrollBody = new LinearLayout(this);
        scrollBody.setOrientation(LinearLayout.VERTICAL);

        boundListContainer = new LinearLayout(this);
        boundListContainer.setOrientation(LinearLayout.VERTICAL);
        scrollBody.addView(boundListContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        discoveredSection = new LinearLayout(this);
        discoveredSection.setOrientation(LinearLayout.VERTICAL);
        discoveredSection.setVisibility(View.GONE);
        LinearLayout.LayoutParams dsLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dsLp.topMargin = dp(12);

        TextView discTitle = new TextView(this);
        discTitle.setText("📡 局域网扫描发现");
        discTitle.setTextColor(Color.parseColor("#E2E8F0"));
        discTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        discTitle.setTypeface(Typeface.DEFAULT_BOLD);
        discoveredSection.addView(discTitle);

        tvLanScanStatus = new TextView(this);
        tvLanScanStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvLanScanStatus.setTextColor(Color.parseColor("#94A3B8"));
        tvLanScanStatus.setPadding(0, dp(4), 0, dp(8));
        discoveredSection.addView(tvLanScanStatus);

        discoveredListContainer = new LinearLayout(this);
        discoveredListContainer.setOrientation(LinearLayout.VERTICAL);
        discoveredSection.addView(discoveredListContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scrollBody.addView(discoveredSection, dsLp);
        scroll.addView(scrollBody, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return root;
    }

    private void showDeviceCenter(boolean triggerProbe) {
        isDeviceCenterVisible = true;
        deviceCenterOverlay.setVisibility(View.VISIBLE);
        statusCardOverlay.setVisibility(View.GONE);
        routePill.setVisibility(View.GONE);
        renderDeviceCenterCards();
        if (triggerProbe) {
            probeAllBoundProfiles();
        }
    }

    private void hideDeviceCenter() {
        isDeviceCenterVisible = false;
        deviceCenterOverlay.setVisibility(View.GONE);
        if (!isConfigOpen) {
            routePill.setVisibility(View.VISIBLE);
        }
    }

    private void probeAllBoundProfiles() {
        final int gen = listProbeGen.incrementAndGet();
        if (boundProfiles.isEmpty()) {
            renderDeviceCenterCards();
            return;
        }
        for (TunnelConfig p : boundProfiles) {
            RouteProbeEngine.MachineProbeStatus st = new RouteProbeEngine.MachineProbeStatus();
            st.profileId = p.ensureId();
            st.probing = true;
            profileStatuses.put(st.profileId, st);
        }
        renderDeviceCenterCards();
        if (btnRefreshAll != null) {
            btnRefreshAll.setEnabled(false);
            btnRefreshAll.setText("探测中...");
        }

        final List<TunnelConfig> snapshot = new ArrayList<>(boundProfiles);
        new Thread(() -> {
            ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, Math.max(1, snapshot.size())));
            final AtomicInteger remaining = new AtomicInteger(snapshot.size());
            for (TunnelConfig p : snapshot) {
                pool.submit(() -> {
                    try {
                        RouteProbeEngine.MachineProbeStatus st = RouteProbeEngine.probeProfileAllTracks(MainActivity.this, p);
                        mainHandler.post(() -> {
                            if (gen != listProbeGen.get()) return;
                            profileStatuses.put(p.ensureId(), st);
                            renderDeviceCenterCards();
                        });
                    } finally {
                        if (remaining.decrementAndGet() == 0) {
                            mainHandler.post(() -> {
                                if (gen != listProbeGen.get()) return;
                                if (btnRefreshAll != null) {
                                    btnRefreshAll.setEnabled(true);
                                    btnRefreshAll.setText("🔄 刷新在线状态");
                                }
                                renderDeviceCenterCards();
                            });
                        }
                    }
                });
            }
            pool.shutdown();
        }, "dsh-probe-all").start();
    }

    private void runDeviceCenterLanScan() {
        if (!lanScanInFlight.compareAndSet(false, true)) {
            return;
        }
        discoveredSection.setVisibility(View.VISIBLE);
        tvLanScanStatus.setTextColor(Color.parseColor("#38BDF8"));
        tvLanScanStatus.setText("正在扫描当前 Wi-Fi 网段 (端口 58000)...");
        scannedLanResults.clear();
        renderDiscoveredLanCards();
        btnScanLanDevices.setEnabled(false);
        btnScanLanDevices.setText("扫描中...");

        RouteProbeEngine.scanLocalSubnetWithProfiles(this, boundProfiles, 58000, new RouteProbeEngine.ScanCallback() {
            @Override
            public void onFound(RouteProbeEngine.ProbeResult item) {
                mainHandler.post(() -> {
                    scannedLanResults.add(item);
                    renderDiscoveredLanCards();
                });
            }

            @Override
            public void onFinished(List<RouteProbeEngine.ProbeResult> allFound, String errorMsg) {
                mainHandler.post(() -> {
                    lanScanInFlight.set(false);
                    btnScanLanDevices.setEnabled(true);
                    btnScanLanDevices.setText("🔍 扫描局域网");
                    if (errorMsg != null && !errorMsg.isEmpty()) {
                        tvLanScanStatus.setTextColor(Color.parseColor("#FCA5A5"));
                        tvLanScanStatus.setText("❌ " + errorMsg);
                        return;
                    }
                    if (allFound.isEmpty()) {
                        tvLanScanStatus.setTextColor(Color.parseColor("#FCD34D"));
                        tvLanScanStatus.setText("未在当前 Wi-Fi 扫到开启「允许同 Wi-Fi 局域网直连」的电脑（若仅开公网隧道，请直接点击上方电脑卡片的公网连接）");
                    } else {
                        tvLanScanStatus.setTextColor(Color.parseColor("#34D399"));
                        tvLanScanStatus.setText("共发现 " + allFound.size() + " 台局域网在线主机，请手动选择要连接的设备：");
                    }
                    scannedLanResults.clear();
                    scannedLanResults.addAll(allFound);
                    renderDiscoveredLanCards();
                });
            }
        });
    }

    private void renderDeviceCenterCards() {
        if (boundListContainer == null) return;
        boundListContainer.removeAllViews();

        if (boundProfiles.isEmpty()) {
            tvDeviceSummary.setText("尚未绑定任何电脑。请点击「绑定新电脑」粘贴配对码，或点击「扫描局域网」发现主机。");
            LinearLayout emptyCard = makeCardContainer("#111827", "#334155");
            TextView tv = new TextView(this);
            tv.setText("暂无已绑定电脑\n\n1. 在电脑端 DSH 左下角打开「云服务器远程穿透」\n2. 点击「复制配置码」发送或复制到手机\n3. 点击上方「➕ 绑定新电脑」导入即可");
            tv.setTextColor(Color.parseColor("#94A3B8"));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tv.setLineSpacing(dp(4), 1f);
            emptyCard.addView(tv);
            boundListContainer.addView(emptyCard);
            return;
        }

        int lanReady = 0;
        int wanReady = 0;
        int probingCount = 0;
        for (TunnelConfig p : boundProfiles) {
            RouteProbeEngine.MachineProbeStatus st = profileStatuses.get(p.ensureId());
            if (st == null || st.probing) {
                probingCount++;
            } else {
                if (st.isLanOk()) lanReady++;
                if (st.isWanOk()) wanReady++;
            }
        }
        if (probingCount > 0) {
            tvDeviceSummary.setText("共绑定 " + boundProfiles.size() + " 台电脑 · 正在探测在线状态 (" + probingCount + " 台检测中)...");
        } else {
            tvDeviceSummary.setText("共绑定 " + boundProfiles.size() + " 台电脑 · 局域网可达 " + lanReady + " 台 · 公网可达 " + wanReady + " 台（请手动选择连接）");
        }

        for (TunnelConfig p : boundProfiles) {
            RouteProbeEngine.MachineProbeStatus st = profileStatuses.get(p.ensureId());
            boolean isOnline = st != null && st.anyReachable();
            boolean isProbing = st == null || st.probing;
            LinearLayout card = makeCardContainer(isOnline ? "#132238" : "#1E293B", isOnline ? "#10B981" : "#334155");

            // Title row
            LinearLayout topRow = new LinearLayout(this);
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL);

            View dot = new View(this);
            GradientDrawable dotGd = new GradientDrawable();
            dotGd.setShape(GradientDrawable.OVAL);
            dotGd.setColor(Color.parseColor(isProbing ? "#F59E0B" : (isOnline ? "#10B981" : "#EF4444")));
            dot.setBackground(dotGd);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(10), dp(10));
            dotLp.rightMargin = dp(8);
            topRow.addView(dot, dotLp);

            TextView tvName = new TextView(this);
            tvName.setText(p.profileName);
            tvName.setTextColor(Color.parseColor("#F8FAFC"));
            tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            tvName.setTypeface(Typeface.DEFAULT_BOLD);
            topRow.addView(tvName, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button btnEdit = makeSmallPillBtn("⚙️ 设置", "#334155", "#E2E8F0");
            btnEdit.setOnClickListener(v -> openConfigOverlay(p, false));
            topRow.addView(btnEdit);
            card.addView(topRow);

            // Status details
            TextView tvInfo = new TextView(this);
            tvInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tvInfo.setLineSpacing(dp(3), 1f);
            tvInfo.setPadding(0, dp(8), 0, dp(10));
            StringBuilder sb = new StringBuilder();
            if (isProbing) {
                tvInfo.setTextColor(Color.parseColor("#FCD34D"));
                sb.append("⏳ 正在探测局域网与公网 mTLS 可达性...");
            } else {
                tvInfo.setTextColor(Color.parseColor("#CBD5E1"));
                sb.append("📶 局域网: ");
                if (st.lanResult != null && st.lanResult.ok) {
                    sb.append("✅ 可直连 (").append(st.lanResult.latencyMs).append("ms) · ").append(st.lanResult.url);
                } else {
                    sb.append(st.lanResult != null ? st.lanResult.errorDetail : "未配置");
                }
                sb.append("\n☁️ 公网 mTLS: ");
                if (st.wanResult != null && st.wanResult.ok) {
                    sb.append("✅ 可连接 (").append(st.wanResult.latencyMs).append("ms) · ").append(st.wanResult.url);
                } else {
                    sb.append(st.wanResult != null ? st.wanResult.errorDetail : "未配置");
                }
            }
            if (p.bridgeToken == null || p.bridgeToken.trim().isEmpty()) {
                sb.append("\n⚠️ 尚未配置 Bridge Token");
            }
            tvInfo.setText(sb.toString());
            card.addView(tvInfo);

            // Manual Connection Buttons
            LinearLayout btnRow = new LinearLayout(this);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);

            boolean lanOk = st != null && st.isLanOk();
            boolean wanOk = st != null && st.isWanOk();

            if (lanOk && wanOk) {
                Button bLan = makeStyledBtn("📶 局域网连接 (" + st.lanResult.latencyMs + "ms)", "#059669", "#FFFFFF");
                bLan.setOnClickListener(v -> connectToProfile(p, TunnelConfig.ROUTE_LAN_ONLY));
                LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, dp(40), 1f);
                lp1.rightMargin = dp(8);
                btnRow.addView(bLan, lp1);

                Button bWan = makeStyledBtn("☁️ 公网连接 (" + st.wanResult.latencyMs + "ms)", "#0284C7", "#FFFFFF");
                bWan.setOnClickListener(v -> connectToProfile(p, TunnelConfig.ROUTE_WAN_ONLY));
                btnRow.addView(bWan, new LinearLayout.LayoutParams(0, dp(40), 1f));
            } else if (lanOk) {
                Button bLan = makeStyledBtn("📶 连接局域网直连 (" + st.lanResult.latencyMs + "ms)", "#059669", "#FFFFFF");
                bLan.setOnClickListener(v -> connectToProfile(p, null));
                btnRow.addView(bLan, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
            } else if (wanOk) {
                Button bWan = makeStyledBtn("☁️ 连接公网 mTLS (" + st.wanResult.latencyMs + "ms)", "#0284C7", "#FFFFFF");
                bWan.setOnClickListener(v -> connectToProfile(p, null));
                btnRow.addView(bWan, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
            } else {
                Button bTry = makeStyledBtn(isProbing ? "⚡ 直接尝试连接" : "🔄 尝试重连此电脑", "#334155", "#E2E8F0");
                bTry.setOnClickListener(v -> connectToProfile(p, null));
                btnRow.addView(bTry, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
            }

            card.addView(btnRow);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.bottomMargin = dp(10);
            boundListContainer.addView(card, cardLp);
        }
    }

    private void renderDiscoveredLanCards() {
        if (discoveredListContainer == null) return;
        discoveredListContainer.removeAllViews();

        for (RouteProbeEngine.ProbeResult item : scannedLanResults) {
            boolean isMatched = item.matchedProfileId != null && !item.matchedProfileId.isEmpty();
            LinearLayout card = makeCardContainer("#111827", isMatched ? "#0284C7" : "#475569");

            TextView title = new TextView(this);
            if (isMatched) {
                title.setText("✅ 已绑定电脑：" + item.matchedProfileName);
                title.setTextColor(Color.parseColor("#38BDF8"));
            } else if (item.ok && item.hostName != null && !item.hostName.isEmpty()) {
                title.setText("💻 发现电脑：" + item.hostName);
                title.setTextColor(Color.parseColor("#34D399"));
            } else {
                title.setText("🔐 发现局域网 DSH 网桥（需 Token 绑定）");
                title.setTextColor(Color.parseColor("#FCD34D"));
            }
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            card.addView(title);

            TextView sub = new TextView(this);
            sub.setText(item.url + "  ·  延迟 " + item.latencyMs + "ms"
                    + (item.publicUrl != null && !item.publicUrl.isEmpty() ? ("\n公网: " + item.publicUrl) : ""));
            sub.setTextColor(Color.parseColor("#94A3B8"));
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            sub.setPadding(0, dp(4), 0, dp(8));
            card.addView(sub);

            if (isMatched) {
                Button btn = makeStyledBtn("⚡ 更新局域网地址并连接", "#0284C7", "#FFFFFF");
                btn.setOnClickListener(v -> {
                    TunnelConfig target = null;
                    for (TunnelConfig p : boundProfiles) {
                        if (item.matchedProfileId.equals(p.id)) {
                            target = p;
                            break;
                        }
                    }
                    if (target != null) {
                        if (target.lanUrlsRaw == null || !target.lanUrlsRaw.contains(item.url)) {
                            target.lanUrlsRaw = (target.lanUrlsRaw == null || target.lanUrlsRaw.trim().isEmpty())
                                    ? item.url
                                    : (item.url + "\n" + target.lanUrlsRaw);
                            target.save(MainActivity.this);
                            reloadBoundProfiles();
                        }
                        connectToProfile(target, TunnelConfig.ROUTE_LAN_ONLY);
                    }
                });
                card.addView(btn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));
            } else {
                Button btn = makeStyledBtn("➕ 绑定这台电脑", "#059669", "#FFFFFF");
                btn.setOnClickListener(v -> {
                    TunnelConfig draft = TunnelConfig.newEmpty();
                    if (item.hostName != null && !item.hostName.isEmpty()) draft.profileName = item.hostName;
                    draft.lanUrlsRaw = item.url;
                    if (item.publicUrl != null && !item.publicUrl.isEmpty()) draft.wanUrl = item.publicUrl;
                    openConfigOverlay(draft, true);
                });
                card.addView(btn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));
            }

            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.bottomMargin = dp(8);
            discoveredListContainer.addView(card, cardLp);
        }
    }

    private void connectToProfile(TunnelConfig profile, String forcedRouteMode) {
        if (profile == null) return;
        final TunnelConfig effectiveProfile = profile;
        boolean switchedProfile = !effectiveProfile.ensureId().equals(lastWebViewProfileId);
        activeConfig = effectiveProfile;

        if (switchedProfile) {
            activeBaseUrl = "";
            webView.loadUrl("about:blank");
            lastWebViewProfileId = effectiveProfile.ensureId();
        }

        hideDeviceCenter();

        Runnable startConnect = () -> new Thread(() -> {
            ClientCertManager.loadActiveIdentity(MainActivity.this, effectiveProfile);
            mainHandler.post(() -> probeAndConnect(true, forcedRouteMode));
        }, "dsh-connect-profile").start();

        if (switchedProfile) {
            WebView.clearClientCertPreferences(startConnect);
        } else {
            startConnect.run();
        }
    }

    private LinearLayout makeCardContainer(String bgHex, String borderHex) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(bgHex));
        gd.setCornerRadius(dp(12));
        gd.setStroke(dp(1), Color.parseColor(borderHex));
        card.setBackground(gd);
        return card;
    }

    private Button makeSmallPillBtn(String txt, String bgHex, String fgHex) {
        Button b = new Button(this);
        b.setText(txt);
        b.setAllCaps(false);
        b.setTextColor(Color.parseColor(fgHex));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        b.setMinHeight(dp(30));
        b.setMinimumHeight(dp(30));
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(bgHex));
        gd.setCornerRadius(dp(14));
        b.setBackground(gd);
        return b;
    }

    private View buildStatusOverlay() {
        FrameLayout wrap = new FrameLayout(this);
        wrap.setBackgroundColor(Color.parseColor("#EE0F172A"));
        wrap.setClickable(true);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(22), dp(20), dp(20));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor("#1E293B"));
        gd.setCornerRadius(dp(16));
        gd.setStroke(dp(1), Color.parseColor("#334155"));
        card.setBackground(gd);

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        pbConnecting = new ProgressBar(this);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(dp(24), dp(24));
        pbLp.rightMargin = dp(12);
        topRow.addView(pbConnecting, pbLp);

        tvStatusTitle = new TextView(this);
        tvStatusTitle.setText("正在探测最优通道...");
        tvStatusTitle.setTextColor(Color.parseColor("#F8FAFC"));
        tvStatusTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        tvStatusTitle.setTypeface(Typeface.DEFAULT_BOLD);
        topRow.addView(tvStatusTitle);
        card.addView(topRow);

        tvStatusDetails = new TextView(this);
        tvStatusDetails.setText("正在并行检测局域网直连与云端 mTLS 通道...");
        tvStatusDetails.setTextColor(Color.parseColor("#CBD5E1"));
        tvStatusDetails.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvStatusDetails.setLineSpacing(dp(4), 1f);
        tvStatusDetails.setPadding(0, dp(12), 0, dp(16));
        card.addView(tvStatusDetails);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        btnRetryConnect = makeStyledBtn("🔄 重新探测连接", "#2563EB", "#FFFFFF");
        btnRetryConnect.setOnClickListener(v -> probeAndConnect(true, null));
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        b1.rightMargin = dp(8);
        btnRow.addView(btnRetryConnect, b1);

        btnOpenConfigFromStatus = makeStyledBtn("⚙️ 通道与证书设置", "#334155", "#F8FAFC");
        btnOpenConfigFromStatus.setOnClickListener(v -> {
            if (activeConfig != null) {
                openConfigOverlay(activeConfig, false);
            } else {
                showDeviceCenter(true);
            }
        });
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnRow.addView(btnOpenConfigFromStatus, b2);
        card.addView(btnRow);

        btnBackToListFromStatus = makeStyledBtn("← 返回设备中心选择其他电脑", "#0F172A", "#38BDF8");
        btnBackToListFromStatus.setOnClickListener(v -> showDeviceCenter(true));
        LinearLayout.LayoutParams b3 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        b3.topMargin = dp(10);
        card.addView(btnBackToListFromStatus, b3);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        cardLp.leftMargin = dp(20);
        cardLp.rightMargin = dp(20);
        wrap.addView(card, cardLp);
        return wrap;
    }

    @SuppressLint("ClickableViewAccessibility")
    private LinearLayout buildFloatingPill() {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(10), dp(6), dp(10), dp(6));

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor("#DC0F172A"));
        gd.setCornerRadius(dp(20));
        gd.setStroke(dp(1), Color.parseColor("#475569"));
        pill.setBackground(gd);
        pill.setElevation(dp(6));

        pillDot = new View(this);
        GradientDrawable dotGd = new GradientDrawable();
        dotGd.setShape(GradientDrawable.OVAL);
        dotGd.setColor(Color.parseColor("#F59E0B"));
        pillDot.setBackground(dotGd);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.rightMargin = dp(6);
        pill.addView(pillDot, dotLp);

        tvPillText = new TextView(this);
        tvPillText.setText("探测中");
        tvPillText.setTextColor(Color.parseColor("#F8FAFC"));
        tvPillText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvPillText.setTypeface(Typeface.DEFAULT_BOLD);
        pill.addView(tvPillText);

        // Smooth drag + tap detection
        final float[] downPos = new float[4]; // rawX, rawY, viewX, viewY
        final boolean[] moved = new boolean[1];
        pill.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downPos[0] = ev.getRawX();
                    downPos[1] = ev.getRawY();
                    downPos[2] = v.getX();
                    downPos[3] = v.getY();
                    moved[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = ev.getRawX() - downPos[0];
                    float dy = ev.getRawY() - downPos[1];
                    if (Math.hypot(dx, dy) > dp(6)) {
                        moved[0] = true;
                    }
                    if (moved[0]) {
                        float maxX = Math.max(0, rootLayout.getWidth() - v.getWidth());
                        float maxY = Math.max(0, rootLayout.getHeight() - v.getHeight());
                        float nx = Math.max(0, Math.min(maxX, downPos[2] + dx));
                        float ny = Math.max(0, Math.min(maxY, downPos[3] + dy));
                        v.setX(nx);
                        v.setY(ny);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved[0]) {
                        showQuickRouteSheet();
                    } else {
                        savePillPosition();
                    }
                    return true;
                default:
                    return false;
            }
        });

        return pill;
    }

    private void savePillPosition() {
        if (rootLayout.getWidth() <= 0 || rootLayout.getHeight() <= 0) return;
        float maxX = Math.max(1f, rootLayout.getWidth() - routePill.getWidth());
        float maxY = Math.max(1f, rootLayout.getHeight() - routePill.getHeight());
        TunnelConfig.savePillRatio(this, routePill.getX() / maxX, routePill.getY() / maxY);
    }

    private void restorePillPosition() {
        float[] ratio = TunnelConfig.loadPillRatio(this);
        if (ratio[0] >= 0f && ratio[1] >= 0f
                && rootLayout.getWidth() > routePill.getWidth()
                && rootLayout.getHeight() > routePill.getHeight()) {
            float maxX = rootLayout.getWidth() - routePill.getWidth();
            float maxY = rootLayout.getHeight() - routePill.getHeight();
            routePill.setX(Math.max(0, Math.min(maxX, ratio[0] * maxX)));
            routePill.setY(Math.max(0, Math.min(maxY, ratio[1] * maxY)));
        }
    }

    private void updatePill(String colorHex, String label) {
        mainHandler.post(() -> {
            GradientDrawable dot = (GradientDrawable) pillDot.getBackground();
            dot.setColor(Color.parseColor(colorHex));
            String prefix = "";
            if (activeConfig != null && activeConfig.profileName != null && !activeConfig.profileName.trim().isEmpty()) {
                String n = activeConfig.profileName.trim();
                if (n.length() > 10) n = n.substring(0, 10) + "…";
                prefix = n + " · ";
            }
            tvPillText.setText(prefix + label);
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setSupportZoom(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setAllowFileAccess(false);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedClientCertRequest(WebView view, ClientCertRequest request) {
                final TunnelConfig cfg = activeConfig;
                new Thread(() -> {
                    if (cfg == null) {
                        request.cancel();
                        return;
                    }
                    String pid = cfg.ensureId();
                    ClientCertManager.CertIdentity id = ClientCertManager.getCachedIdentity(pid);
                    if (id == null && !TunnelConfig.CERT_NONE.equals(cfg.certMode)) {
                        id = ClientCertManager.loadActiveIdentity(MainActivity.this, cfg);
                    }
                    if (id != null && id.isValid()) {
                        request.proceed(id.privateKey, id.chain);
                    } else {
                        request.cancel();
                        mainFrameErrorShown = true;
                        final String err = ClientCertManager.getLastLoadError(pid);
                        mainHandler.post(() -> {
                            showConnectionFailureUi(
                                    "需要 mTLS 客户端证书",
                                    "公网服务器 (" + request.getHost() + ":" + request.getPort() + ") 要求客户端可信证书。\n"
                                            + (err.isEmpty()
                                            ? "请先导入 .p12 证书文件或绑定手机系统证书。"
                                            : err)
                            );
                        });
                    }
                }, "dsh-webview-mtls").start();
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                final TunnelConfig cfg = activeConfig;
                SslCertificate cert = error.getCertificate();
                if (cfg != null && ClientCertManager.matchesTrustedServerCert(MainActivity.this, cfg, cert)) {
                    handler.proceed();
                    return;
                }
                handler.cancel();
                String fp = ClientCertManager.extractServerCertSha256(cert);
                mainHandler.post(() -> new AlertDialog.Builder(MainActivity.this)
                        .setTitle("服务端 HTTPS 证书校验未通过")
                        .setMessage("当前站点证书不受系统默认信任。\n\nSHA-256 指纹:\n"
                                + (fp.isEmpty() ? "(无法提取)" : fp)
                                + "\n\n如果这是你自己部署的自签名内网/公网证书，确认指纹无误后可点击「信任此指纹并继续」。")
                        .setPositiveButton("信任此指纹并继续", (d, w) -> {
                            if (!fp.isEmpty() && cfg != null) {
                                cfg.trustedServerCertSha256 = fp;
                                cfg.save(MainActivity.this);
                                reloadBoundProfiles();
                                probeAndConnect(false, null);
                            }
                        })
                        .setNegativeButton("取消", (d, w) -> {
                            mainFrameErrorShown = true;
                            showConnectionFailureUi("HTTPS 服务端证书不受信任", "SHA-256: " + fp);
                        })
                        .show());
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                mainFrameErrorShown = false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (!mainFrameErrorShown && url != null && !url.startsWith("about:")
                        && !activeBaseUrl.isEmpty() && url.startsWith(activeBaseUrl)) {
                    statusCardOverlay.setVisibility(View.GONE);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request != null && request.isForMainFrame()) {
                    mainFrameErrorShown = true;
                    CharSequence desc = error != null ? error.getDescription() : "网络连接中断";
                    mainHandler.post(() -> handleMainFrameTransportFailure(String.valueOf(desc)));
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                super.onReceivedHttpError(view, request, errorResponse);
                if (request != null && request.isForMainFrame() && errorResponse != null) {
                    int code = errorResponse.getStatusCode();
                    if (code == 400) {
                        mainFrameErrorShown = true;
                        mainHandler.post(() -> showConnectionFailureUi(
                                "mTLS 证书校验被服务器拒绝 (HTTP 400)",
                                "请检查是否在设置中导入了正确的 .p12 客户端证书，或密码是否正确。"
                        ));
                    } else if (code == 502 || code == 503 || code == 504) {
                        mainFrameErrorShown = true;
                        mainHandler.post(() -> showConnectionFailureUi(
                                "云服务器已连通，但电脑端不在线 (HTTP " + code + ")",
                                "你的手机已通过云服务器 mTLS 安全验证，但当前电脑端 DeepSeek Harness 尚未开启隧道或已关机。"
                        ));
                    }
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request == null || request.getUrl() == null) return false;
                Uri uri = request.getUrl();
                String s = uri.toString();
                if (s.startsWith("dsh-tunnel://")) {
                    try {
                        TunnelConfig.PairingParseResult parsed = TunnelConfig.parsePairingCode(s);
                        openConfigOverlay(TunnelConfig.newEmpty(), true);
                        configHelper.applyParsedPairing(parsed);
                    } catch (Exception e) {
                        toast("配对码解析失败: " + e.getMessage());
                    }
                    return true;
                }
                if (!activeBaseUrl.isEmpty() && s.startsWith(activeBaseUrl)) {
                    return false;
                }
                if (activeConfig != null) {
                    for (String lu : activeConfig.getNormalizedLanUrls()) {
                        if (s.startsWith(lu)) return false;
                    }
                    String wan = activeConfig.getNormalizedWanUrl();
                    if (!wan.isEmpty() && s.startsWith(wan)) {
                        return false;
                    }
                }
                if ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } catch (Exception ignored) {}
                    return true;
                }
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (fileUploadCallback != null) {
                    fileUploadCallback.onReceiveValue(null);
                }
                fileUploadCallback = filePathCallback;
                try {
                    Intent intent = fileChooserParams != null ? fileChooserParams.createIntent() : new Intent(Intent.ACTION_GET_CONTENT);
                    startActivityForResult(intent, REQ_WEB_FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    fileUploadCallback = null;
                    return false;
                }
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                mainHandler.post(() -> {
                    String[] resources = request.getResources();
                    boolean needsCam = false;
                    for (String r : resources) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) {
                            needsCam = true;
                        }
                    }
                    if (needsCam && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        pendingWebPermissionRequest = request;
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERMISSION);
                    } else {
                        request.grant(resources);
                    }
                });
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
                downloadWithMtls(url, contentDisposition, mimetype);
            }
        });
    }

    private void downloadWithMtls(String urlStr, String contentDisposition, String mimetype) {
        String fileName = URLUtil.guessFileName(urlStr, contentDisposition, mimetype);
        toast("开始下载: " + fileName);
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL u = new URL(urlStr);
                conn = (HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);
                String cookies = CookieManager.getInstance().getCookie(urlStr);
                if (cookies != null && !cookies.isEmpty()) {
                    conn.setRequestProperty("Cookie", cookies);
                }
                if (activeConfig != null && activeConfig.bridgeToken != null
                        && !activeConfig.bridgeToken.trim().isEmpty()
                        && !activeBaseUrl.isEmpty() && urlStr.startsWith(activeBaseUrl)) {
                    conn.setRequestProperty("X-DSH-Bridge-Token", activeConfig.bridgeToken.trim());
                }
                if (conn instanceof HttpsURLConnection) {
                    SSLContext sslCtx = ClientCertManager.buildMtlsSslContext(this, activeConfig);
                    ((HttpsURLConnection) conn).setSSLSocketFactory(sslCtx.getSocketFactory());
                }
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new RuntimeException("HTTP " + code);
                }
                try (InputStream is = conn.getInputStream()) {
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues cv = new ContentValues();
                        cv.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                        if (mimetype != null && !mimetype.isEmpty()) {
                            cv.put(MediaStore.Downloads.MIME_TYPE, mimetype);
                        }
                        cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                        Uri outUri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                        if (outUri == null) throw new RuntimeException("无法创建下载文件");
                        try (OutputStream os = getContentResolver().openOutputStream(outUri)) {
                            copyStream(is, os);
                        }
                        mainHandler.post(() -> toast("已保存到系统下载目录: " + fileName));
                    } else {
                        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                        if (dir != null && !dir.exists()) dir.mkdirs();
                        File outFile = new File(dir, fileName);
                        try (FileOutputStream fos = new FileOutputStream(outFile)) {
                            copyStream(is, fos);
                        }
                        mainHandler.post(() -> toast("下载完成: " + outFile.getAbsolutePath()));
                    }
                }
            } catch (Exception e) {
                mainHandler.post(() -> toast("下载失败: " + e.getMessage()));
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "dsh-download").start();
    }

    private static void copyStream(InputStream is, OutputStream os) throws Exception {
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            os.write(buf, 0, n);
        }
        os.flush();
    }

    private void probeAndConnect(boolean showLoadingOverlay, String forcedRouteMode) {
        final TunnelConfig cfg = activeConfig;
        if (cfg == null || !cfg.hasAnyEndpoint()) {
            showDeviceCenter(true);
            return;
        }
        if (!probingInFlight.compareAndSet(false, true)) {
            return;
        }
        updatePill("#F59E0B", "探测中");
        if (showLoadingOverlay) {
            pbConnecting.setVisibility(View.VISIBLE);
            tvStatusTitle.setText("正在连接「" + cfg.profileName + "」...");
            tvStatusDetails.setText("正在检测可用通道与网桥鉴权...");
            statusCardOverlay.setVisibility(View.VISIBLE);
        }

        final TunnelConfig probeCfg;
        if (forcedRouteMode != null && !forcedRouteMode.isEmpty()) {
            probeCfg = TunnelConfig.fromJson(cfg.toJson());
            probeCfg.routeMode = forcedRouteMode;
        } else {
            probeCfg = cfg;
        }

        new Thread(() -> {
            try {
                RouteProbeEngine.DualProbeDecision decision = RouteProbeEngine.probeBestRoute(MainActivity.this, probeCfg);
                final List<String> bridgeCookies = (decision.chosen != null && decision.chosen.ok)
                        ? RouteProbeEngine.authenticateBridgeSession(MainActivity.this, probeCfg, decision.chosen.url, decision.chosenIsLan)
                        : null;
                mainHandler.post(() -> {
                    if (isDeviceCenterVisible || activeConfig == null || !cfg.ensureId().equals(activeConfig.ensureId())) {
                        return;
                    }
                    if (decision.chosen != null && decision.chosen.ok) {
                        switchWebViewToRoute(decision.chosen.url, decision.chosenIsLan, decision.chosen.latencyMs, false, bridgeCookies);
                    } else {
                        showDualDecisionFailure(decision);
                    }
                });
            } finally {
                probingInFlight.set(false);
            }
        }, "dsh-probe-connect").start();
    }

    private void switchWebViewToRoute(String targetBaseUrl, boolean isLan, long latencyMs, boolean announceToast, List<String> bridgeCookies) {
        String currentSuffix = extractCurrentPathAndQuery();
        boolean sameOrigin = targetBaseUrl.equals(activeBaseUrl) && webView.getUrl() != null && webView.getUrl().startsWith(targetBaseUrl);

        activeBaseUrl = targetBaseUrl;
        activeIsLan = isLan;
        activeLatencyMs = latencyMs;
        lanUpgradeHintAvailable = false;
        lastRouteSwitchAt = System.currentTimeMillis();

        if (isLan) {
            updatePill("#10B981", "局域网 " + latencyMs + "ms");
        } else {
            updatePill("#38BDF8", "云端 " + latencyMs + "ms");
        }

        statusCardOverlay.setVisibility(View.GONE);

        if (bridgeCookies != null && !bridgeCookies.isEmpty()) {
            CookieManager cm = CookieManager.getInstance();
            for (String c : bridgeCookies) {
                if (c != null && !c.isEmpty()) {
                    cm.setCookie(targetBaseUrl, c);
                }
            }
            cm.flush();
        }

        if (!sameOrigin) {
            mainFrameErrorShown = false;
            String fullUrl = targetBaseUrl + (currentSuffix.isEmpty() ? "/" : currentSuffix);
            loadBridgeUrl(fullUrl);
            if (announceToast) {
                toast(isLan
                        ? ("⚡ 已秒切至局域网直连 (" + latencyMs + "ms)")
                        : ("☁️ 已切换至云端 mTLS 转发 (" + latencyMs + "ms)"));
            }
        }
    }

    private void loadBridgeUrl(String fullUrl) {
        if (activeConfig != null && activeConfig.bridgeToken != null && !activeConfig.bridgeToken.trim().isEmpty()) {
            Map<String, String> headers = new HashMap<>();
            headers.put("X-DSH-Bridge-Token", activeConfig.bridgeToken.trim());
            webView.loadUrl(fullUrl, headers);
        } else {
            webView.loadUrl(fullUrl);
        }
    }

    private String extractCurrentPathAndQuery() {
        String cur = webView.getUrl();
        if (cur == null || cur.isEmpty() || cur.startsWith("about:")) return "/";
        try {
            Uri u = Uri.parse(cur);
            StringBuilder sb = new StringBuilder();
            String path = u.getEncodedPath();
            sb.append((path == null || path.isEmpty()) ? "/" : path);
            if (u.getEncodedQuery() != null && !u.getEncodedQuery().isEmpty()) {
                sb.append("?").append(u.getEncodedQuery());
            }
            if (u.getEncodedFragment() != null && !u.getEncodedFragment().isEmpty()) {
                sb.append("#").append(u.getEncodedFragment());
            }
            return sb.toString();
        } catch (Exception e) {
            return "/";
        }
    }

    private void handleMainFrameTransportFailure(String errorDesc) {
        if (System.currentTimeMillis() - lastRouteSwitchAt < 1200) {
            return;
        }
        // If we were on LAN and auto mode is enabled, try seamless failover to WAN
        if (activeIsLan && activeConfig != null
                && TunnelConfig.ROUTE_AUTO.equals(activeConfig.routeMode)
                && !activeConfig.getNormalizedWanUrl().isEmpty()) {
            toast("局域网连接中断，正在秒切云端转发...");
            probeAndConnect(true, null);
        } else {
            showConnectionFailureUi("网页加载失败", "原因: " + errorDesc + "\n当前地址: " + activeBaseUrl);
        }
    }

    private void showDualDecisionFailure(RouteProbeEngine.DualProbeDecision decision) {
        StringBuilder sb = new StringBuilder();
        if (decision.lanSummary != null) {
            sb.append("📶 局域网: ")
                    .append(decision.lanSummary.ok ? ("✅ " + decision.lanSummary.latencyMs + "ms") : ("❌ " + decision.lanSummary.errorDetail))
                    .append("\n");
        }
        if (decision.wanSummary != null) {
            sb.append("☁️ 公网 mTLS: ")
                    .append(decision.wanSummary.ok ? ("✅ " + decision.wanSummary.latencyMs + "ms") : ("❌ " + decision.wanSummary.errorDetail));
        }
        showConnectionFailureUi("暂未连接到 DSH 电脑端", sb.toString());
    }

    private void showConnectionFailureUi(String title, String details) {
        updatePill("#EF4444", "未连接");
        pbConnecting.setVisibility(View.GONE);
        tvStatusTitle.setText(title);
        tvStatusDetails.setText(details);
        statusCardOverlay.setVisibility(View.VISIBLE);
    }

    // --- Real-time Network Transitions ---
    private void registerNetworkWatcher() {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                scheduleNetworkCheck();
            }

            @Override
            public void onLost(Network network) {
                scheduleNetworkCheck();
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
                scheduleNetworkCheck();
            }
        };
        try {
            connectivityManager.registerNetworkCallback(new NetworkRequest.Builder().build(), networkCallback);
        } catch (Exception ignored) {
        }
    }

    private void scheduleNetworkCheck() {
        mainHandler.removeCallbacks(debouncedNetworkCheck);
        mainHandler.postDelayed(debouncedNetworkCheck, 650);
    }

    private void onNetworkChangedSettled() {
        if (isConfigOpen) return;
        if (isDeviceCenterVisible) {
            mainHandler.post(this::probeAllBoundProfiles);
            return;
        }
        final TunnelConfig cfg = activeConfig;
        if (cfg == null || !cfg.hasAnyEndpoint()) return;
        if (System.currentTimeMillis() - lastRouteSwitchAt < 1500) return;

        new Thread(() -> {
            RouteProbeEngine.NetworkState net = RouteProbeEngine.inspectNetwork(this);
            if (!net.hasAnyNetwork) return;

            // Case 1: Currently disconnected -> auto reconnect immediately
            if (statusCardOverlay.getVisibility() == View.VISIBLE || activeBaseUrl.isEmpty()) {
                mainHandler.post(() -> probeAndConnect(false, null));
                return;
            }

            if (!TunnelConfig.ROUTE_AUTO.equals(cfg.routeMode)) return;

            // Case 2: Currently on LAN -> verify if LAN is still alive; if not, failover to WAN
            if (activeIsLan) {
                RouteProbeEngine.ProbeResult alive = RouteProbeEngine.probeSingle(this, cfg, activeBaseUrl, true, 500, 500);
                if (!alive.ok) {
                    String wan = cfg.getNormalizedWanUrl();
                    if (!wan.isEmpty()) {
                        RouteProbeEngine.ProbeResult wanOk = RouteProbeEngine.probeSingle(this, cfg, wan, false, 3000, 3000);
                        if (wanOk.ok) {
                            List<String> wanCookies = RouteProbeEngine.authenticateBridgeSession(this, cfg, wanOk.url, false);
                            mainHandler.post(() -> switchWebViewToRoute(wanOk.url, false, wanOk.latencyMs, true, wanCookies));
                        }
                    }
                } else {
                    activeLatencyMs = alive.latencyMs;
                    updatePill("#10B981", "局域网 " + alive.latencyMs + "ms");
                }
                return;
            }

            // Case 3: Currently on WAN & Wi-Fi/Ethernet became available -> probe LAN for fast upgrade
            if (!activeIsLan && net.hasWifiOrEthernet) {
                List<String> lans = cfg.getNormalizedLanUrls();
                for (String lu : lans) {
                    RouteProbeEngine.ProbeResult lanOk = RouteProbeEngine.probeSingle(this, cfg, lu, true, 550, 550);
                    if (lanOk.ok) {
                        if (cfg.autoUpgradeToLan) {
                            List<String> lanCookies = RouteProbeEngine.authenticateBridgeSession(this, cfg, lanOk.url, true);
                            mainHandler.post(() -> switchWebViewToRoute(lanOk.url, true, lanOk.latencyMs, true, lanCookies));
                        } else {
                            lanUpgradeHintAvailable = true;
                            updatePill("#10B981", "可切局域网 (" + lanOk.latencyMs + "ms)");
                        }
                        break;
                    }
                }
            }
        }, "dsh-net-transition").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isConfigOpen) return;
        if (isDeviceCenterVisible) {
            probeAllBoundProfiles();
            return;
        }
        if (activeConfig != null && activeConfig.hasAnyEndpoint() && !activeBaseUrl.isEmpty()) {
            scheduleNetworkCheck();
        }
    }

    @Override
    protected void onDestroy() {
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    // --- Quick Route & Action Sheet ---
    private void showQuickRouteSheet() {
        final TunnelConfig cfg = activeConfig;
        if (cfg == null) {
            showDeviceCenter(true);
            return;
        }
        ClientCertManager.CertIdentity cert = ClientCertManager.getCachedIdentity(cfg.ensureId());
        String routeDesc = activeBaseUrl.isEmpty()
                ? "当前未连接"
                : ((activeIsLan ? "📶 局域网直连" : "☁️ 云端 mTLS") + " (" + activeLatencyMs + "ms)\n地址: " + activeBaseUrl);
        String certDesc = (cert != null && cert.isValid()) ? cert.getSummaryText() : "未加载客户端证书";
        String modeLabel = TunnelConfig.ROUTE_LAN_ONLY.equals(cfg.routeMode) ? "仅局域网"
                : TunnelConfig.ROUTE_WAN_ONLY.equals(cfg.routeMode) ? "仅公网 mTLS" : "智能双轨 (自动)";

        String[] items = new String[]{
                "🖥️ 切换电脑 / 返回设备中心",
                "⚡ 立即双轨测速并切到最优线路",
                "📶 切换策略：智能双轨（自动）",
                "🏠 切换策略：强制局域网直连",
                "☁️ 切换策略：强制公网 mTLS 转发",
                "🔄 刷新当前页面",
                "⏮️ 返回 DSH 会话首页",
                "⚙️ 编辑当前电脑设置与证书"
        };

        TextView header = new TextView(this);
        header.setText("当前电脑: " + cfg.profileName + " (" + modeLabel + ")\n" + routeDesc + "\n" + certDesc);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        header.setPadding(dp(20), dp(16), dp(20), dp(8));
        header.setLineSpacing(dp(3), 1f);

        new AlertDialog.Builder(this)
                .setCustomTitle(header)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            showDeviceCenter(true);
                            break;
                        case 1:
                            probeAndConnect(false, null);
                            break;
                        case 2:
                            cfg.routeMode = TunnelConfig.ROUTE_AUTO;
                            cfg.save(this);
                            reloadBoundProfiles();
                            probeAndConnect(true, null);
                            break;
                        case 3:
                            cfg.routeMode = TunnelConfig.ROUTE_LAN_ONLY;
                            cfg.save(this);
                            reloadBoundProfiles();
                            probeAndConnect(true, null);
                            break;
                        case 4:
                            cfg.routeMode = TunnelConfig.ROUTE_WAN_ONLY;
                            cfg.save(this);
                            reloadBoundProfiles();
                            probeAndConnect(true, null);
                            break;
                        case 5:
                            webView.reload();
                            break;
                        case 6:
                            if (!activeBaseUrl.isEmpty()) {
                                loadBridgeUrl(activeBaseUrl + "/");
                            } else {
                                probeAndConnect(true, null);
                            }
                            break;
                        case 7:
                            openConfigOverlay(cfg, false);
                            break;
                    }
                })
                .setNegativeButton("关闭 (" + (activeIsLan ? "局域网" : "公网") + " · " + (cert != null ? "证书正常" : "无证书") + ")", null)
                .show();
    }

    private void openConfigOverlay(TunnelConfig targetCfg, boolean isNew) {
        isConfigOpen = true;
        routePill.setVisibility(View.GONE);
        configHelper.bindFromDraft(targetCfg, isNew, boundProfiles);
        configOverlayView.setVisibility(View.VISIBLE);
    }

    private void closeConfigOverlay() {
        isConfigOpen = false;
        configOverlayView.setVisibility(View.GONE);
        if (!isDeviceCenterVisible && activeConfig != null) {
            routePill.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_WEB_FILE_CHOOSER) {
            if (fileUploadCallback != null) {
                Uri[] results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
                fileUploadCallback.onReceiveValue(results);
                fileUploadCallback = null;
            }
            return;
        }

        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String targetPid = (pendingCertTargetProfileId != null && !pendingCertTargetProfileId.isEmpty())
                ? pendingCertTargetProfileId
                : configHelper.getDraftProfileId();

        if (requestCode == REQ_PICK_P12) {
            pendingP12Uri = uri;
            String pass = configHelper.getDraftP12Password();
            tryImportP12WithPassword(targetPid, uri, pass, true);
        } else if (requestCode == REQ_PICK_SERVER_CA) {
            new Thread(() -> {
                try {
                    int count = ClientCertManager.importServerCaFromUri(this, targetPid, uri);
                    mainHandler.post(() -> {
                        configHelper.onServerCaUpdated();
                        toast("已导入 " + count + " 个服务端 CA 证书");
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> toast("导入 CA 失败: " + e.getMessage()));
                }
            }).start();
        }
    }

    private void tryImportP12WithPassword(String targetPid, Uri uri, String password, boolean promptOnFailure) {
        new Thread(() -> {
            try {
                ClientCertManager.CertIdentity id = ClientCertManager.importP12FromUri(this, targetPid, uri, password);
                mainHandler.post(() -> {
                    for (TunnelConfig p : boundProfiles) {
                        if (targetPid.equals(p.id)) {
                            p.certMode = TunnelConfig.CERT_P12;
                            p.p12Password = password == null ? "" : password;
                            p.save(MainActivity.this);
                            break;
                        }
                    }
                    WebView.clearClientCertPreferences(() -> configHelper.onP12ImportedSuccess(password, id));
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (promptOnFailure) {
                        promptP12PasswordDialog(targetPid, uri, e.getMessage());
                    } else {
                        toast(e.getMessage());
                    }
                });
            }
        }).start();
    }

    private void promptP12PasswordDialog(String targetPid, Uri uri, String errMsg) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("输入 .p12 证书密码（无密码请直接点确定）");
        input.setText(configHelper.getDraftP12Password());

        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("需要 .p12 证书密码")
                .setMessage(errMsg)
                .setView(wrap)
                .setPositiveButton("解锁并导入", (d, w) ->
                        tryImportP12WithPassword(targetPid, uri, input.getText().toString(), true))
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA_PERMISSION && pendingWebPermissionRequest != null) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pendingWebPermissionRequest.grant(pendingWebPermissionRequest.getResources());
            } else {
                pendingWebPermissionRequest.deny();
            }
            pendingWebPermissionRequest = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (isConfigOpen) {
            closeConfigOverlay();
            return;
        }
        if (!isDeviceCenterVisible) {
            if (webView != null && webView.canGoBack()) {
                webView.goBack();
                return;
            }
            showDeviceCenter(true);
            toast("已返回设备中心，再按一次退出");
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackPressAt < 2000) {
            super.onBackPressed();
        } else {
            lastBackPressAt = now;
            toast("再按一次返回键退出 DSH 云端直连");
        }
    }

    private Button makeStyledBtn(String txt, String bgHex, String fgHex) {
        Button b = new Button(this);
        b.setText(txt);
        b.setAllCaps(false);
        b.setTextColor(Color.parseColor(fgHex));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(bgHex));
        gd.setCornerRadius(dp(8));
        b.setBackground(gd);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
