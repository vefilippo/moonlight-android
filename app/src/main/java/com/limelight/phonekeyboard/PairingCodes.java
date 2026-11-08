package com.limelight.phonekeyboard;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** One-time pairing codes that expire after {@link #TTL_MS}. */
public final class PairingCodes {
    public static final long TTL_MS = 300_000;
    // Plain hex (128 bits): android.util.Base64 is a stub in plain JUnit tests, which this class must support.
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Clock clock;
    private final Map<String, Long> expiries = new HashMap<>();

    public PairingCodes(Clock clock) {
        this.clock = clock;
    }

    public synchronized String issue() {
        purgeExpired();
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : raw) {
            sb.append(String.format("%02x", b));
        }
        String code = sb.toString();
        expiries.put(code, clock.nowMs() + TTL_MS);
        return code;
    }

    /** True exactly once for an issued, unexpired code. */
    public synchronized boolean consume(String code) {
        if (code == null) {
            return false;
        }
        purgeExpired();
        byte[] given = code.getBytes(StandardCharsets.UTF_8);
        String match = null;
        for (String known : expiries.keySet()) {
            if (MessageDigest.isEqual(known.getBytes(StandardCharsets.UTF_8), given)) {
                match = known;
            }
        }
        if (match == null) {
            return false;
        }
        expiries.remove(match);
        return true;
    }

    public synchronized void cancelAll() {
        expiries.clear();
    }

    public synchronized boolean hasActive() {
        purgeExpired();
        return !expiries.isEmpty();
    }

    private void purgeExpired() {
        long now = clock.nowMs();
        Iterator<Long> it = expiries.values().iterator();
        while (it.hasNext()) {
            if (it.next() < now) {
                it.remove();
            }
        }
    }
}
