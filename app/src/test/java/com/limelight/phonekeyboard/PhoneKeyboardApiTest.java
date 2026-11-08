package com.limelight.phonekeyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33})
public class PhoneKeyboardApiTest {
    private long now = 0;
    private PairedPhoneStore store;
    private PairingCodes codes;
    private PhoneKeyboardHub hub;
    private RecordingSink sink;
    private PhoneKeyboardApi api;
    private PairedPhone phone;
    private final boolean[] paired = {false};

    @Before public void setUp() {
        SharedPreferences prefs = ((android.content.Context) ApplicationProvider.getApplicationContext())
                .getSharedPreferences("test_api_phones", 0);
        prefs.edit().clear().commit();
        Clock clock = () -> now;
        store = new PairedPhoneStore(prefs, clock);
        codes = new PairingCodes(clock);
        hub = new PhoneKeyboardHub(Runnable::run);
        sink = new RecordingSink();
        api = new PhoneKeyboardApi(store, codes, hub, "<html>page</html>", () -> paired[0] = true);
        phone = store.addPhone("test");
    }

    private static Map<String, String> ua(String s) {
        return Collections.singletonMap("user-agent", s);
    }
    private static Map<String, String> noHeaders() { return new HashMap<>(); }
    private static Map<String, String> key(PairedPhone p) {
        return Collections.singletonMap("x-pheme-key", p.secret);
    }
    private static String json(String text) {
        try { return new JSONObject().put("text", text).toString(); } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test public void pairRedirectsToPhonePageOnce() {
        String code = codes.issue();
        ApiResponse r = api.handle("GET", "/pair/" + code, ua("Mozilla (iPhone; CPU)"), "");
        assertEquals(302, r.status);
        assertEquals("no-store", r.headers.get("Cache-Control"));
        String secret = r.headers.get("Location").substring("/k/".length());
        assertEquals("iPhone", store.findBySecret(secret).label);
        assertTrue(paired[0]);
        assertEquals(410, api.handle("GET", "/pair/" + code, ua(""), "").status);
    }
    @Test public void pairLabelsFromUserAgent() {
        String[][] cases = {{"iPad", "iPad"}, {"Linux; Android 14", "Android phone"}, {"curl", "Phone"}};
        for (String[] c : cases) {
            ApiResponse r = api.handle("GET", "/pair/" + codes.issue(), ua(c[0]), "");
            assertEquals(c[1], store.findBySecret(r.headers.get("Location").substring(3)).label);
        }
        ApiResponse r = api.handle("GET", "/pair/" + codes.issue(), null, "");
        assertEquals("Phone", store.findBySecret(r.headers.get("Location").substring(3)).label);
    }
    @Test public void expiredOrWrongCodeIs410() {
        String code = codes.issue();
        now += PairingCodes.TTL_MS + 1;
        assertEquals(410, api.handle("GET", "/pair/" + code, ua(""), "").status);
        assertEquals(410, api.handle("GET", "/pair/nope", ua(""), "").status);
    }
    @Test public void pageNeedsValidSecret() {
        ApiResponse ok = api.handle("GET", "/k/" + phone.secret, noHeaders(), "");
        assertEquals(200, ok.status);
        assertEquals("<html>page</html>", ok.body);
        assertEquals(403, api.handle("GET", "/k/wrong", noHeaders(), "").status);
    }
    @Test public void strictPathsAreNotFound() {
        assertEquals(404, api.handle("GET", "/k/", noHeaders(), "").status);
        assertEquals(404, api.handle("GET", "/k/" + phone.secret + "/x", noHeaders(), "").status);
        assertEquals(404, api.handle("GET", "/pair/", noHeaders(), "").status);
        assertEquals(404, api.handle("GET", "/api/status/", key(phone), "").status);
        assertEquals(404, api.handle("POST", "/k/" + phone.secret, noHeaders(), "").status);
        assertEquals(404, api.handle("GET", "/api/text", key(phone), "").status);
        assertEquals(404, api.handle("POST", "/api/status", key(phone), "").status);
        assertEquals(404, api.handle("PUT", "/api/text", key(phone), json("a")).status);
        assertEquals(404, api.handle(null, null, null, null).status);
    }
    @Test public void statusReflectsStream() {
        assertEquals("{\"state\":\"waiting\"}", api.handle("GET", "/api/status", key(phone), "").body);
        hub.attach(sink);
        ApiResponse r = api.handle("GET", "/api/status", key(phone), "");
        assertEquals("{\"state\":\"ready\"}", r.body);
        assertEquals("application/json", r.mimeType);
    }
    @Test public void missingOrBadKeyIs401AndTypesNothing() {
        hub.attach(sink);
        assertEquals(401, api.handle("GET", "/api/status", noHeaders(), "").status);
        assertEquals(401, api.handle("GET", "/api/status", null, "").status);
        assertEquals(401, api.handle("POST", "/api/text", noHeaders(), json("a")).status);
        assertEquals(401, api.handle("POST", "/api/text", Collections.singletonMap("x-pheme-key", "bad"), json("a")).status);
        assertEquals(401, api.handle("POST", "/api/key", null, "{\"key\":\"enter\"}").status);
        assertTrue(sink.events.isEmpty());
    }
    @Test public void textIsTypedWhenStreaming() {
        hub.attach(sink);
        ApiResponse r = api.handle("POST", "/api/text", key(phone), "{\"text\":\"a!\"}");
        assertEquals(200, r.status);
        assertEquals("{\"ok\":true}", r.body);
        assertEquals(Arrays.asList("key:65:false", "text:!"), sink.events);
    }
    @Test public void successfulTypingMarksUsed() {
        hub.attach(sink);
        now = 100_000;
        api.handle("POST", "/api/text", key(phone), json("a"));
        assertEquals(100_000, store.list().get(0).lastUsedMs);
    }
    @Test public void textWithoutStreamIs409() {
        ApiResponse r = api.handle("POST", "/api/text", key(phone), "{\"text\":\"a\"}");
        assertEquals(409, r.status);
        assertEquals("{\"error\":\"waiting\"}", r.body);
    }
    @Test public void tooLongTextIs413() {
        hub.attach(sink);
        assertEquals(413, api.handle("POST", "/api/text", key(phone), json("a".repeat(4001))).status);
        assertEquals(200, api.handle("POST", "/api/text", key(phone), json("a".repeat(4000))).status);
    }
    @Test public void badJsonOrMissingTextIs400() {
        hub.attach(sink);
        assertEquals(400, api.handle("POST", "/api/text", key(phone), "{").status);
        assertEquals(400, api.handle("POST", "/api/text", key(phone), "{}").status);
        assertEquals(400, api.handle("POST", "/api/text", key(phone), "{\"text\":5}").status);
        assertEquals(400, api.handle("POST", "/api/text", key(phone), null).status);
        assertTrue(sink.events.isEmpty());
    }
    @Test public void specialKeyIsPressedOrWaiting() {
        assertEquals(409, api.handle("POST", "/api/key", key(phone), "{\"key\":\"enter\"}").status);
        hub.attach(sink);
        assertEquals(200, api.handle("POST", "/api/key", key(phone), "{\"key\":\"enter\"}").status);
        assertEquals(1, sink.events.size());
    }
    @Test public void unknownSpecialKeyIs400() {
        hub.attach(sink);
        assertEquals(400, api.handle("POST", "/api/key", key(phone), "{\"key\":\"f13\"}").status);
        assertEquals(400, api.handle("POST", "/api/key", key(phone), "{}").status);
    }
    @Test public void api_rejectsRemovedPhone() {
        store.remove(phone.id);
        assertEquals(401, api.handle("GET", "/api/status", key(phone), "").status);
        assertEquals(401, api.handle("POST", "/api/text", key(phone), "{\"text\":\"a\"}").status);
    }
    @Test public void unknownPathIs404() {
        assertEquals(404, api.handle("GET", "/etc/passwd", noHeaders(), "").status);
    }
}
