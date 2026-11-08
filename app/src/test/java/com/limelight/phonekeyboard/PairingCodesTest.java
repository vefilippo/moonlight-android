package com.limelight.phonekeyboard;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PairingCodesTest {
    private long now = 0;
    private final PairingCodes codes = new PairingCodes(() -> now);

    @Test public void codeIsOneTime() { String c = codes.issue(); assertTrue(codes.consume(c)); assertFalse(codes.consume(c)); }
    @Test public void codeExpiresAfterTtl() { String c = codes.issue(); now += PairingCodes.TTL_MS + 1; assertFalse(codes.consume(c)); assertFalse(codes.hasActive()); }
    @Test public void cancelAllInvalidates() { String c = codes.issue(); codes.cancelAll(); assertFalse(codes.consume(c)); }
}
