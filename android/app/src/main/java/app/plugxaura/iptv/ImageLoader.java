package app.plugxaura.iptv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Small, bounded cover cache so older TV boxes do not decode full-size posters. */
final class ImageLoader {
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(12 * 1024) {
        @Override protected int sizeOf(String key, Bitmap image) { return image.getByteCount() / 1024; }
    };
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build();
    private ImageLoader() { }
    static void into(Context context, String path, ImageView target) {
        String url = PlugXApp.absolute(path);
        target.setTag(url);
        target.setImageDrawable(null);
        if (url.isEmpty()) return;
        Bitmap cached = CACHE.get(url);
        if (cached != null) { target.setImageBitmap(cached); return; }
        PlugXApp.WORK.execute(() -> {
            Bitmap bitmap = null;
            try (Response response = CLIENT.newCall(new Request.Builder().url(url).build()).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    if (response.body().contentLength() > 3 * 1024 * 1024) return;
                    byte[] bytes;
                    try (InputStream in = response.body().byteStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                        byte[] chunk = new byte[8192]; int n;
                        while ((n = in.read(chunk)) != -1) { if (out.size() + n > 3 * 1024 * 1024) return; out.write(chunk, 0, n); }
                        bytes = out.toByteArray();
                    }
                    if (bytes.length > 0) {
                        BitmapFactory.Options bounds = new BitmapFactory.Options();
                        bounds.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                        int sample = 1;
                        while (bounds.outWidth / sample > 480 || bounds.outHeight / sample > 320) sample *= 2;
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inSampleSize = sample;
                        options.inPreferredConfig = Bitmap.Config.RGB_565;
                        bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                    }
                }
            } catch (Exception ignored) { }
            Bitmap ready = bitmap;
            if (ready != null) {
                CACHE.put(url, ready);
                PlugXApp.MAIN.post(() -> { if (url.equals(target.getTag())) target.setImageBitmap(ready); });
            }
        });
    }
}
