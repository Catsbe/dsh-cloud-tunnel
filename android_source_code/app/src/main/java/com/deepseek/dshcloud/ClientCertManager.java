package com.deepseek.dshcloud;

import android.content.Context;
import android.net.Uri;
import android.net.http.SslCertificate;
import android.os.Build;
import android.os.Bundle;
import android.security.KeyChain;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

public class ClientCertManager {
    private static final String LEGACY_P12_FILENAME = "client_cert.p12";
    private static final String LEGACY_SERVER_CA_FILENAME = "server_ca.crt";

    public static class CertIdentity {
        public PrivateKey privateKey;
        public X509Certificate[] chain;
        public String subjectCn;
        public String validUntil;
        public String modeLabel;

        public boolean isValid() {
            return privateKey != null && chain != null && chain.length > 0;
        }

        public String getSummaryText() {
            String cn = (subjectCn == null || subjectCn.isEmpty()) ? "未知主体" : subjectCn;
            String exp = (validUntil == null || validUntil.isEmpty()) ? "" : (" (有效期至 " + validUntil + ")");
            return modeLabel + ": " + cn + exp;
        }
    }

    private static final Map<String, CertIdentity> cachedIdentities = new ConcurrentHashMap<>();
    private static final Map<String, String> lastLoadErrors = new ConcurrentHashMap<>();

    private static String safeId(String profileId) {
        if (profileId == null || profileId.trim().isEmpty()) return "default";
        return profileId.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private static File getP12File(Context ctx, String profileId) {
        return new File(ctx.getFilesDir(), "client_cert_" + safeId(profileId) + ".p12");
    }

    private static File getServerCaFile(Context ctx, String profileId) {
        return new File(ctx.getFilesDir(), "server_ca_" + safeId(profileId) + ".crt");
    }

    public static void migrateLegacyFilesToProfile(Context ctx, String profileId) {
        String sid = safeId(profileId);
        File oldP12 = new File(ctx.getFilesDir(), LEGACY_P12_FILENAME);
        File newP12 = getP12File(ctx, sid);
        if (oldP12.exists() && oldP12.length() > 0 && !newP12.exists()) {
            oldP12.renameTo(newP12);
        }
        File oldCa = new File(ctx.getFilesDir(), LEGACY_SERVER_CA_FILENAME);
        File newCa = getServerCaFile(ctx, sid);
        if (oldCa.exists() && oldCa.length() > 0 && !newCa.exists()) {
            oldCa.renameTo(newCa);
        }
    }

    public static void deleteProfileFiles(Context ctx, String profileId) {
        String sid = safeId(profileId);
        File f1 = getP12File(ctx, sid);
        if (f1.exists()) f1.delete();
        File f2 = getServerCaFile(ctx, sid);
        if (f2.exists()) f2.delete();
        cachedIdentities.remove(sid);
        lastLoadErrors.remove(sid);
    }

    public static CertIdentity getCachedIdentity(String profileId) {
        return cachedIdentities.get(safeId(profileId));
    }

    public static String getLastLoadError(String profileId) {
        String err = lastLoadErrors.get(safeId(profileId));
        return err == null ? "" : err;
    }

    public static void clearCache(String profileId) {
        String sid = safeId(profileId);
        cachedIdentities.remove(sid);
        lastLoadErrors.remove(sid);
    }

    public static boolean hasStoredP12(Context ctx, String profileId) {
        File f = getP12File(ctx, profileId);
        return f.exists() && f.length() > 0;
    }

    public static boolean hasStoredServerCa(Context ctx, String profileId) {
        File f = getServerCaFile(ctx, profileId);
        return f.exists() && f.length() > 0;
    }

    public static void deleteStoredP12(Context ctx, String profileId) {
        String sid = safeId(profileId);
        File f = getP12File(ctx, sid);
        if (f.exists()) f.delete();
        cachedIdentities.remove(sid);
        lastLoadErrors.remove(sid);
    }

    public static void deleteStoredServerCa(Context ctx, String profileId) {
        File f = getServerCaFile(ctx, profileId);
        if (f.exists()) f.delete();
    }

    public static CertIdentity copyP12FromProfile(Context ctx, String fromProfileId, String toProfileId, String password) throws Exception {
        File src = getP12File(ctx, fromProfileId);
        if (!src.exists() || src.length() == 0) {
            throw new IllegalArgumentException("来源电脑尚未导入 .p12 文件");
        }
        byte[] data;
        try (FileInputStream fis = new FileInputStream(src)) {
            data = readAllBytes(fis, 5 * 1024 * 1024);
        }
        CertIdentity id;
        try {
            id = parseP12Bytes(data, password);
        } catch (Exception e) {
            throw new IllegalArgumentException(translateP12Error(e));
        }
        String targetSid = safeId(toProfileId);
        File dst = getP12File(ctx, targetSid);
        try (FileOutputStream fos = new FileOutputStream(dst)) {
            fos.write(data);
            fos.flush();
        }
        cachedIdentities.put(targetSid, id);
        lastLoadErrors.put(targetSid, "");
        return id;
    }

    public static CertIdentity loadActiveIdentity(Context ctx, TunnelConfig cfg) {
        if (cfg == null) {
            return null;
        }
        String sid = safeId(cfg.id);
        if (TunnelConfig.CERT_P12.equals(cfg.certMode)) {
            File f = getP12File(ctx, sid);
            if (!f.exists() || f.length() == 0) {
                cachedIdentities.remove(sid);
                lastLoadErrors.put(sid, "未找到已导入的 .p12 证书文件");
                return null;
            }
            try (FileInputStream fis = new FileInputStream(f)) {
                byte[] data = readAllBytes(fis, 5 * 1024 * 1024);
                CertIdentity id = parseP12Bytes(data, cfg.p12Password);
                cachedIdentities.put(sid, id);
                lastLoadErrors.put(sid, "");
                return id;
            } catch (Exception e) {
                cachedIdentities.remove(sid);
                lastLoadErrors.put(sid, translateP12Error(e));
                return null;
            }
        } else if (TunnelConfig.CERT_SYSTEM.equals(cfg.certMode)) {
            if (cfg.systemCertAlias == null || cfg.systemCertAlias.trim().isEmpty()) {
                cachedIdentities.remove(sid);
                lastLoadErrors.put(sid, "尚未选择系统证书别名");
                return null;
            }
            try {
                PrivateKey pk = KeyChain.getPrivateKey(ctx, cfg.systemCertAlias);
                X509Certificate[] chain = KeyChain.getCertificateChain(ctx, cfg.systemCertAlias);
                if (pk == null || chain == null || chain.length == 0) {
                    cachedIdentities.remove(sid);
                    lastLoadErrors.put(sid, "系统证书不可用或未授权，请重新选择");
                    return null;
                }
                CertIdentity id = new CertIdentity();
                id.privateKey = pk;
                id.chain = chain;
                id.subjectCn = extractCommonName(chain[0]);
                id.validUntil = formatDate(chain[0]);
                id.modeLabel = "系统证书 [" + cfg.systemCertAlias + "]";
                cachedIdentities.put(sid, id);
                lastLoadErrors.put(sid, "");
                return id;
            } catch (Exception e) {
                cachedIdentities.remove(sid);
                lastLoadErrors.put(sid, "读取系统证书失败: " + e.getMessage());
                return null;
            }
        } else {
            cachedIdentities.remove(sid);
            lastLoadErrors.put(sid, "");
            return null;
        }
    }

    public static CertIdentity importP12FromUri(Context ctx, String profileId, Uri uri, String password) throws Exception {
        byte[] data;
        try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
            if (is == null) throw new IllegalArgumentException("无法打开所选文件");
            data = readAllBytes(is, 5 * 1024 * 1024);
        }
        CertIdentity id;
        try {
            id = parseP12Bytes(data, password);
        } catch (Exception e) {
            throw new IllegalArgumentException(translateP12Error(e));
        }
        String sid = safeId(profileId);
        File outFile = getP12File(ctx, sid);
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(data);
            fos.flush();
        }
        cachedIdentities.put(sid, id);
        lastLoadErrors.put(sid, "");
        return id;
    }

    public static int importServerCaFromUri(Context ctx, String profileId, Uri uri) throws Exception {
        byte[] data;
        try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
            if (is == null) throw new IllegalArgumentException("无法打开证书文件");
            data = readAllBytes(is, 2 * 1024 * 1024);
        }
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Collection<? extends Certificate> certs = cf.generateCertificates(new ByteArrayInputStream(data));
        if (certs == null || certs.isEmpty()) {
            throw new IllegalArgumentException("未识别到有效的 X.509 服务端 CA 证书 (.crt/.pem)");
        }
        File outFile = getServerCaFile(ctx, profileId);
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(data);
            fos.flush();
        }
        return certs.size();
    }

    private static CertIdentity parseP12Bytes(byte[] data, String password) throws Exception {
        char[] passChars = password == null ? new char[0] : password.toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(new ByteArrayInputStream(data), passChars);

        Enumeration<String> aliases = ks.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            if (ks.isKeyEntry(alias)) {
                java.security.Key key = ks.getKey(alias, passChars);
                if (!(key instanceof PrivateKey)) continue;
                Certificate[] rawChain = ks.getCertificateChain(alias);
                if (rawChain == null || rawChain.length == 0) {
                    Certificate single = ks.getCertificate(alias);
                    if (single != null) rawChain = new Certificate[]{single};
                }
                if (rawChain == null || rawChain.length == 0) continue;

                List<X509Certificate> xList = new ArrayList<>();
                for (Certificate c : rawChain) {
                    if (c instanceof X509Certificate) {
                        xList.add((X509Certificate) c);
                    }
                }
                if (xList.isEmpty()) continue;

                CertIdentity id = new CertIdentity();
                id.privateKey = (PrivateKey) key;
                id.chain = xList.toArray(new X509Certificate[0]);
                id.subjectCn = extractCommonName(id.chain[0]);
                id.validUntil = formatDate(id.chain[0]);
                id.modeLabel = "内置 .p12";
                return id;
            }
        }
        throw new IllegalArgumentException("该 .p12 文件中不包含有效的私钥与客户端证书");
    }

    private static String translateP12Error(Exception e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.US);
        if (msg.contains("mac") || msg.contains("password") || msg.contains("unrecoverable") || msg.contains("pad block")) {
            return "证书密码错误，请核对后重试";
        }
        return "解析 .p12 证书失败: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }

    public static SSLContext buildMtlsSslContext(Context ctx, TunnelConfig cfg) throws Exception {
        CertIdentity id = cfg != null ? cachedIdentities.get(safeId(cfg.id)) : null;
        if (id == null && cfg != null && !TunnelConfig.CERT_NONE.equals(cfg.certMode)) {
            id = loadActiveIdentity(ctx, cfg);
        }
        KeyManager[] kms = null;
        if (id != null && id.isValid()) {
            final CertIdentity activeId = id;
            kms = new KeyManager[]{new X509ExtendedKeyManager() {
                @Override
                public String[] getClientAliases(String keyType, Principal[] issuers) {
                    return new String[]{"dsh-client"};
                }

                @Override
                public String chooseClientAlias(String[] keyTypes, Principal[] issuers, Socket socket) {
                    return "dsh-client";
                }

                @Override
                public String chooseEngineClientAlias(String[] keyTypes, Principal[] issuers, SSLEngine engine) {
                    return "dsh-client";
                }

                @Override
                public String[] getServerAliases(String keyType, Principal[] issuers) {
                    return null;
                }

                @Override
                public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
                    return null;
                }

                @Override
                public X509Certificate[] getCertificateChain(String alias) {
                    return activeId.chain;
                }

                @Override
                public PrivateKey getPrivateKey(String alias) {
                    return activeId.privateKey;
                }
            }};
        }

        X509TrustManager tm = buildCompositeTrustManager(ctx, cfg);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kms, new TrustManager[]{tm}, null);
        return sslContext;
    }

    private static X509TrustManager buildCompositeTrustManager(Context ctx, TunnelConfig cfg) throws Exception {
        TrustManagerFactory sysTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        sysTmf.init((KeyStore) null);
        final X509TrustManager sysTm = findX509TrustManager(sysTmf.getTrustManagers());

        X509TrustManager customCaTm = null;
        File caFile = getServerCaFile(ctx, cfg != null ? cfg.id : "default");
        if (caFile.exists() && caFile.length() > 0) {
            try (FileInputStream fis = new FileInputStream(caFile)) {
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                Collection<? extends Certificate> certs = cf.generateCertificates(fis);
                KeyStore caKs = KeyStore.getInstance(KeyStore.getDefaultType());
                caKs.load(null, null);
                int idx = 0;
                for (Certificate c : certs) {
                    caKs.setCertificateEntry("ca-" + (idx++), c);
                }
                TrustManagerFactory caTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                caTmf.init(caKs);
                customCaTm = findX509TrustManager(caTmf.getTrustManagers());
            } catch (Exception ignored) {
            }
        }
        final X509TrustManager finalCustomTm = customCaTm;
        final String pinnedSha256 = (cfg != null && cfg.trustedServerCertSha256 != null)
                ? cfg.trustedServerCertSha256.trim().toUpperCase(Locale.US)
                : "";

        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (sysTm != null) sysTm.checkClientTrusted(chain, authType);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain != null && chain.length > 0 && !pinnedSha256.isEmpty()) {
                    String leafSha = sha256Hex(chain[0]);
                    if (pinnedSha256.equalsIgnoreCase(leafSha)) {
                        return;
                    }
                }
                CertificateException lastEx = null;
                if (sysTm != null) {
                    try {
                        sysTm.checkServerTrusted(chain, authType);
                        return;
                    } catch (CertificateException e) {
                        lastEx = e;
                    }
                }
                if (finalCustomTm != null) {
                    try {
                        finalCustomTm.checkServerTrusted(chain, authType);
                        return;
                    } catch (CertificateException e) {
                        lastEx = e;
                    }
                }
                if (lastEx != null) throw lastEx;
                throw new CertificateException("服务端证书校验失败");
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                if (sysTm != null) return sysTm.getAcceptedIssuers();
                return new X509Certificate[0];
            }
        };
    }

    public static boolean matchesTrustedServerCert(Context ctx, TunnelConfig cfg, SslCertificate sslCert) {
        if (sslCert == null) return false;
        X509Certificate x509 = toX509Certificate(sslCert);
        if (x509 == null) return false;
        try {
            X509TrustManager tm = buildCompositeTrustManager(ctx, cfg);
            tm.checkServerTrusted(new X509Certificate[]{x509}, "RSA");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static String extractServerCertSha256(SslCertificate sslCert) {
        X509Certificate x509 = toX509Certificate(sslCert);
        if (x509 == null) return "";
        return sha256Hex(x509);
    }

    public static X509Certificate toX509Certificate(SslCertificate sslCert) {
        if (sslCert == null) return null;
        if (Build.VERSION.SDK_INT >= 29) {
            X509Certificate c = sslCert.getX509Certificate();
            if (c != null) return c;
        }
        try {
            Bundle b = SslCertificate.saveState(sslCert);
            if (b != null) {
                byte[] bytes = b.getByteArray("x509-certificate");
                if (bytes != null && bytes.length > 0) {
                    CertificateFactory cf = CertificateFactory.getInstance("X.509");
                    return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(bytes));
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static X509TrustManager findX509TrustManager(TrustManager[] managers) {
        if (managers == null) return null;
        for (TrustManager tm : managers) {
            if (tm instanceof X509TrustManager) return (X509TrustManager) tm;
        }
        return null;
    }

    private static String sha256Hex(X509Certificate cert) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(cert.getEncoded());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < digest.length; i++) {
                if (i > 0) sb.append(':');
                sb.append(String.format(Locale.US, "%02X", digest[i] & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String extractCommonName(X509Certificate cert) {
        if (cert == null) return "";
        String dn = cert.getSubjectX500Principal().getName();
        for (String part : dn.split(",")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("CN=")) {
                return trimmed.substring(3);
            }
        }
        return dn;
    }

    private static String formatDate(X509Certificate cert) {
        if (cert == null || cert.getNotAfter() == null) return "";
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cert.getNotAfter());
    }

    private static byte[] readAllBytes(InputStream is, int maxBytes) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = is.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) {
                throw new IllegalArgumentException("文件过大（超过限制）");
            }
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }
}
