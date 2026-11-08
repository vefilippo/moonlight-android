package com.limelight.phonekeyboard;

/** A phone that has been paired with the keyboard server. */
public final class PairedPhone {
    public final String id;
    public final String label;
    public final String secret;
    public final long createdAtMs;
    public final long lastUsedMs;

    public PairedPhone(String id, String label, String secret, long createdAtMs, long lastUsedMs) {
        this.id = id;
        this.label = label;
        this.secret = secret;
        this.createdAtMs = createdAtMs;
        this.lastUsedMs = lastUsedMs;
    }
}
