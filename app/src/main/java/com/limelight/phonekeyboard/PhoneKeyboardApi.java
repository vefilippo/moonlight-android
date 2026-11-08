package com.limelight.phonekeyboard;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Request handling for the phone keyboard server, independent of the HTTP transport.
 * Only the exact routes below are served; every typing route requires a paired phone's key.
 * Request text is never logged or put in exception messages.
 */
public final class PhoneKeyboardApi {
    public static final int MAX_TEXT_CHARS = 4000;
    public static final String KEY_HEADER = "x-pheme-key";

    private static final String HTML = "text/html";
    private static final String JSON = "application/json";

    private final PairedPhoneStore store;
    private final PairingCodes codes;
    private final PhoneKeyboardHub hub;
    private final String pageHtml;
    private final Runnable onPaired;

    public PhoneKeyboardApi(PairedPhoneStore store, PairingCodes codes, PhoneKeyboardHub hub,
                            String pageHtml, Runnable onPaired) {
        this.store = store;
        this.codes = codes;
        this.hub = hub;
        this.pageHtml = pageHtml;
        this.onPaired = onPaired;
    }

    public ApiResponse handle(String method, String path, Map<String, String> headers, String body) {
        if (method == null || path == null) return notFound();
        boolean get = method.equals("GET");
        boolean post = method.equals("POST");

        if (get && path.startsWith("/pair/")) {
            String code = singleSegment(path, "/pair/");
            return code == null ? notFound() : pair(code, headers);
        }
        if (get && path.startsWith("/k/")) {
            String secret = singleSegment(path, "/k/");
            return secret == null ? notFound() : page(secret);
        }
        if (get && path.equals("/api/status")) {
            if (authenticate(headers) == null) return json(401, error("unauthorized"));
            return json(200, single("state", hub.isStreamActive() ? "ready" : "waiting"));
        }
        if (post && path.equals("/api/text")) return text(headers, body);
        if (post && path.equals("/api/key")) return key(headers, body);
        return notFound();
    }

    private ApiResponse pair(String code, Map<String, String> headers) {
        if (!codes.consume(code)) {
            return html(410, "This pairing link has expired. Show the QR code on the TV again.");
        }
        PairedPhone phone = store.addPhone(labelFor(headers == null ? null : headers.get("user-agent")));
        if (onPaired != null) onPaired.run();
        Map<String, String> h = new HashMap<>();
        h.put("Location", "/k/" + phone.secret);
        return build(302, HTML, "", h);
    }

    private ApiResponse page(String secret) {
        if (store.findBySecret(secret) == null) {
            return html(403, "This phone isn't paired (or was removed). Pair it again from Pheme on the TV.");
        }
        return html(200, pageHtml);
    }

    private ApiResponse text(Map<String, String> headers, String body) {
        PairedPhone phone = authenticate(headers);
        if (phone == null) return json(401, error("unauthorized"));
        JSONObject obj = parse(body);
        Object text = obj == null ? null : obj.opt("text");
        if (!(text instanceof String)) return json(400, error("bad request"));
        String s = (String) text;
        if (s.length() > MAX_TEXT_CHARS) return json(413, error("too long"));
        List<TypingAction> actions = TextSplitter.split(s);
        if (!hub.type(actions)) return json(409, error("waiting"));
        store.markUsed(phone.id);
        return json(200, okBody());
    }

    private ApiResponse key(Map<String, String> headers, String body) {
        PairedPhone phone = authenticate(headers);
        if (phone == null) return json(401, error("unauthorized"));
        JSONObject obj = parse(body);
        Object name = obj == null ? null : obj.opt("key");
        SpecialKey key = name instanceof String ? SpecialKey.fromWireName((String) name) : null;
        if (key == null) return json(400, error("bad request"));
        if (!hub.press(key)) return json(409, error("waiting"));
        store.markUsed(phone.id);
        return json(200, okBody());
    }

    private PairedPhone authenticate(Map<String, String> headers) {
        if (headers == null) return null;
        return store.findBySecret(headers.get(KEY_HEADER));
    }

    /** The remainder of path after prefix, or null if empty or containing another '/'. */
    private static String singleSegment(String path, String prefix) {
        String rest = path.substring(prefix.length());
        if (rest.isEmpty() || rest.indexOf('/') >= 0) return null;
        return rest;
    }

    private static JSONObject parse(String body) {
        if (body == null) return null;
        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            return null;
        }
    }

    private static String labelFor(String userAgent) {
        if (userAgent == null) return "Phone";
        if (userAgent.contains("iPhone")) return "iPhone";
        if (userAgent.contains("iPad")) return "iPad";
        if (userAgent.contains("Android")) return "Android phone";
        return "Phone";
    }

    private static String single(String name, Object value) {
        try {
            return new JSONObject().put(name, value).toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    private static String error(String message) {
        return single("error", message);
    }

    private static String okBody() {
        return single("ok", true);
    }

    private static ApiResponse notFound() {
        return html(404, "Not found");
    }

    private static ApiResponse html(int status, String body) {
        return build(status, HTML, body, new HashMap<String, String>());
    }

    private static ApiResponse json(int status, String body) {
        return build(status, JSON, body, new HashMap<String, String>());
    }

    private static ApiResponse build(int status, String mime, String body, Map<String, String> h) {
        h.put("Cache-Control", "no-store");
        return new ApiResponse(status, mime, body, h);
    }
}
