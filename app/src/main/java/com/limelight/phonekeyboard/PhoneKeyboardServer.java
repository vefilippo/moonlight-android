package com.limelight.phonekeyboard;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/** Thin NanoHTTPD transport over {@link PhoneKeyboardApi}. Request bodies are never logged. */
final class PhoneKeyboardServer extends NanoHTTPD {
    static final int PORT = 47900;
    static final int MAX_BODY_BYTES = 64 * 1024;

    private final PhoneKeyboardApi api;

    PhoneKeyboardServer(PhoneKeyboardApi api) {
        super(PORT);
        this.api = api;
    }

    @Override
    public Response serve(IHTTPSession session) {
        Map<String, String> headers = session.getHeaders();
        String body = "";
        if (session.getMethod() == Method.POST) {
            int length = parseLength(headers.get("content-length"));
            if (length < 0 || length > MAX_BODY_BYTES) {
                return newFixedLengthResponse(Response.Status.lookup(413), "text/plain", "too large");
            }
            try {
                body = readBody(session.getInputStream(), length);
            } catch (IOException e) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "bad request");
            }
        }
        ApiResponse r = api.handle(session.getMethod().name(), session.getUri(), headers, body);
        Response response = newFixedLengthResponse(Response.Status.lookup(r.status), r.mimeType, r.body);
        for (Map.Entry<String, String> h : r.headers.entrySet()) {
            response.addHeader(h.getKey(), h.getValue());
        }
        return response;
    }

    private static int parseLength(String value) {
        if (value == null) return 0;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String readBody(InputStream in, int length) throws IOException {
        byte[] buf = new byte[length];
        int off = 0;
        while (off < length) {
            int n = in.read(buf, off, length - off);
            if (n < 0) break;
            off += n;
        }
        return new String(buf, 0, off, StandardCharsets.UTF_8);
    }
}
