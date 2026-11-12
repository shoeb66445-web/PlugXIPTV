package app.plugxaura.iptv;

import android.media.*;
import android.os.Build;
import android.os.PersistableBundle;
import androidx.media3.common.C;
import androidx.media3.common.DrmInitData.SchemeData;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.drm.*;
import java.util.*;

/** Keeps Media3's ClearKey UUID adaptation consistent during decoder capability queries. */
final class LegacyClearKeyDrm implements ExoMediaDrm {
    static final Provider PROVIDER = uuid -> {
        ExoMediaDrm framework = FrameworkMediaDrm.DEFAULT_PROVIDER.acquireExoMediaDrm(uuid);
        return Build.VERSION.SDK_INT < 27 && C.CLEARKEY_UUID.equals(uuid)
                && framework instanceof FrameworkMediaDrm
                ? new LegacyClearKeyDrm(framework) : framework;
    };
    private final ExoMediaDrm delegate;
    private LegacyClearKeyDrm(ExoMediaDrm delegate) { this.delegate = delegate; }

    @Override public boolean requiresSecureDecoder(byte[] session, String mime) {
        // Before API 27, FrameworkMediaDrm opens ClearKey using COMMON_PSSH_UUID.
        // Media3 1.4.1's query instead uses CLEARKEY_UUID, which fails on Android 5
        // and returns true. 1.3.1 queried the correctly adapted MediaCrypto directly.
        // Ask the actual platform session: never force a security requirement off.
        MediaCrypto crypto = null;
        try {
            FrameworkCryptoConfig config = (FrameworkCryptoConfig) delegate.createCryptoConfig(session);
            crypto = new MediaCrypto(config.uuid, config.sessionId);
            return crypto.requiresSecureDecoderComponent(mime);
        } catch (MediaCryptoException | RuntimeException failure) {
            return true;
        } finally {
            if (crypto != null) crypto.release();
        }
    }
    @Override public void setOnEventListener(OnEventListener listener) {
        delegate.setOnEventListener(listener == null ? null : (drm, session, event, extra, data) -> listener.onEvent(this, session, event, extra, data));
    }
    @Override public void setOnKeyStatusChangeListener(OnKeyStatusChangeListener listener) {
        delegate.setOnKeyStatusChangeListener(listener == null ? null : (drm, session, keys, usable) -> listener.onKeyStatusChange(this, session, keys, usable));
    }
    @Override public void setOnExpirationUpdateListener(OnExpirationUpdateListener listener) {
        delegate.setOnExpirationUpdateListener(listener == null ? null : (drm, session, time) -> listener.onExpirationUpdate(this, session, time));
    }
    @Override public byte[] openSession() throws MediaDrmException { return delegate.openSession(); }
    @Override public void closeSession(byte[] session) { delegate.closeSession(session); }
    @Override public void setPlayerIdForSession(byte[] session, PlayerId player) { delegate.setPlayerIdForSession(session, player); }
    @Override public KeyRequest getKeyRequest(byte[] scope, List<SchemeData> data, int type, HashMap<String,String> options) throws NotProvisionedException { return delegate.getKeyRequest(scope, data, type, options); }
    @Override public byte[] provideKeyResponse(byte[] scope, byte[] response) throws NotProvisionedException, DeniedByServerException { return delegate.provideKeyResponse(scope, response); }
    @Override public ProvisionRequest getProvisionRequest() { return delegate.getProvisionRequest(); }
    @Override public void provideProvisionResponse(byte[] response) throws DeniedByServerException { delegate.provideProvisionResponse(response); }
    @Override public Map<String,String> queryKeyStatus(byte[] session) { return delegate.queryKeyStatus(session); }
    @Override public void acquire() { delegate.acquire(); }
    @Override public void release() { delegate.release(); }
    @Override public void restoreKeys(byte[] session, byte[] keys) { delegate.restoreKeys(session, keys); }
    @Override public void removeOfflineLicense(byte[] keys) { delegate.removeOfflineLicense(keys); }
    @Override public List<byte[]> getOfflineLicenseKeySetIds() { return delegate.getOfflineLicenseKeySetIds(); }
    @Override public PersistableBundle getMetrics() { return delegate.getMetrics(); }
    @Override public String getPropertyString(String name) { return delegate.getPropertyString(name); }
    @Override public byte[] getPropertyByteArray(String name) { return delegate.getPropertyByteArray(name); }
    @Override public void setPropertyString(String name, String value) { delegate.setPropertyString(name, value); }
    @Override public void setPropertyByteArray(String name, byte[] value) { delegate.setPropertyByteArray(name, value); }
    @Override public CryptoConfig createCryptoConfig(byte[] session) throws MediaCryptoException { return delegate.createCryptoConfig(session); }
    @Override public int getCryptoType() { return delegate.getCryptoType(); }
}

