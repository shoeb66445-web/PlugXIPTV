package app.plugxaura.iptv;

import android.util.Base64;
import com.google.gson.*;
import java.util.LinkedHashMap;
import java.util.Map;

/** Converts supplied ClearKey material to a complete JWK response. Never logs keys. */
final class ClearKeySet {
    private static final int MAX_KEYS = 1024;
    private final Map<String, String> pairs = new LinkedHashMap<>();

    /** Feed's explicit no-key marker, not a shortened or padded DRM key. */
    static boolean isPlaceholder(String input) {
        return input != null && input.trim().matches("0000\\s*:\\s*0000");
    }

    static String toJwk(String input) {
        if (input == null || input.length() > 262144) throw invalid();
        ClearKeySet set = new ClearKeySet();
        String text = input.trim();
        try {
            if (text.startsWith("{") || text.startsWith("[")) {
                set.readJson(JsonParser.parseString(text));
            } else {
                for (String pair : text.split("[,;\\r\\n]+", -1)) {
                    String[] parts = pair.trim().split(":", -1);
                    if (parts.length != 2) throw invalid();
                    set.add(parts[0], parts[1]);
                }
            }
            if (set.pairs.isEmpty()) throw invalid();
            JsonArray keys = new JsonArray();
            for (Map.Entry<String, String> pair : set.pairs.entrySet()) {
                JsonObject key = new JsonObject();
                key.addProperty("kty", "oct");
                key.addProperty("kid", pair.getKey());
                key.addProperty("k", pair.getValue());
                keys.add(key);
            }
            JsonObject result = new JsonObject();
            result.add("keys", keys);
            result.addProperty("type", "temporary");
            return result.toString();
        } catch (RuntimeException e) {
            // Parser exceptions can include the supplied secret. Expose only a generic error.
            throw invalid();
        }
    }

    private void readJson(JsonElement value) {
        if (value.isJsonArray()) {
            for (JsonElement key : value.getAsJsonArray()) readRecord(key.getAsJsonObject());
        } else if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (object.has("keys")) {
                if (!object.get("keys").isJsonArray()) throw invalid();
                readJson(object.get("keys"));
            } else if (object.has("kid")) {
                readRecord(object);
            } else {
                for (Map.Entry<String, JsonElement> pair : object.entrySet()) add(pair.getKey(), string(pair.getValue()));
            }
        } else throw invalid();
    }

    private void readRecord(JsonObject key) {
        if (key.has("kty") && !"oct".equals(string(key.get("kty")))) throw invalid();
        add(string(key.get("kid")), string(key.has("k") ? key.get("k") : key.get("key")));
    }

    private static String string(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid();
        return value.getAsString();
    }

    private void add(String kid, String key) {
        String id = encode(kid), secret = encode(key);
        String previous = pairs.get(id);
        if (previous != null && !previous.equals(secret)) throw invalid();
        pairs.put(id, secret);
        if (pairs.size() > MAX_KEYS) throw invalid();
    }

    private static String encode(String value) {
        String text = value.trim();
        if (text.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) text = text.replace("-", "");
        byte[] bytes;
        if (text.matches("[0-9a-fA-F]{32}")) {
            bytes = new byte[16];
            for (int i = 0; i < 16; i++) bytes[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        } else {
            if (!text.matches("[A-Za-z0-9_+/\\-]{22}(==)?")) throw invalid();
            bytes = Base64.decode(text.replace('-', '+').replace('_', '/'), Base64.DEFAULT);
        }
        if (bytes.length != 16) throw invalid();
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid ClearKey configuration");
    }
}

