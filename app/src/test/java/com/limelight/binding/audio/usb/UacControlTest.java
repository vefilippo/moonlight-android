package com.limelight.binding.audio.usb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class UacControlTest {
    static final class Call {
        int type, req, value, index, length;
        byte[] data;
    }

    interface Responder {
        /** Fill buffer and return the transfer result, or null to default (length). */
        int respond(Call c, byte[] buffer);
    }

    static final class FakeTransport implements UacControl.Transport {
        final List<Call> calls = new ArrayList<>();
        Responder responder = (c, b) -> c.length;

        @Override
        public int control(int requestType, int request, int value, int index,
                           byte[] buffer, int length, int timeoutMs) {
            Call c = new Call();
            c.type = requestType; c.req = request; c.value = value; c.index = index;
            c.length = length;
            c.data = buffer == null ? null : Arrays.copyOf(buffer, length);
            calls.add(c);
            return responder.respond(c, buffer);
        }
    }

    private static UacDescriptors desc(int version, int ctrlIf) {
        UacDescriptors d = new UacDescriptors();
        d.uacVersion = version;
        d.controlInterface = ctrlIf;
        return d;
    }

    private static UacDescriptors.AltSetting alt(int ep, boolean rateCtl, int clock) {
        UacDescriptors.AltSetting a = new UacDescriptors.AltSetting();
        a.endpointAddress = ep;
        a.hasSampleRateControl = rateCtl;
        a.clockSourceId = clock;
        return a;
    }

    private static void le(byte[] b, int off, int len, int v) {
        for (int i = 0; i < len; i++) b[off + i] = (byte) (v >> (8 * i));
    }

    @Test public void uac1SetsRateOnEndpoint() {
        FakeTransport t = new FakeTransport();
        UacControl c = new UacControl(t, desc(1, 0));
        assertTrue(c.setSampleRate(alt(0x01, true, -1), 48000));
        assertEquals(1, t.calls.size());
        Call k = t.calls.get(0);
        assertEquals(0x22, k.type);
        assertEquals(0x01, k.req);
        assertEquals(0x0100, k.value);
        assertEquals(0x0001, k.index);
        assertArrayEquals(new byte[]{(byte) 0x80, (byte) 0xBB, 0x00}, k.data);
    }

    @Test public void uac1WithoutRateControlSendsNothing() {
        FakeTransport t = new FakeTransport();
        UacControl c = new UacControl(t, desc(1, 0));
        assertTrue(c.setSampleRate(alt(0x01, false, -1), 48000));
        assertTrue(t.calls.isEmpty());
    }

    @Test public void uac2SetsRateOnClock() {
        FakeTransport t = new FakeTransport();
        UacControl c = new UacControl(t, desc(2, 0));
        assertTrue(c.setSampleRate(alt(0x01, false, 0x29), 48000));
        Call k = t.calls.get(0);
        assertEquals(0x21, k.type);
        assertEquals(0x01, k.req);
        assertEquals(0x0100, k.value);
        assertEquals(0x2900, k.index);
        assertArrayEquals(new byte[]{(byte) 0x80, (byte) 0xBB, 0x00, 0x00}, k.data);
    }

    @Test public void setSampleRateFalseOnShortTransfer() {
        FakeTransport t = new FakeTransport();
        t.responder = (c, b) -> -1;
        UacControl c = new UacControl(t, desc(2, 0));
        assertFalse(c.setSampleRate(alt(0x01, false, 0x29), 48000));
    }

    @Test public void uac2RangeFillsDiscreteAndContinuous() {
        FakeTransport t = new FakeTransport();
        t.responder = (c, b) -> {
            if (c.length == 2) {
                le(b, 0, 2, 2);
            } else {
                le(b, 0, 2, 2);
                le(b, 2, 4, 44100); le(b, 6, 4, 44100); le(b, 10, 4, 0);
                le(b, 14, 4, 48000); le(b, 18, 4, 96000); le(b, 22, 4, 1);
            }
            return c.length;
        };
        UacDescriptors d = desc(2, 0);
        UacDescriptors.AltSetting a = alt(0x01, false, 0x29);
        d.altSettings.add(a);
        new UacControl(t, d).fillUac2Rates();
        assertArrayEquals(new int[]{44100}, a.sampleRates);
        assertEquals(48000, a.minRate);
        assertEquals(96000, a.maxRate);
        assertEquals(2, t.calls.size());
        Call k = t.calls.get(1);
        assertEquals(0xA1, k.type);
        assertEquals(0x02, k.req);
        assertEquals(0x0100, k.value);
        assertEquals(0x2900, k.index);
        assertEquals(2 + 12 * 2, k.length);
    }

    @Test public void uac2RangeQueriesEachClockOnce() {
        FakeTransport t = new FakeTransport();
        t.responder = (c, b) -> {
            le(b, 0, 2, 1);
            if (c.length > 2) { le(b, 2, 4, 48000); le(b, 6, 4, 48000); le(b, 10, 4, 0); }
            return c.length;
        };
        UacDescriptors d = desc(2, 0);
        UacDescriptors.AltSetting a = alt(0x01, false, 0x29);
        UacDescriptors.AltSetting b2 = alt(0x81, false, 0x29);
        d.altSettings.add(a);
        d.altSettings.add(b2);
        new UacControl(t, d).fillUac2Rates();
        assertEquals(2, t.calls.size());
        assertArrayEquals(new int[]{48000}, a.sampleRates);
        assertArrayEquals(new int[]{48000}, b2.sampleRates);
    }

    @Test public void uac2RangeFailureDefaultsTo48k() {
        FakeTransport t = new FakeTransport();
        t.responder = (c, b) -> -1;
        UacDescriptors d = desc(2, 0);
        UacDescriptors.AltSetting a = alt(0x01, false, 0x29);
        d.altSettings.add(a);
        new UacControl(t, d).fillUac2Rates();
        assertArrayEquals(new int[]{48000}, a.sampleRates);
    }

    private static UacDescriptors.FeatureUnit fu(int id, boolean mute, boolean vol) {
        UacDescriptors.FeatureUnit f = new UacDescriptors.FeatureUnit();
        f.unitId = id; f.hasMasterMute = mute; f.hasMasterVolume = vol;
        return f;
    }

    private static FakeTransport uac1Volume(int max) {
        FakeTransport t = new FakeTransport();
        t.responder = (c, b) -> {
            if (c.req == 0x83) le(b, 0, 2, max);
            return c.length;
        };
        return t;
    }

    @Test public void unmutesAndCapsVolumeAt0dB() {
        FakeTransport t = uac1Volume(0x0600);
        UacDescriptors d = desc(1, 3);
        d.featureUnits.add(fu(5, true, true));
        new UacControl(t, d).unmuteAndSetUnityVolume();
        assertEquals(3, t.calls.size());
        Call m = t.calls.get(0);
        assertEquals(0x21, m.type); assertEquals(0x01, m.req);
        assertEquals(0x0100, m.value); assertEquals(0x0503, m.index);
        assertArrayEquals(new byte[]{0}, m.data);
        Call g = t.calls.get(1);
        assertEquals(0xA1, g.type); assertEquals(0x83, g.req);
        assertEquals(0x0200, g.value); assertEquals(0x0503, g.index);
        assertEquals(2, g.length);
        Call s = t.calls.get(2);
        assertEquals(0x21, s.type); assertEquals(0x01, s.req);
        assertEquals(0x0200, s.value); assertEquals(0x0503, s.index);
        assertArrayEquals(new byte[]{0, 0}, s.data);
    }

    @Test public void negativeMaxVolumeIsKept() {
        FakeTransport t = uac1Volume(0xF000);
        UacDescriptors d = desc(1, 0);
        d.featureUnits.add(fu(5, false, true));
        new UacControl(t, d).unmuteAndSetUnityVolume();
        Call s = t.calls.get(t.calls.size() - 1);
        assertEquals(0x01, s.req);
        assertArrayEquals(new byte[]{0x00, (byte) 0xF0}, s.data);
    }

    @Test public void uac2VolumeUsesRangeMax() {
        FakeTransport t = new FakeTransport();
        t.responder = (c, b) -> {
            if (c.req == 0x02) {
                le(b, 0, 2, 2);
                if (c.length > 2) {
                    le(b, 2, 2, 0xE000); le(b, 4, 2, 0xF000); le(b, 6, 2, 0x0100);
                    le(b, 8, 2, 0xF000); le(b, 10, 2, 0xF800); le(b, 12, 2, 0x0100);
                }
            }
            return c.length;
        };
        UacDescriptors d = desc(2, 0);
        d.featureUnits.add(fu(7, true, true));
        new UacControl(t, d).unmuteAndSetUnityVolume();
        // mute, range(2), range(14), set
        assertEquals(4, t.calls.size());
        assertEquals(2 + 6 * 2, t.calls.get(2).length);
        assertEquals(0xA1, t.calls.get(1).type);
        assertEquals(0x0200, t.calls.get(1).value);
        assertEquals(0x0700, t.calls.get(1).index);
        Call s = t.calls.get(3);
        assertEquals(0x21, s.type); assertEquals(0x01, s.req);
        assertArrayEquals(new byte[]{0x00, (byte) 0xF8}, s.data);
    }

    @Test public void unitySkipsTheHardwareVolumeUnit() {
        FakeTransport t = uac1Volume(0);
        UacDescriptors d = desc(1, 1);
        d.featureUnits.add(fu(2, true, true));
        d.featureUnits.add(fu(5, true, true));
        new UacControl(t, d).unmuteAndSetUnityVolume(5);
        assertEquals(3, t.calls.size());
        for (Call k : t.calls) assertEquals(0x0201, k.index);
    }

    @Test public void uac1ReadsVolumeMinMaxRes() {
        FakeTransport t = new FakeTransport();
        t.responder = (c, b) -> {
            if (c.req == 0x82) le(b, 0, 2, 0xC400);      // -60 dB
            else if (c.req == 0x83) le(b, 0, 2, 0x0600); // +6 dB
            else if (c.req == 0x84) le(b, 0, 2, 0x0080); // 0.5 dB
            return c.length;
        };
        UacControl.VolumeRange r = new UacControl(t, desc(1, 1)).readVolumeRange(5);
        assertNotNull(r);
        assertEquals(-60 * 256, r.min);
        assertEquals(6 * 256, r.max);
        assertEquals(128, r.res);
        assertEquals(3, t.calls.size());
        int[] reqs = {0x82, 0x83, 0x84};
        for (int i = 0; i < 3; i++) {
            Call k = t.calls.get(i);
            assertEquals(0xA1, k.type);
            assertEquals(reqs[i], k.req);
            assertEquals(0x0200, k.value);
            assertEquals(0x0501, k.index);
            assertEquals(2, k.length);
        }
    }

    @Test public void uac1VolumeRangeFailsWithoutMinOrMax() {
        FakeTransport t = new FakeTransport();
        t.responder = (c, b) -> c.req == 0x83 ? -1 : c.length;
        assertNull(new UacControl(t, desc(1, 1)).readVolumeRange(5));
    }

    @Test public void uac1MissingResDefaultsToOne() {
        FakeTransport t = new FakeTransport();
        t.responder = (c, b) -> {
            if (c.req == 0x84) return -1;
            if (c.req == 0x82) le(b, 0, 2, 0xE200);
            return c.length;
        };
        UacControl.VolumeRange r = new UacControl(t, desc(1, 1)).readVolumeRange(5);
        assertNotNull(r);
        assertEquals(1, r.res);
        assertEquals((short) 0xE200, r.min);
        assertEquals(0, r.max);
    }

    @Test public void uac2VolumeRangeSpansSubranges() {
        FakeTransport t = new FakeTransport();
        t.responder = (c, b) -> {
            le(b, 0, 2, 2);
            if (c.length > 2) {
                le(b, 2, 2, 0xE000); le(b, 4, 2, 0xF000); le(b, 6, 2, 0x0100);
                le(b, 8, 2, 0xF000); le(b, 10, 2, 0x0000); le(b, 12, 2, 0x0080);
            }
            return c.length;
        };
        UacControl.VolumeRange r = new UacControl(t, desc(2, 0)).readVolumeRange(7);
        assertNotNull(r);
        assertEquals((short) 0xE000, r.min);
        assertEquals(0, r.max);
        assertEquals(0x0100, r.res);
        assertEquals(0x02, t.calls.get(0).req);
        assertEquals(0x0700, t.calls.get(0).index);
    }

    @Test public void setVolumeAndMuteRequestBytes() {
        FakeTransport t = new FakeTransport();
        UacControl c = new UacControl(t, desc(1, 1));
        assertTrue(c.setVolume(5, -15 * 256));
        assertTrue(c.setMute(5, true));
        assertTrue(c.setMute(5, false));
        Call v = t.calls.get(0);
        assertEquals(0x21, v.type); assertEquals(0x01, v.req);
        assertEquals(0x0200, v.value); assertEquals(0x0501, v.index);
        assertArrayEquals(new byte[]{0x00, (byte) 0xF1}, v.data);
        Call m = t.calls.get(1);
        assertEquals(0x21, m.type); assertEquals(0x01, m.req);
        assertEquals(0x0100, m.value); assertEquals(0x0501, m.index);
        assertArrayEquals(new byte[]{1}, m.data);
        assertArrayEquals(new byte[]{0}, t.calls.get(2).data);
        t.responder = (k, b) -> -1;
        assertFalse(c.setVolume(5, 0));
        assertFalse(c.setMute(5, true));
    }

    @Test public void failuresAreIgnored() {
        FakeTransport t = new FakeTransport();
        t.responder = (c, b) -> -1;
        UacDescriptors d = desc(1, 0);
        d.featureUnits.add(fu(5, true, true));
        d.featureUnits.add(fu(6, true, false));
        new UacControl(t, d).unmuteAndSetUnityVolume();
        assertEquals(3, t.calls.size()); // mute5, getmax5, mute6 (no set after failed max)
    }
}
