package com.limelight.binding.audio.usb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UacHardwareVolumeTest {
    private static final int DB = 256;

    // ---- level -> 1/256 dB mapping ----

    @Test public void endsAreTopAndThirtyDbBelow() {
        assertEquals(0, UacHardwareVolume.volumeForLevel(15, -60 * DB, 0, 1));
        assertEquals(-30 * DB, UacHardwareVolume.volumeForLevel(1, -60 * DB, 0, 1));
    }

    @Test public void linearInDbBetweenEnds() {
        // 14 steps over 30 dB
        assertEquals(-15 * DB, UacHardwareVolume.volumeForLevel(8, -60 * DB, 0, 1));
        assertEquals(Math.round(-30 * DB + 30 * DB / 14f), UacHardwareVolume.volumeForLevel(2, -60 * DB, 0, 1));
    }

    @Test public void topIsCappedAt0dB() {
        assertEquals(0, UacHardwareVolume.volumeForLevel(15, -60 * DB, 6 * DB, 1));
        assertEquals(-30 * DB, UacHardwareVolume.volumeForLevel(1, -60 * DB, 6 * DB, 1));
    }

    @Test public void negativeMaxIsTheTop() {
        assertEquals(-4 * DB, UacHardwareVolume.volumeForLevel(15, -60 * DB, -4 * DB, 1));
        assertEquals(-34 * DB, UacHardwareVolume.volumeForLevel(1, -60 * DB, -4 * DB, 1));
    }

    @Test public void bottomIsClampedToMin() {
        assertEquals(-20 * DB, UacHardwareVolume.volumeForLevel(1, -20 * DB, 0, 1));
        assertEquals(0, UacHardwareVolume.volumeForLevel(15, -20 * DB, 0, 1));
        assertEquals(-10 * DB, UacHardwareVolume.volumeForLevel(8, -20 * DB, 0, 1));
    }

    @Test public void roundsToResolutionStepsFromMin() {
        // res 1.5 dB from -30 dB: level 2 is -27.86 dB -> nearest step -28.5 dB
        assertEquals(-30 * DB + 384, UacHardwareVolume.volumeForLevel(2, -30 * DB, 0, 384));
        // every result is min + k*res
        for (int level = 1; level <= 15; level++) {
            int v = UacHardwareVolume.volumeForLevel(level, -45 * DB, 0, 384);
            assertEquals(0, (v + 45 * DB) % 384);
        }
    }

    @Test public void roundingNeverExceedsTop() {
        // (0 - -7680) / 1000 = 7.68 steps: 8 steps would be +320 (above 0 dB), so 7
        assertEquals(-7680 + 7000, UacHardwareVolume.volumeForLevel(15, -7680, 100, 1000));
    }

    @Test public void levelsAreClampedAndMonotonic() {
        assertEquals(UacHardwareVolume.volumeForLevel(15, -60 * DB, 0, 1),
                UacHardwareVolume.volumeForLevel(99, -60 * DB, 0, 1));
        assertEquals(UacHardwareVolume.volumeForLevel(1, -60 * DB, 0, 1),
                UacHardwareVolume.volumeForLevel(0, -60 * DB, 0, 1));
        int prev = Integer.MIN_VALUE;
        for (int level = 1; level <= 15; level++) {
            int v = UacHardwareVolume.volumeForLevel(level, -50 * DB, 0, 128);
            assertTrue(v >= prev);
            prev = v;
        }
    }

    @Test public void badResolutionAndInvertedRangeAreSafe() {
        assertEquals(-15 * DB, UacHardwareVolume.volumeForLevel(8, -60 * DB, 0, 0));
        assertEquals(-15 * DB, UacHardwareVolume.volumeForLevel(8, -60 * DB, 0, -5));
        // max below min: everything is min
        assertEquals(-10 * DB, UacHardwareVolume.volumeForLevel(15, -10 * DB, -20 * DB, 1));
        assertEquals(-10 * DB, UacHardwareVolume.volumeForLevel(1, -10 * DB, -20 * DB, 1));
    }

    // ---- request sequences ----

    private static UacDescriptors.FeatureUnit fu(int id, boolean mute) {
        UacDescriptors.FeatureUnit f = new UacDescriptors.FeatureUnit();
        f.unitId = id;
        f.hasMasterMute = mute;
        f.hasMasterVolume = true;
        return f;
    }

    private static UacControlTest.FakeTransport rangeTransport() {
        UacControlTest.FakeTransport t = new UacControlTest.FakeTransport();
        t.responder = (c, b) -> {
            int v = c.req == 0x82 ? -60 * DB : c.req == 0x83 ? 0 : c.req == 0x84 ? DB : 0;
            if (c.type == 0xA1) { b[0] = (byte) v; b[1] = (byte) (v >> 8); }
            return c.length;
        };
        return t;
    }

    private static UacDescriptors desc() {
        UacDescriptors d = new UacDescriptors();
        d.uacVersion = 1;
        d.controlInterface = 1;
        return d;
    }

    @Test public void createReadsRangeAndFailsWithoutIt() {
        UacControlTest.FakeTransport t = rangeTransport();
        UacHardwareVolume hv = UacHardwareVolume.create(new UacControl(t, desc()), fu(5, true));
        assertNotNull(hv);
        assertEquals(5, hv.unitId());
        assertEquals(3, t.calls.size());

        UacControlTest.FakeTransport bad = new UacControlTest.FakeTransport();
        bad.responder = (c, b) -> -1;
        assertNull(UacHardwareVolume.create(new UacControl(bad, desc()), fu(5, true)));
    }

    @Test public void levelZeroMutes() {
        UacControlTest.FakeTransport t = rangeTransport();
        UacHardwareVolume hv = UacHardwareVolume.create(new UacControl(t, desc()), fu(5, true));
        t.calls.clear();
        hv.apply(0);
        assertEquals(1, t.calls.size());
        UacControlTest.Call m = t.calls.get(0);
        assertEquals(0x21, m.type); assertEquals(0x01, m.req);
        assertEquals(0x0100, m.value); assertEquals(0x0501, m.index);
        assertArrayEquals(new byte[]{1}, m.data);
    }

    @Test public void levelSetsVolumeThenUnmutes() {
        UacControlTest.FakeTransport t = rangeTransport();
        UacHardwareVolume hv = UacHardwareVolume.create(new UacControl(t, desc()), fu(5, true));
        t.calls.clear();
        hv.apply(8);
        assertEquals(2, t.calls.size());
        UacControlTest.Call v = t.calls.get(0);
        assertEquals(0x0200, v.value); assertEquals(0x0501, v.index);
        assertArrayEquals(new byte[]{0x00, (byte) 0xF1}, v.data); // -15 dB
        UacControlTest.Call m = t.calls.get(1);
        assertEquals(0x0100, m.value);
        assertArrayEquals(new byte[]{0}, m.data);
    }

    @Test public void withoutMuteControlLevelZeroGoesToMin() {
        UacControlTest.FakeTransport t = rangeTransport();
        UacHardwareVolume hv = UacHardwareVolume.create(new UacControl(t, desc()), fu(5, false));
        t.calls.clear();
        hv.apply(0);
        assertEquals(1, t.calls.size());
        assertEquals(0x0200, t.calls.get(0).value);
        assertArrayEquals(new byte[]{0x00, (byte) 0xC4}, t.calls.get(0).data); // -60 dB
        t.calls.clear();
        hv.apply(15);
        assertEquals(1, t.calls.size());
        assertArrayEquals(new byte[]{0, 0}, t.calls.get(0).data);
    }
}
