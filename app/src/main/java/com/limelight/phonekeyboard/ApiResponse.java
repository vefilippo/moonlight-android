package com.limelight.phonekeyboard;

import java.util.Collections;
import java.util.Map;

/** Transport-independent HTTP response produced by {@link PhoneKeyboardApi}. */
public final class ApiResponse {
    public final int status;
    public final String mimeType;
    public final String body;
    public final Map<String, String> headers;

    public ApiResponse(int status, String mimeType, String body, Map<String, String> headers) {
        this.status = status;
        this.mimeType = mimeType;
        this.body = body;
        this.headers = Collections.unmodifiableMap(headers);
    }
}
