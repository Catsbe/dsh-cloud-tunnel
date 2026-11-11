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
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

public class MainActivity extends Activity {

    private static final int REQ_PICK_P12 = 1001;
    private static final int REQ_PICK_SERVER_CA = 1002;
    private static final int REQ_WEB_FILE_CHOOSER = 1003;
    private static final int REQ_CAMERA_PERMISSION = 1004;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean probingInFlight = new AtomicBoolean(false);

    private TunnelConfig currentConfig;
    private FrameLayout rootLayout;
    private WebView webView;
    private View statusCardOverlay;
    private TextView tvStatusTitle;
    private TextView tvStatusDetails;
    private ProgressBar pbConnecting;
    private Button btnRetryConnect;
    private Button btnOpenConfigFromStatus;

    private LinearLayout routePill;
    private View pillDot;
    private TextView tvPillText;

    private ConfigOverlayHelper configHelper;
    private View configOverlayView;
    private boolean isConfigOpen = false;

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

        currentConfig = TunnelConfig.load(this);
        buildUi();
        setupWebView();
        registerNetworkWatcher();

        // Preload identity off UI thread, then auto-probe or open setup
        new Thread(() -> {
            ClientCertManager.loadActiveIdentity(this, currentConfig);
            mainHandler.post(() -> {
                handleIncomingPairingIntent(getIntent());
                if (!currentConfig.hasAnyEndpoint()) {
                    openConfigOverlay(true);
                } else if (!isConfigOpen) {
                    probeAndConnect(true);
                }
            });
        }, "dsh-init-cert").start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingPairingIntent(intent);
    }

    private void handleIncomingPairingIntent(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        Uri data = intent.getData();
        if ("dsh-tunnel".equalsIgnoreCase(data.getScheme())) {
            try {
                TunnelConfig.PairingParseResult parsed = TunnelConfig.parsePairingCode(data.toString());
                openConfigOverlay(false);
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
        FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        pillLp.gravity = Gravity.TOP | Gravity.END;
        pillLp.topMargin = dp(10);
        pillLp.rightMargin = dp(12);
        rootLayout.addView(routePill, pillLp);

        rootLayout.post(this::restorePillPosition);

        // 4. Setup / Config Overlay
        configHelper = new ConfigOverlayHelper(this, new ConfigOverlayHelper.HostActions() {
            @Override
            public void onPickP12File() {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "application/x-pkcs12", "application/pkcs12", "application/octet-stream"
                });
                startActivityForResult(Intent.createChooser(intent, "选择 .p12 客户端证书"), REQ_PICK_P12);
            }

            @Override
            public void onPickServerCaFile() {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                startActivityForResult(Intent.createChooser(intent, "选择服务端 CA 证书 (.crt/.pem)"), REQ_PICK_SERVER_CA);
            }

            @Override
            public void onRequestSystemCert(ConfigOverlayHelper.KeyChainAliasCallback cb) {
                KeyChain.choosePrivateKeyAlias(MainActivity.this, alias -> {
                    mainHandler.post(() -> {
                        if (alias != null && !alias.isEmpty()) {
                            WebView.clearClientCertPreferences(() -> cb.onAliasPicked(alias));
                        } else {
                            toast("未选择系统证书");
                        }
                    });
                }, new String[]{"RSA", "EC"}, null, null, -1, currentConfig.systemCertAlias);
            }

            @Override
            public void onConfigSavedAndConnect(TunnelConfig updatedConfig) {
                currentConfig = updatedConfig;
                closeConfigOverlay();
                new Thread(() -> {
                    ClientCertManager.loadActiveIdentity(MainActivity.this, currentConfig);
                    mainHandler.post(() -> WebView.clearClientCertPreferences(() -> probeAndConnect(true)));
                }).start();
            }

            @Override
            public void onCloseRequested() {
                if (currentConfig.hasAnyEndpoint()) {
                    closeConfigOverlay();
                } else {
                    toast("请先完成连接地址配置");
                }
            }
        });
        configOverlayView = configHelper.buildView(currentConfig);
        configOverlayView.setVisibility(View.GONE);
        rootLayout.addView(configOverlayView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(rootLayout);
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
        btnRetryConnect.setOnClickListener(v -> probeAndConnect(true));
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        b1.rightMargin = dp(8);
        btnRow.addView(btnRetryConnect, b1);

        btnOpenConfigFromStatus = makeStyledBtn("⚙️ 通道与证书设置", "#334155", "#F8FAFC");
        btnOpenConfigFromStatus.setOnClickListener(v -> openConfigOverlay(false));
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnRow.addView(btnOpenConfigFromStatus, b2);
        card.addView(btnRow);

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
        currentConfig.pillXRatio = routePill.getX() / maxX;
        currentConfig.pillYRatio = routePill.getY() / maxY;
        currentConfig.save(this);
    }

    private void restorePillPosition() {
        if (currentConfig.pillXRatio >= 0f && currentConfig.pillYRatio >= 0f
                && rootLayout.getWidth() > routePill.getWidth()
                && rootLayout.getHeight() > routePill.getHeight()) {
            float maxX = rootLayout.getWidth() - routePill.getWidth();
            float maxY = rootLayout.getHeight() - routePill.getHeight();
            routePill.setX(Math.max(0, Math.min(maxX, currentConfig.pillXRatio * maxX)));
            routePill.setY(Math.max(0, Math.min(maxY, currentConfig.pillYRatio * maxY)));
        }
    }

    private void updatePill(String colorHex, String label) {
        mainHandler.post(() -> {
            GradientDrawable dot = (GradientDrawable) pillDot.getBackground();
            dot.setColor(Color.parseColor(colorHex));
            tvPillText.setText(label);
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
                new Thread(() -> {
                    ClientCertManager.CertIdentity id = ClientCertManager.getCachedIdentity();
                    if (id == null && !TunnelConfig.CERT_NONE.equals(currentConfig.certMode)) {
                        id = ClientCertManager.loadActiveIdentity(MainActivity.this, currentConfig);
                    }
                    if (id != null && id.isValid()) {
                        request.proceed(id.privateKey, id.chain);
                    } else {
                        request.cancel();
                        mainFrameErrorShown = true;
                        mainHandler.post(() -> {
                            showConnectionFailureUi(
                                    "需要 mTLS 客户端证书",
                                    "公网服务器 (" + request.getHost() + ":" + request.getPort() + ") 要求客户端可信证书。\n"
                                            + (ClientCertManager.getLastLoadError().isEmpty()
                                            ? "请先导入 .p12 证书文件或绑定手机系统证书。"
                                            : ClientCertManager.getLastLoadError())
                            );
                        });
                    }
                }, "dsh-webview-mtls").start();
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                SslCertificate cert = error.getCertificate();
                if (ClientCertManager.matchesTrustedServerCert(MainActivity.this, currentConfig, cert)) {
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
                            if (!fp.isEmpty()) {
                                currentConfig.trustedServerCertSha256 = fp;
                                currentConfig.save(MainActivity.this);
                                probeAndConnect(false);
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
                        openConfigOverlay(false);
                        configHelper.applyParsedPairing(parsed);
                    } catch (Exception e) {
                        toast("配对码解析失败: " + e.getMessage());
                    }
                    return true;
                }
                if (!activeBaseUrl.isEmpty() && s.startsWith(activeBaseUrl)) {
                    return false;
                }
                for (String lu : currentConfig.getNormalizedLanUrls()) {
                    if (s.startsWith(lu)) return false;
                }
                String wan = currentConfig.getNormalizedWanUrl();
                if (!wan.isEmpty() && s.startsWith(wan)) {
                    return false;
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
                if (conn instanceof HttpsURLConnection) {
                    SSLContext sslCtx = ClientCertManager.buildMtlsSslContext(this, currentConfig);
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

    private void probeAndConnect(boolean showLoadingOverlay) {
        if (!currentConfig.hasAnyEndpoint()) {
            openConfigOverlay(true);
            return;
        }
        if (!probingInFlight.compareAndSet(false, true)) {
            return;
        }
        updatePill("#F59E0B", "探测中");
        if (showLoadingOverlay) {
            pbConnecting.setVisibility(View.VISIBLE);
            tvStatusTitle.setText("正在探测最优通道...");
            tvStatusDetails.setText("并行检测局域网直连与云端 mTLS 通道...");
            statusCardOverlay.setVisibility(View.VISIBLE);
        }

        new Thread(() -> {
            try {
                RouteProbeEngine.DualProbeDecision decision = RouteProbeEngine.probeBestRoute(MainActivity.this, currentConfig);
                mainHandler.post(() -> {
                    if (decision.chosen != null && decision.chosen.ok) {
                        switchWebViewToRoute(decision.chosen.url, decision.chosenIsLan, decision.chosen.latencyMs, false);
                    } else {
                        showDualDecisionFailure(decision);
                    }
                });
            } finally {
                probingInFlight.set(false);
            }
        }, "dsh-probe-connect").start();
    }

    private void switchWebViewToRoute(String targetBaseUrl, boolean isLan, long latencyMs, boolean announceToast) {
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

        if (!sameOrigin) {
            mainFrameErrorShown = false;
            String fullUrl = targetBaseUrl + (currentSuffix.isEmpty() ? "/" : currentSuffix);
            webView.loadUrl(fullUrl);
            if (announceToast) {
                toast(isLan
                        ? ("⚡ 已秒切至局域网直连 (" + latencyMs + "ms)")
                        : ("☁️ 已切换至云端 mTLS 转发 (" + latencyMs + "ms)"));
            }
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
        if (activeIsLan && TunnelConfig.ROUTE_AUTO.equals(currentConfig.routeMode) && !currentConfig.getNormalizedWanUrl().isEmpty()) {
            toast("局域网连接中断，正在秒切云端转发...");
            probeAndConnect(true);
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
        if (isConfigOpen || !currentConfig.hasAnyEndpoint()) return;
        if (System.currentTimeMillis() - lastRouteSwitchAt < 1500) return;

        new Thread(() -> {
            RouteProbeEngine.NetworkState net = RouteProbeEngine.inspectNetwork(this);
            if (!net.hasAnyNetwork) return;

            // Case 1: Currently disconnected -> auto reconnect immediately
            if (statusCardOverlay.getVisibility() == View.VISIBLE || activeBaseUrl.isEmpty()) {
                mainHandler.post(() -> probeAndConnect(false));
                return;
            }

            if (!TunnelConfig.ROUTE_AUTO.equals(currentConfig.routeMode)) return;

            // Case 2: Currently on LAN -> verify if LAN is still alive; if not, failover to WAN
            if (activeIsLan) {
                RouteProbeEngine.ProbeResult alive = RouteProbeEngine.probeSingle(this, currentConfig, activeBaseUrl, true, 500, 500);
                if (!alive.ok) {
                    String wan = currentConfig.getNormalizedWanUrl();
                    if (!wan.isEmpty()) {
                        RouteProbeEngine.ProbeResult wanOk = RouteProbeEngine.probeSingle(this, currentConfig, wan, false, 3000, 3000);
                        if (wanOk.ok) {
                            mainHandler.post(() -> switchWebViewToRoute(wanOk.url, false, wanOk.latencyMs, true));
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
                List<String> lans = currentConfig.getNormalizedLanUrls();
                for (String lu : lans) {
                    RouteProbeEngine.ProbeResult lanOk = RouteProbeEngine.probeSingle(this, currentConfig, lu, true, 550, 550);
                    if (lanOk.ok) {
                        if (currentConfig.autoUpgradeToLan) {
                            mainHandler.post(() -> switchWebViewToRoute(lanOk.url, true, lanOk.latencyMs, true));
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
        if (isConfigOpen || !currentConfig.hasAnyEndpoint()) return;
        // Quick resume verification so moving between home & outside recovers instantly
        if (!activeBaseUrl.isEmpty()) {
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
        ClientCertManager.CertIdentity cert = ClientCertManager.getCachedIdentity();
        String routeDesc = activeBaseUrl.isEmpty()
                ? "当前未连接"
                : ((activeIsLan ? "📶 局域网直连" : "☁️ 云端 mTLS") + " (" + activeLatencyMs + "ms)\n地址: " + activeBaseUrl);
        String certDesc = (cert != null && cert.isValid()) ? cert.getSummaryText() : "未加载客户端证书";
        String modeLabel = TunnelConfig.ROUTE_LAN_ONLY.equals(currentConfig.routeMode) ? "仅局域网"
                : TunnelConfig.ROUTE_WAN_ONLY.equals(currentConfig.routeMode) ? "仅公网 mTLS" : "智能双轨 (自动)";

        String[] items = new String[]{
                "⚡ 立即双轨测速并切到最优线路",
                "📶 切换策略：智能双轨（自动）",
                "🏠 切换策略：强制局域网直连",
                "☁️ 切换策略：强制公网 mTLS 转发",
                "🔄 刷新当前页面",
                "⏮️ 返回 DSH 会话首页",
                "⚙️ 完整设置与证书管理"
        };

        TextView header = new TextView(this);
        header.setText("当前通道: " + modeLabel + "\n" + routeDesc + "\n" + certDesc);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        header.setPadding(dp(20), dp(16), dp(20), dp(8));
        header.setLineSpacing(dp(3), 1f);

        new AlertDialog.Builder(this)
                .setCustomTitle(header)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            probeAndConnect(false);
                            break;
                        case 1:
                            currentConfig.routeMode = TunnelConfig.ROUTE_AUTO;
                            currentConfig.save(this);
                            probeAndConnect(true);
                            break;
                        case 2:
                            currentConfig.routeMode = TunnelConfig.ROUTE_LAN_ONLY;
                            currentConfig.save(this);
                            probeAndConnect(true);
                            break;
                        case 3:
                            currentConfig.routeMode = TunnelConfig.ROUTE_WAN_ONLY;
                            currentConfig.save(this);
                            probeAndConnect(true);
                            break;
                        case 4:
                            webView.reload();
                            break;
                        case 5:
                            if (!activeBaseUrl.isEmpty()) {
                                webView.loadUrl(activeBaseUrl + "/");
                            } else {
                                probeAndConnect(true);
                            }
                            break;
                        case 6:
                            openConfigOverlay(false);
                            break;
                    }
                })
                .setNegativeButton("关闭 (" + (activeIsLan ? "局域网" : "公网") + " · " + (cert != null ? "证书正常" : "无证书") + ")", null)
                .show();
    }

    private void openConfigOverlay(boolean firstRun) {
        isConfigOpen = true;
        configHelper.bindFromDraft(currentConfig);
        configOverlayView.setVisibility(View.VISIBLE);
    }

    private void closeConfigOverlay() {
        isConfigOpen = false;
        configOverlayView.setVisibility(View.GONE);
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

        if (requestCode == REQ_PICK_P12) {
            pendingP12Uri = uri;
            String pass = configHelper.getDraftP12Password();
            tryImportP12WithPassword(uri, pass, true);
        } else if (requestCode == REQ_PICK_SERVER_CA) {
            new Thread(() -> {
                try {
                    int count = ClientCertManager.importServerCaFromUri(this, uri);
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

    private void tryImportP12WithPassword(Uri uri, String password, boolean promptOnFailure) {
        new Thread(() -> {
            try {
                ClientCertManager.CertIdentity id = ClientCertManager.importP12FromUri(this, uri, password);
                currentConfig.certMode = TunnelConfig.CERT_P12;
                currentConfig.p12Password = password == null ? "" : password;
                currentConfig.save(this);
                mainHandler.post(() -> WebView.clearClientCertPreferences(() ->
                        configHelper.onP12ImportedSuccess(password, id)));
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (promptOnFailure) {
                        promptP12PasswordDialog(uri, e.getMessage());
                    } else {
                        toast(e.getMessage());
                    }
                });
            }
        }).start();
    }

    private void promptP12PasswordDialog(Uri uri, String errMsg) {
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
                        tryImportP12WithPassword(uri, input.getText().toString(), true))
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
            if (currentConfig.hasAnyEndpoint()) {
                closeConfigOverlay();
            } else {
                finish();
            }
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackPressAt < 2000) {
            super.onBackPressed();
        } else {
            lastBackPressAt = now;
            toast("再按一次返回键退出 DSH 远控");
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
