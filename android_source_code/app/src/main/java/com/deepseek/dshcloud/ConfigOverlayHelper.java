package com.deepseek.dshcloud;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.security.KeyChain;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class ConfigOverlayHelper {

    public interface HostActions {
        void onPickP12File();
        void onPickServerCaFile();
        void onRequestSystemCert(KeyChainAliasCallback cb);
        void onConfigSavedAndConnect(TunnelConfig updatedConfig);
        void onCloseRequested();
    }

    public interface KeyChainAliasCallback {
        void onAliasPicked(String alias);
    }

    private final Context ctx;
    private final HostActions host;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TunnelConfig draftConfig;
    private View rootOverlay;
    private Button btnClose;
    private EditText etName;
    private EditText etLanUrls;
    private EditText etWanUrl;
    private EditText etP12Pass;
    private RadioGroup rgRouteMode;
    private RadioButton rbAuto;
    private RadioButton rbLanOnly;
    private RadioButton rbWanOnly;
    private Switch swAutoUpgrade;
    private TextView tvCertStatus;
    private TextView tvCaStatus;
    private TextView tvScanStatus;
    private TextView tvTestResult;
    private Button btnTestBoth;
    private Button btnScanLan;
    private boolean passVisible = false;

    public ConfigOverlayHelper(Context ctx, HostActions host) {
        this.ctx = ctx;
        this.host = host;
    }

    public View buildView(TunnelConfig currentCfg) {
        this.draftConfig = copyConfig(currentCfg);

        FrameLayout backdrop = new FrameLayout(ctx);
        backdrop.setBackgroundColor(Color.parseColor("#0F172A"));
        backdrop.setClickable(true);
        backdrop.setFocusable(true);

        ScrollView scroll = new ScrollView(ctx);
        scroll.setFillViewport(true);

        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        container.setPadding(pad, dp(14), pad, dp(28));

        // Header row
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(12));

        TextView title = new TextView(ctx);
        title.setText("⚙️ DSH 远控 · 双轨与 mTLS 设置");
        title.setTextColor(Color.parseColor("#F8FAFC"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(title, titleLp);

        btnClose = makeSmallBtn("返回", "#334155", "#E2E8F0");
        btnClose.setOnClickListener(v -> host.onCloseRequested());
        btnClose.setVisibility(draftConfig.hasAnyEndpoint() ? View.VISIBLE : View.GONE);
        header.addView(btnClose);
        container.addView(header);

        // Card 1: Quick Import
        LinearLayout quickCard = makeCard();
        quickCard.addView(makeSectionTitle("⚡ 一键导入电脑配置"));
        TextView quickSub = makeHint("可在同一 Wi-Fi 下自动发现电脑，或粘贴 PC 端“云端穿透”里的手机配对码。");
        quickCard.addView(quickSub);

        LinearLayout quickBtns = new LinearLayout(ctx);
        quickBtns.setOrientation(LinearLayout.HORIZONTAL);
        quickBtns.setPadding(0, dp(8), 0, 0);

        btnScanLan = makeActionBtn("🔍 扫描局域网电脑", "#0284C7", "#FFFFFF");
        LinearLayout.LayoutParams b1Lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        b1Lp.rightMargin = dp(8);
        btnScanLan.setOnClickListener(v -> runLanDiscovery());
        quickBtns.addView(btnScanLan, b1Lp);

        Button btnPaste = makeActionBtn("📋 剪贴板导入", "#334155", "#F8FAFC");
        LinearLayout.LayoutParams b2Lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnPaste.setOnClickListener(v -> importFromClipboard());
        quickBtns.addView(btnPaste, b2Lp);

        quickCard.addView(quickBtns);

        tvScanStatus = new TextView(ctx);
        tvScanStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvScanStatus.setTextColor(Color.parseColor("#94A3B8"));
        tvScanStatus.setPadding(0, dp(6), 0, 0);
        tvScanStatus.setVisibility(View.GONE);
        quickCard.addView(tvScanStatus);
        container.addView(quickCard, makeCardLp());

        // Card 2: Endpoints & Strategy
        LinearLayout netCard = makeCard();
        netCard.addView(makeSectionTitle("🌐 双轨通道配置"));

        netCard.addView(makeLabel("电脑备注名称"));
        etName = makeInput("例如：办公电脑", false);
        netCard.addView(etName);

        netCard.addView(makeLabel("局域网直连地址 (同 Wi-Fi 秒连，可填多个换行分隔)"));
        etLanUrls = makeInput("例如：http://192.168.1.100:58000", true);
        etLanUrls.setMinLines(1);
        etLanUrls.setMaxLines(3);
        netCard.addView(etLanUrls);

        netCard.addView(makeLabel("公网 mTLS 转发地址 (外出 4G/5G 走云服务器)"));
        etWanUrl = makeInput("例如：https://dsh.example.com:8443", false);
        netCard.addView(etWanUrl);

        netCard.addView(makeLabel("默认切换策略"));
        rgRouteMode = new RadioGroup(ctx);
        rgRouteMode.setOrientation(LinearLayout.VERTICAL);

        rbAuto = makeRadio("智能双轨 (优先局域网秒连，不在内网自动走云端)");
        rbLanOnly = makeRadio("仅局域网直连");
        rbWanOnly = makeRadio("仅公网 mTLS 转发");
        rgRouteMode.addView(rbAuto);
        rgRouteMode.addView(rbLanOnly);
        rgRouteMode.addView(rbWanOnly);
        netCard.addView(rgRouteMode);

        LinearLayout swRow = new LinearLayout(ctx);
        swRow.setOrientation(LinearLayout.HORIZONTAL);
        swRow.setGravity(Gravity.CENTER_VERTICAL);
        swRow.setPadding(0, dp(8), 0, 0);
        TextView swTxt = new TextView(ctx);
        swTxt.setText("手机连回 Wi-Fi 且局域网可达时自动切回直连");
        swTxt.setTextColor(Color.parseColor("#CBD5E1"));
        swTxt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        swRow.addView(swTxt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        swAutoUpgrade = new Switch(ctx);
        swRow.addView(swAutoUpgrade);
        netCard.addView(swRow);

        container.addView(netCard, makeCardLp());

        // Card 3: mTLS Client Certificate
        LinearLayout certCard = makeCard();
        certCard.addView(makeSectionTitle("🔐 mTLS 客户端可信证书"));
        certCard.addView(makeHint("推荐直接导入 .p12 证书文件，App 将在底层自动完成双向 TLS 认证，告别系统弹窗；若之前已在手机系统设置安装过证书，也可直接绑定系统证书。"));

        tvCertStatus = new TextView(ctx);
        tvCertStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvCertStatus.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable statusBg = new GradientDrawable();
        statusBg.setColor(Color.parseColor("#0F172A"));
        statusBg.setCornerRadius(dp(8));
        statusBg.setStroke(dp(1), Color.parseColor("#334155"));
        tvCertStatus.setBackground(statusBg);
        LinearLayout.LayoutParams csLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        csLp.topMargin = dp(8);
        certCard.addView(tvCertStatus, csLp);

        netCard.post(() -> { /* noop */ });

        certCard.addView(makeLabel(".p12 证书密码 (导入或解锁时使用，无密码请留空)"));
        LinearLayout passRow = new LinearLayout(ctx);
        passRow.setOrientation(LinearLayout.HORIZONTAL);
        passRow.setGravity(Gravity.CENTER_VERTICAL);

        etP12Pass = makeInput("请输入 .p12 证书密码", false);
        etP12Pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pLp.topMargin = dp(4);
        pLp.rightMargin = dp(8);
        passRow.addView(etP12Pass, pLp);

        Button btnEye = makeSmallBtn("显示", "#334155", "#CBD5E1");
        btnEye.setOnClickListener(v -> {
            passVisible = !passVisible;
            if (passVisible) {
                etP12Pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                btnEye.setText("隐藏");
            } else {
                etP12Pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                btnEye.setText("显示");
            }
            etP12Pass.setSelection(etP12Pass.getText().length());
        });
        passRow.addView(btnEye);
        certCard.addView(passRow);

        LinearLayout certBtns = new LinearLayout(ctx);
        certBtns.setOrientation(LinearLayout.HORIZONTAL);
        certBtns.setPadding(0, dp(10), 0, 0);

        Button btnPickP12 = makeActionBtn("📂 导入 .p12 文件", "#2563EB", "#FFFFFF");
        LinearLayout.LayoutParams cb1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cb1.rightMargin = dp(8);
        btnPickP12.setOnClickListener(v -> {
            draftConfig.p12Password = etP12Pass.getText().toString();
            host.onPickP12File();
        });
        certBtns.addView(btnPickP12, cb1);

        Button btnPickSys = makeActionBtn("🔑 选系统已装证书", "#334155", "#F8FAFC");
        LinearLayout.LayoutParams cb2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnPickSys.setOnClickListener(v -> host.onRequestSystemCert(alias -> {
            if (alias == null || alias.trim().isEmpty()) return;
            new Thread(() -> {
                draftConfig.certMode = TunnelConfig.CERT_SYSTEM;
                draftConfig.systemCertAlias = alias;
                ClientCertManager.loadActiveIdentity(ctx, draftConfig);
                mainHandler.post(this::refreshCertStatusUi);
            }).start();
        }));
        certBtns.addView(btnPickSys, cb2);
        certCard.addView(certBtns);

        // Secondary Cert actions (Unlock / Clear / Custom Server CA)
        LinearLayout certExtra = new LinearLayout(ctx);
        certExtra.setOrientation(LinearLayout.HORIZONTAL);
        certExtra.setPadding(0, dp(8), 0, 0);

        Button btnReunlock = makeSmallBtn("用当前密码重载 .p12", "#1E293B", "#38BDF8");
        btnReunlock.setOnClickListener(v -> {
            if (!ClientCertManager.hasStoredP12(ctx)) {
                toast("尚未导入过 .p12 文件");
                return;
            }
            draftConfig.certMode = TunnelConfig.CERT_P12;
            draftConfig.p12Password = etP12Pass.getText().toString();
            new Thread(() -> {
                ClientCertManager.CertIdentity id = ClientCertManager.loadActiveIdentity(ctx, draftConfig);
                mainHandler.post(() -> {
                    refreshCertStatusUi();
                    if (id != null) {
                        toast("证书解锁成功: " + id.subjectCn);
                    } else {
                        toast(ClientCertManager.getLastLoadError());
                    }
                });
            }).start();
        });
        LinearLayout.LayoutParams ex1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ex1.rightMargin = dp(8);
        certExtra.addView(btnReunlock, ex1);

        Button btnImportCa = makeSmallBtn("自定义服务端 CA (.crt)", "#1E293B", "#94A3B8");
        btnImportCa.setOnClickListener(v -> showAdvancedCaDialog());
        LinearLayout.LayoutParams ex2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        certExtra.addView(btnImportCa, ex2);
        certCard.addView(certExtra);

        tvCaStatus = new TextView(ctx);
        tvCaStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvCaStatus.setTextColor(Color.parseColor("#94A3B8"));
        tvCaStatus.setPadding(0, dp(6), 0, 0);
        certCard.addView(tvCaStatus);

        container.addView(certCard, makeCardLp());

        // Card 4: Live Dual-Track Connectivity Test
        LinearLayout testCard = makeCard();
        testCard.addView(makeSectionTitle("🩺 双轨连通性实测"));
        btnTestBoth = makeActionBtn("⚡ 同时测试局域网与公网 mTLS", "#0F766E", "#FFFFFF");
        btnTestBoth.setOnClickListener(v -> runDualDiagnosticTest());
        LinearLayout.LayoutParams tbLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tbLp.topMargin = dp(6);
        testCard.addView(btnTestBoth, tbLp);

        tvTestResult = new TextView(ctx);
        tvTestResult.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvTestResult.setTextColor(Color.parseColor("#CBD5E1"));
        tvTestResult.setPadding(0, dp(8), 0, 0);
        tvTestResult.setVisibility(View.GONE);
        testCard.addView(tvTestResult);
        container.addView(testCard, makeCardLp());

        // Primary Save & Connect Button
        Button btnSave = makeActionBtn("💾 保存配置并立即连接", "#10B981", "#022C22");
        btnSave.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        btnSave.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        saveLp.topMargin = dp(6);
        btnSave.setOnClickListener(v -> saveAndSubmit());
        container.addView(btnSave, saveLp);

        scroll.addView(container);
        backdrop.addView(scroll);
        this.rootOverlay = backdrop;

        bindFromDraft(currentCfg);
        return backdrop;
    }

    public void bindFromDraft(TunnelConfig cfg) {
        this.draftConfig = copyConfig(cfg);
        etName.setText(draftConfig.profileName);
        etLanUrls.setText(draftConfig.lanUrlsRaw);
        etWanUrl.setText(draftConfig.wanUrl);
        etP12Pass.setText(draftConfig.p12Password);

        if (TunnelConfig.ROUTE_LAN_ONLY.equals(draftConfig.routeMode)) {
            rbLanOnly.setChecked(true);
        } else if (TunnelConfig.ROUTE_WAN_ONLY.equals(draftConfig.routeMode)) {
            rbWanOnly.setChecked(true);
        } else {
            rbAuto.setChecked(true);
        }
        swAutoUpgrade.setChecked(draftConfig.autoUpgradeToLan);
        btnClose.setVisibility(draftConfig.hasAnyEndpoint() ? View.VISIBLE : View.GONE);
        refreshCertStatusUi();
    }

    public String getDraftP12Password() {
        return etP12Pass != null ? etP12Pass.getText().toString() : "";
    }

    public void onP12ImportedSuccess(String usedPassword, ClientCertManager.CertIdentity id) {
        draftConfig.certMode = TunnelConfig.CERT_P12;
        draftConfig.p12Password = usedPassword == null ? "" : usedPassword;
        if (etP12Pass != null) {
            etP12Pass.setText(draftConfig.p12Password);
        }
        refreshCertStatusUi();
        toast("已导入客户端证书: " + id.subjectCn);
    }

    public void onServerCaUpdated() {
        refreshCertStatusUi();
    }

    public void applyParsedPairing(TunnelConfig.PairingParseResult res) {
        if (res.name != null && !res.name.isEmpty()) etName.setText(res.name);
        if (res.lanUrlsJoined != null && !res.lanUrlsJoined.isEmpty()) etLanUrls.setText(res.lanUrlsJoined);
        if (res.wanUrl != null && !res.wanUrl.isEmpty()) etWanUrl.setText(res.wanUrl);
        toast("已导入配对配置: " + res.name);
    }

    public void refreshCertStatusUi() {
        if (tvCertStatus == null) return;
        ClientCertManager.CertIdentity id = ClientCertManager.getCachedIdentity();
        String err = ClientCertManager.getLastLoadError();
        if (id != null && id.isValid()) {
            tvCertStatus.setText("✅ " + id.getSummaryText());
            tvCertStatus.setTextColor(Color.parseColor("#34D399"));
        } else if (!err.isEmpty()) {
            tvCertStatus.setText("❌ " + err);
            tvCertStatus.setTextColor(Color.parseColor("#FCA5A5"));
        } else {
            tvCertStatus.setText("⚠️ 尚未配置 mTLS 客户端证书（局域网可用；公网转发前请导入 .p12）");
            tvCertStatus.setTextColor(Color.parseColor("#FCD34D"));
        }

        if (tvCaStatus != null) {
            boolean hasCa = ClientCertManager.hasStoredServerCa(ctx);
            boolean hasPin = draftConfig.trustedServerCertSha256 != null && !draftConfig.trustedServerCertSha256.isEmpty();
            if (hasCa || hasPin) {
                tvCaStatus.setVisibility(View.VISIBLE);
                tvCaStatus.setText("🛡️ 已启用自定义服务端证书信任"
                        + (hasCa ? " (已导入 CA)" : "")
                        + (hasPin ? " (已固定指纹)" : ""));
            } else {
                tvCaStatus.setVisibility(View.GONE);
            }
        }
    }

    private void showAdvancedCaDialog() {
        boolean hasCa = ClientCertManager.hasStoredServerCa(ctx);
        boolean hasPin = draftConfig.trustedServerCertSha256 != null && !draftConfig.trustedServerCertSha256.isEmpty();
        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle("自定义服务端证书 (可选)");
        b.setMessage("若你的云服务器使用了正式域名证书（如 Let's Encrypt / 商业 CA），无需任何额外配置。\n\n仅当你使用纯 IP 或自签名服务端证书时，可在此导入服务端 CA (.crt/.pem)。");
        b.setPositiveButton("导入服务端 CA (.crt)", (d, w) -> host.onPickServerCaFile());
        if (hasCa || hasPin) {
            b.setNeutralButton("清除自定义信任", (d, w) -> {
                ClientCertManager.deleteStoredServerCa(ctx);
                draftConfig.trustedServerCertSha256 = "";
                refreshCertStatusUi();
                toast("已清除自定义服务端证书信任");
            });
        }
        b.setNegativeButton("取消", null);
        b.show();
    }

    private void importFromClipboard() {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) {
            toast("剪贴板为空，请先在电脑端复制手机配置码");
            return;
        }
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            toast("剪贴板无文本内容");
            return;
        }
        CharSequence txt = clip.getItemAt(0).coerceToText(ctx);
        if (txt == null) return;
        try {
            TunnelConfig.PairingParseResult parsed = TunnelConfig.parsePairingCode(txt.toString());
            applyParsedPairing(parsed);
        } catch (Exception e) {
            toast("剪贴板内容无法识别: " + e.getMessage());
        }
    }

    private void runLanDiscovery() {
        btnScanLan.setEnabled(false);
        btnScanLan.setText("扫描中...");
        tvScanStatus.setVisibility(View.VISIBLE);
        tvScanStatus.setTextColor(Color.parseColor("#38BDF8"));
        tvScanStatus.setText("正在扫描当前 Wi-Fi 局域网内的 DSH 网桥 (端口 " + draftConfig.defaultLocalPort + ")...");

        RouteProbeEngine.scanLocalSubnetForDsh(ctx, collectDraftFromFields(), draftConfig.defaultLocalPort, new RouteProbeEngine.ScanCallback() {
            @Override
            public void onFound(RouteProbeEngine.ProbeResult item) {
                mainHandler.post(() -> {
                    String name = item.hostName.isEmpty() ? item.url : item.hostName;
                    tvScanStatus.setText("已发现: " + name + " (" + item.url + ")，正在完成扫描...");
                });
            }

            @Override
            public void onFinished(List<RouteProbeEngine.ProbeResult> allFound, String errorMsg) {
                mainHandler.post(() -> {
                    btnScanLan.setEnabled(true);
                    btnScanLan.setText("🔍 扫描局域网电脑");
                    if (errorMsg != null && !errorMsg.isEmpty()) {
                        tvScanStatus.setTextColor(Color.parseColor("#FCA5A5"));
                        tvScanStatus.setText("❌ " + errorMsg);
                        return;
                    }
                    if (allFound.isEmpty()) {
                        tvScanStatus.setTextColor(Color.parseColor("#FCD34D"));
                        tvScanStatus.setText("未在当前 Wi-Fi 扫到开启「云端穿透」的电脑（请检查电脑防火墙是否放行 " + draftConfig.defaultLocalPort + " 端口）");
                        return;
                    }
                    tvScanStatus.setTextColor(Color.parseColor("#34D399"));
                    if (allFound.size() == 1) {
                        applyDiscoveredHost(allFound.get(0));
                        tvScanStatus.setText("✅ 已自动填入: " + (allFound.get(0).hostName.isEmpty() ? allFound.get(0).url : allFound.get(0).hostName));
                    } else {
                        String[] labels = new String[allFound.size()];
                        for (int i = 0; i < allFound.size(); i++) {
                            RouteProbeEngine.ProbeResult item = allFound.get(i);
                            labels[i] = (item.hostName.isEmpty() ? "DSH 主机" : item.hostName) + " — " + item.url + " (" + item.latencyMs + "ms)";
                        }
                        new AlertDialog.Builder(ctx)
                                .setTitle("选择发现的 DSH 电脑")
                                .setItems(labels, (d, which) -> {
                                    applyDiscoveredHost(allFound.get(which));
                                    tvScanStatus.setText("✅ 已选择: " + labels[which]);
                                })
                                .show();
                    }
                });
            }
        });
    }

    private void applyDiscoveredHost(RouteProbeEngine.ProbeResult pr) {
        if (pr.hostName != null && !pr.hostName.isEmpty()) {
            etName.setText(pr.hostName);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(pr.url);
        for (String ip : pr.lanIps) {
            String candidate = "http://" + ip + ":" + draftConfig.defaultLocalPort;
            if (!candidate.equals(pr.url) && sb.indexOf(candidate) == -1) {
                sb.append("\n").append(candidate);
            }
        }
        etLanUrls.setText(sb.toString());
        if (pr.publicUrl != null && !pr.publicUrl.isEmpty()) {
            etWanUrl.setText(pr.publicUrl);
        }
    }

    private void runDualDiagnosticTest() {
        TunnelConfig testCfg = collectDraftFromFields();
        if (!testCfg.hasAnyEndpoint()) {
            toast("请至少填写局域网或公网地址后再测试");
            return;
        }
        btnTestBoth.setEnabled(false);
        btnTestBoth.setText("探测中...");
        tvTestResult.setVisibility(View.VISIBLE);
        tvTestResult.setTextColor(Color.parseColor("#38BDF8"));
        tvTestResult.setText("正在同时探测局域网直连与公网 mTLS 通道...");

        new Thread(() -> {
            ClientCertManager.loadActiveIdentity(ctx, testCfg);
            List<String> lans = testCfg.getNormalizedLanUrls();
            String wan = testCfg.getNormalizedWanUrl();

            RouteProbeEngine.ProbeResult lanRes = null;
            for (String lu : lans) {
                RouteProbeEngine.ProbeResult r = RouteProbeEngine.probeSingle(ctx, testCfg, lu, true, 900, 900);
                lanRes = r;
                if (r.ok) break;
            }
            if (lanRes == null) {
                lanRes = RouteProbeEngine.probeSingle(ctx, testCfg, "", true, 900, 900);
            }
            RouteProbeEngine.ProbeResult wanRes = RouteProbeEngine.probeSingle(ctx, testCfg, wan, false, 3500, 3500);

            final RouteProbeEngine.ProbeResult finalLan = lanRes;
            final RouteProbeEngine.ProbeResult finalWan = wanRes;
            mainHandler.post(() -> {
                btnTestBoth.setEnabled(true);
                btnTestBoth.setText("⚡ 同时测试局域网与公网 mTLS");
                StringBuilder out = new StringBuilder();
                out.append("📶 局域网: ");
                if (finalLan.ok) {
                    out.append("✅ 已连通 (").append(finalLan.latencyMs).append("ms) -> ").append(finalLan.url);
                } else {
                    out.append("❌ ").append(finalLan.errorDetail);
                }
                out.append("\n☁️ 公网 mTLS: ");
                if (finalWan.ok) {
                    out.append("✅ 已连通 (").append(finalWan.latencyMs).append("ms) -> ").append(finalWan.url);
                } else {
                    out.append("❌ ").append(finalWan.errorDetail);
                }
                tvTestResult.setTextColor(Color.parseColor((finalLan.ok || finalWan.ok) ? "#A7F3D0" : "#FCA5A5"));
                tvTestResult.setText(out.toString());
                refreshCertStatusUi();
            });
        }, "dsh-dual-test").start();
    }

    private void saveAndSubmit() {
        TunnelConfig next = collectDraftFromFields();
        if (!next.hasAnyEndpoint()) {
            toast("请至少配置局域网地址或公网地址");
            return;
        }
        next.save(ctx);
        host.onConfigSavedAndConnect(next);
    }

    private TunnelConfig collectDraftFromFields() {
        TunnelConfig cfg = copyConfig(draftConfig);
        cfg.profileName = etName.getText().toString().trim();
        if (cfg.profileName.isEmpty()) cfg.profileName = "我的 DSH 电脑";
        cfg.lanUrlsRaw = etLanUrls.getText().toString().trim();
        cfg.wanUrl = TunnelConfig.normalizeUrl(etWanUrl.getText().toString(), "https", -1);
        cfg.p12Password = etP12Pass.getText().toString();
        if (rbLanOnly.isChecked()) {
            cfg.routeMode = TunnelConfig.ROUTE_LAN_ONLY;
        } else if (rbWanOnly.isChecked()) {
            cfg.routeMode = TunnelConfig.ROUTE_WAN_ONLY;
        } else {
            cfg.routeMode = TunnelConfig.ROUTE_AUTO;
        }
        cfg.autoUpgradeToLan = swAutoUpgrade.isChecked();
        return cfg;
    }

    private static TunnelConfig copyConfig(TunnelConfig src) {
        TunnelConfig c = new TunnelConfig();
        if (src == null) return c;
        c.profileName = src.profileName;
        c.lanUrlsRaw = src.lanUrlsRaw;
        c.defaultLocalPort = src.defaultLocalPort;
        c.wanUrl = src.wanUrl;
        c.certMode = src.certMode;
        c.p12Password = src.p12Password == null ? "" : src.p12Password;
        c.systemCertAlias = src.systemCertAlias;
        c.routeMode = src.routeMode;
        c.autoUpgradeToLan = src.autoUpgradeToLan;
        c.trustedServerCertSha256 = src.trustedServerCertSha256;
        c.pillXRatio = src.pillXRatio;
        c.pillYRatio = src.pillYRatio;
        return c;
    }

    // --- UI Helpers ---
    private LinearLayout makeCard() {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor("#1E293B"));
        gd.setCornerRadius(dp(12));
        gd.setStroke(dp(1), Color.parseColor("#334155"));
        card.setBackground(gd);
        return card;
    }

    private LinearLayout.LayoutParams makeCardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        return lp;
    }

    private TextView makeSectionTitle(String txt) {
        TextView tv = new TextView(ctx);
        tv.setText(txt);
        tv.setTextColor(Color.parseColor("#F8FAFC"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private TextView makeHint(String txt) {
        TextView tv = new TextView(ctx);
        tv.setText(txt);
        tv.setTextColor(Color.parseColor("#94A3B8"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setPadding(0, dp(4), 0, 0);
        return tv;
    }

    private TextView makeLabel(String txt) {
        TextView tv = new TextView(ctx);
        tv.setText(txt);
        tv.setTextColor(Color.parseColor("#CBD5E1"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setPadding(0, dp(10), 0, dp(4));
        return tv;
    }

    private EditText makeInput(String hint, boolean multiline) {
        EditText et = new EditText(ctx);
        et.setHint(hint);
        et.setHintTextColor(Color.parseColor("#64748B"));
        et.setTextColor(Color.parseColor("#F8FAFC"));
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        et.setPadding(dp(10), dp(9), dp(10), dp(9));
        et.setSingleLine(!multiline);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#0F172A"));
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), Color.parseColor("#334155"));
        et.setBackground(bg);
        return et;
    }

    private RadioButton makeRadio(String txt) {
        RadioButton rb = new RadioButton(ctx);
        rb.setText(txt);
        rb.setTextColor(Color.parseColor("#E2E8F0"));
        rb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        rb.setPadding(dp(4), dp(6), dp(4), dp(6));
        return rb;
    }

    private Button makeActionBtn(String txt, String bgHex, String fgHex) {
        Button b = new Button(ctx);
        b.setText(txt);
        b.setAllCaps(false);
        b.setTextColor(Color.parseColor(fgHex));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(bgHex));
        gd.setCornerRadius(dp(8));
        b.setBackground(gd);
        return b;
    }

    private Button makeSmallBtn(String txt, String bgHex, String fgHex) {
        Button b = new Button(ctx);
        b.setText(txt);
        b.setAllCaps(false);
        b.setTextColor(Color.parseColor(fgHex));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setMinHeight(dp(36));
        b.setMinimumHeight(dp(36));
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(bgHex));
        gd.setCornerRadius(dp(8));
        gd.setStroke(dp(1), Color.parseColor("#334155"));
        b.setBackground(gd);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    private void toast(String msg) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
    }
}
