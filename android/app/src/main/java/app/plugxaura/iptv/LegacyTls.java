package app.plugxaura.iptv;

import android.content.Context;
import android.os.Build;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.OkHttpClient;

/** Adds Let's Encrypt Root YE to older Android trust stores without disabling TLS validation. */
final class LegacyTls {
    private LegacyTls() {}

    static void addTrustedRoot(Context context, OkHttpClient.Builder builder) {
        if (Build.VERSION.SDK_INT >= 24) return;
        try {
            TrustManagerFactory platformFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            platformFactory.init((KeyStore) null);
            X509TrustManager platform = findX509(platformFactory.getTrustManagers());

            CertificateFactory certificates = CertificateFactory.getInstance("X.509");
            X509Certificate root;
            try (InputStream input = context.getResources().openRawResource(R.raw.isrg_root_ye)) {
                root = (X509Certificate) certificates.generateCertificate(input);
            }
            KeyStore extraStore = KeyStore.getInstance(KeyStore.getDefaultType());
            extraStore.load(null, null);
            extraStore.setCertificateEntry("isrg-root-ye", root);
            TrustManagerFactory extraFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            extraFactory.init(extraStore);
            X509TrustManager extra = findX509(extraFactory.getTrustManagers());

            X509TrustManager combined = new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    platform.checkClientTrusted(chain, authType);
                }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    try { platform.checkServerTrusted(chain, authType); }
                    catch (CertificateException original) { extra.checkServerTrusted(chain, authType); }
                }
                @Override public X509Certificate[] getAcceptedIssuers() {
                    X509Certificate[] first = platform.getAcceptedIssuers();
                    X509Certificate[] second = extra.getAcceptedIssuers();
                    X509Certificate[] all = Arrays.copyOf(first, first.length + second.length);
                    System.arraycopy(second, 0, all, first.length, second.length);
                    return all;
                }
            };
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(null, new TrustManager[]{combined}, new SecureRandom());
            builder.sslSocketFactory(tls.getSocketFactory(), combined);
        } catch (Exception e) {
            android.util.Log.w("PlugXTls", "Could not load supplemental certificate", e);
        }
    }

    private static X509TrustManager findX509(TrustManager[] managers) {
        for (TrustManager manager : managers) if (manager instanceof X509TrustManager) return (X509TrustManager) manager;
        throw new IllegalStateException("X509 trust manager is unavailable");
    }
}
