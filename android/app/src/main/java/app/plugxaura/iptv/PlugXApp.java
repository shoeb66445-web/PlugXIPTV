package app.plugxaura.iptv;

import android.content.*;
import android.os.*;
import androidx.multidex.MultiDexApplication;
import com.google.gson.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.SecretKey;
import okhttp3.*;

public class PlugXApp extends MultiDexApplication {
    // Obfuscated API endpoint: https://plugxaura.app/Plugxlive/api.php
    static final String API = SecurityVault.dec(new byte[]{(byte)0x32,(byte)0x2E,(byte)0x2E,(byte)0x2A,(byte)0x29,(byte)0x60,(byte)0x75,(byte)0x75,(byte)0x2A,(byte)0x36,(byte)0x2F,(byte)0x3D,(byte)0x22,(byte)0x3B,(byte)0x2F,(byte)0x28,(byte)0x3B,(byte)0x74,(byte)0x3B,(byte)0x2A,(byte)0x2A,(byte)0x75,(byte)0x0A,(byte)0x36,(byte)0x2F,(byte)0x3D,(byte)0x22,(byte)0x36,(byte)0x33,(byte)0x2C,(byte)0x3F,(byte)0x75,(byte)0x3B,(byte)0x2A,(byte)0x33,(byte)0x74,(byte)0x2A,(byte)0x32,(byte)0x2A}, (byte)0x5A);
    // Obfuscated base URL: https://plugxaura.app/Plugxlive/
    private static final String BASE_URL = SecurityVault.dec(new byte[]{(byte)0x32,(byte)0x2E,(byte)0x2E,(byte)0x2A,(byte)0x29,(byte)0x60,(byte)0x75,(byte)0x75,(byte)0x2A,(byte)0x36,(byte)0x2F,(byte)0x3D,(byte)0x22,(byte)0x3B,(byte)0x2F,(byte)0x28,(byte)0x3B,(byte)0x74,(byte)0x3B,(byte)0x2A,(byte)0x2A,(byte)0x75,(byte)0x0A,(byte)0x36,(byte)0x2F,(byte)0x3D,(byte)0x22,(byte)0x36,(byte)0x33,(byte)0x2C,(byte)0x3F,(byte)0x75}, (byte)0x5A);

    static final ExecutorService WORK=Executors.newFixedThreadPool(3);
    private static final ExecutorService FILE_WORK=Executors.newSingleThreadExecutor();
    static final Handler MAIN=new Handler(Looper.getMainLooper());
    static final Gson GSON=new Gson();
    private static OkHttpClient API_CLIENT;
    private static JsonObject data;
    private static SecretKey vaultKey;
    private static Context sAppContext;

    @Override public void onCreate(){
        sAppContext = this;
        super.onCreate();

        WORK.execute(()->{
            if(!SecurityVault.isTrusted(this)){
                MAIN.post(()->{
                    prefs(this).edit().clear().apply();
                    android.util.Log.e("PlugXSec","Integrity check failed");
                    android.os.Process.killProcess(android.os.Process.myPid());
                });
                return;
            }
            if(!BuildConfig.DEBUG && SecurityVault.isDebugged()){
                MAIN.post(()->android.os.Process.killProcess(android.os.Process.myPid()));
            }
        });

        try { vaultKey = SecurityVault.deviceKey(this); } catch(Exception ignored){}

        OkHttpClient.Builder http = new OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS);
        LegacyTls.addTrustedRoot(this, http);

        API_CLIENT = http.build();

        try {
            File f = new File(getFilesDir(), "catalog.enc");
            if(f.isFile()){
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try (InputStream in = new FileInputStream(f)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) != -1) {
                        bos.write(buf, 0, len);
                    }
                }
                String enc = bos.toString("UTF-8").trim();
                if(!enc.isEmpty() && vaultKey != null){
                    String json = SecurityVault.decrypt(enc, vaultKey);
                    if(json != null) data = JsonParser.parseString(json).getAsJsonObject();
                }
            } else {
                File legacy = new File(getFilesDir(), "catalog.json");
                if(legacy.isFile()){
                    try(Reader r = new FileReader(legacy)){
                        data = JsonParser.parseReader(r).getAsJsonObject();
                    }
                    if(data != null) persistCatalog(data);
                    legacy.delete();
                }
            }
        } catch(Exception ignored){}
    }

    static android.content.SharedPreferences prefs(Context c){return c.getSharedPreferences("plugx",0);}
    static String token(Context c){return prefs(c).getString("token","");}
    static String deviceId(Context c){String id=prefs(c).getString("device_id","");if(!id.isEmpty())return id;try{String androidId=android.provider.Settings.Secure.getString(c.getContentResolver(),android.provider.Settings.Secure.ANDROID_ID);if(androidId!=null&&!androidId.isEmpty()&&!androidId.equals("9774d56d682e549c")){byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest((c.getPackageName()+":"+androidId).getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder hex=new StringBuilder("a-");for(byte value:digest)hex.append(String.format(java.util.Locale.ROOT,"%02x",value&255));id=hex.toString();}}catch(Exception ignored){}if(id.isEmpty())id=UUID.randomUUID().toString();prefs(c).edit().putString("device_id",id).apply();return id;}

    static synchronized JsonObject catalog(){return data;}

    static synchronized void catalog(Context c, JsonObject value){
        data = value;
        prefs(c).edit().putLong("last_sync_at", System.currentTimeMillis()).apply();
        FILE_WORK.execute(()->persistCatalog(value));
    }

    private static void persistCatalog(JsonObject value){
        try {
            if(sAppContext == null) return;
            File file = new File(sAppContext.getFilesDir(), "catalog.enc");
            String json = GSON.toJson(value);
            String encrypted = vaultKey != null ? SecurityVault.encrypt(json, vaultKey) : json;
            try(Writer w = new FileWriter(file)){
                w.write(encrypted);
            }
            new File(sAppContext.getFilesDir(), "catalog.json").delete();
        } catch(Exception ignored){}
    }

    interface Result{void done(JsonObject result,Exception error);}

    static void request(Context c,String action,JsonObject body,Result callback){
        WORK.execute(()->{
            JsonObject result=null;Exception error=null;
            try{
                JsonObject payload=body==null?new JsonObject():body;
                payload.addProperty("action",action);
                Request.Builder b=new Request.Builder().url(API)
                        .post(RequestBody.create(GSON.toJson(payload),MediaType.parse("application/json")));
                String t=token(c);if(!t.isEmpty())b.header("Authorization","Bearer "+t);
                try(Response r=API_CLIENT.newCall(b.build()).execute()){
                    String raw=r.body()==null?"":r.body().string();
                    result=JsonParser.parseString(raw).getAsJsonObject();
                    if(!r.isSuccessful()||!result.has("ok")||!result.get("ok").getAsBoolean())
                        throw new IOException(str(result,"error","Request failed ("+r.code()+")"));
                }
            }catch(Exception ex){error=ex;}
            JsonObject finalResult=result;Exception finalError=error;
            MAIN.post(()->callback.done(finalResult,finalError));
        });
    }

    static String str(JsonObject o,String key,String fallback){try{JsonElement x=o==null?null:o.get(key);return x==null||x.isJsonNull()?fallback:x.getAsString();}catch(Exception e){return fallback;}}
    static int integer(JsonObject o,String key,int fallback){try{return o.get(key).getAsInt();}catch(Exception e){return fallback;}}
    static JsonArray array(JsonObject o,String key){try{return o.getAsJsonArray(key);}catch(Exception e){return new JsonArray();}}
    static JsonObject object(JsonObject o,String key){try{return o.getAsJsonObject(key);}catch(Exception e){return new JsonObject();}}
    static boolean bool(JsonObject o,String key){try{return o.get(key).getAsBoolean();}catch(Exception e){return false;}}
    static String absolute(String path){if(path==null||path.isEmpty())return "";if(path.startsWith("http://")||path.startsWith("https://"))return path;return BASE_URL+path.replaceFirst("^/+","");}
}
