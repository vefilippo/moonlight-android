package com.limelight.phonekeyboard;

import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/** Persists paired phones (JSON array in SharedPreferences under "phones"). */
public final class PairedPhoneStore {
    static final String KEY = "phones";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SharedPreferences prefs;
    private final LongSupplier clock;

    public PairedPhoneStore(SharedPreferences prefs, LongSupplier clock) {
        this.prefs = prefs;
        this.clock = clock;
    }

    public synchronized PairedPhone addPhone(String label) {
        long now = clock.getAsLong();
        PairedPhone phone = new PairedPhone(randomToken(9), label, randomToken(32), now, 0L);
        List<PairedPhone> phones = load();
        phones.add(phone);
        save(phones);
        return phone;
    }

    public synchronized PairedPhone findBySecret(String secret) {
        if (secret == null) {
            return null;
        }
        byte[] given = secret.getBytes(StandardCharsets.UTF_8);
        PairedPhone found = null;
        for (PairedPhone p : load()) {
            if (MessageDigest.isEqual(p.secret.getBytes(StandardCharsets.UTF_8), given)) {
                found = p;
            }
        }
        return found;
    }

    public synchronized void markUsed(String id) {
        List<PairedPhone> phones = load();
        boolean changed = false;
        for (int i = 0; i < phones.size(); i++) {
            PairedPhone p = phones.get(i);
            if (p.id.equals(id)) {
                phones.set(i, new PairedPhone(p.id, p.label, p.secret, p.createdAtMs, clock.getAsLong()));
                changed = true;
            }
        }
        if (changed) {
            save(phones);
        }
    }

    public synchronized boolean remove(String id) {
        List<PairedPhone> phones = load();
        boolean removed = false;
        for (int i = phones.size() - 1; i >= 0; i--) {
            if (phones.get(i).id.equals(id)) {
                phones.remove(i);
                removed = true;
            }
        }
        if (removed) {
            save(phones);
        }
        return removed;
    }

    /** Oldest first. */
    public synchronized List<PairedPhone> list() {
        return load();
    }

    public synchronized boolean isEmpty() {
        return load().isEmpty();
    }

    private List<PairedPhone> load() {
        List<PairedPhone> out = new ArrayList<>();
        String json = prefs.getString(KEY, null);
        if (json == null) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new PairedPhone(o.getString("id"), o.getString("label"), o.getString("secret"),
                        o.getLong("createdAtMs"), o.getLong("lastUsedMs")));
            }
        } catch (JSONException e) {
            return new ArrayList<>();
        }
        return out;
    }

    private void save(List<PairedPhone> phones) {
        JSONArray arr = new JSONArray();
        try {
            for (PairedPhone p : phones) {
                arr.put(new JSONObject()
                        .put("id", p.id)
                        .put("label", p.label)
                        .put("secret", p.secret)
                        .put("createdAtMs", p.createdAtMs)
                        .put("lastUsedMs", p.lastUsedMs));
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        prefs.edit().putString(KEY, arr.toString()).apply();
    }

    static String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }
}
