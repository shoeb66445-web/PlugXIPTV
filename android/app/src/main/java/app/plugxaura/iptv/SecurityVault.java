package app.plugxaura.iptv;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.util.Base64;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class SecurityVault {

    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    private SecurityVault() {}

    static String dec(byte[] enc, byte key) {
        byte[] res = new byte[enc.length];
        for (int i = 0; i < enc.length; i++) {
            res[i] = (byte) (enc[i] ^ key);
        }
        return new String(res, StandardCharsets.UTF_8);
    }

    static SecretKey deviceKey(Context ctx) {
        try {
            StringBuilder seed = new StringBuilder();
            seed.append(ctx.getPackageName()).append("|");
            String androidId = android.provider.Settings.Secure.getString(
                    ctx.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
            seed.append(androidId == null ? "null" : androidId).append("|");
            seed.append(certFingerprint(ctx)).append("|");
            seed.append(Build.FINGERPRINT);

            byte[] raw = seed.toString().getBytes(StandardCharsets.UTF_8);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(raw);
            return new SecretKeySpec(hash, "AES");
        } catch (Exception e) {
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256")
                        .digest(ctx.getPackageName().getBytes(StandardCharsets.UTF_8));
                return new SecretKeySpec(hash, "AES");
            } catch (Exception ex) {
                throw new RuntimeException("Key derivation failed", ex);
            }
        }
    }

    static String encrypt(String plaintext, SecretKey key) {
        try {
            byte[] iv = new byte[IV_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] output = ByteBuffer.allocate(IV_BYTES + ciphertext.length)
                    .put(iv).put(ciphertext).array();
            return Base64.encodeToString(output, Base64.NO_WRAP);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    static String decrypt(String cipherBase64, SecretKey key) {
        try {
            byte[] input = Base64.decode(cipherBase64, Base64.NO_WRAP);
            ByteBuffer buf = ByteBuffer.wrap(input);
            byte[] iv = new byte[IV_BYTES];
            buf.get(iv);
            byte[] ciphertext = new byte[buf.remaining()];
            buf.get(ciphertext);
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = cipher.doFinal(ciphertext);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    static String certFingerprint(Context ctx) {
        try {
            Signature[] sigs;
            if (Build.VERSION.SDK_INT >= 28) {
                android.content.pm.PackageInfo info = ctx.getPackageManager().getPackageInfo(
                        ctx.getPackageName(),
                        PackageManager.GET_SIGNING_CERTIFICATES);
                sigs = info.signingInfo.getApkContentsSigners();
            } else {
                @SuppressWarnings("deprecation")
                android.content.pm.PackageInfo info = ctx.getPackageManager().getPackageInfo(
                        ctx.getPackageName(),
                        PackageManager.GET_SIGNATURES);
                sigs = info.signatures;
            }
            if (sigs == null || sigs.length == 0) return "none";
            byte[] cert = sigs[0].toByteArray();
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(cert);
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (Exception e) {
            return "error";
        }
    }

    static boolean isTrusted(Context ctx) {
        if (BuildConfig.DEBUG) return true;
        try {
            String current = certFingerprint(ctx);
            if (current == null || current.isEmpty() || current.equals("none") || current.equals("error")) return false;
            android.content.SharedPreferences prefs =
                    ctx.getSharedPreferences("plugx_vault", Context.MODE_PRIVATE);
            String trusted = prefs.getString("cert_fp", null);
            if (trusted == null) {
                prefs.edit().putString("cert_fp", current).apply();
                return true;
            }
            return trusted.equalsIgnoreCase(current);
        } catch (Exception e) {
            return true;
        }
    }

    static boolean isDebugged() {
        return android.os.Debug.isDebuggerConnected() || android.os.Debug.waitingForDebugger();
    }
}
