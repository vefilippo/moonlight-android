package com.limelight.binding.audio.usb;

import static com.limelight.binding.audio.usb.UacFormatSelector.selectCapture;
import static com.limelight.binding.audio.usb.UacFormatSelector.selectPlayback;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class UacFormatSelectorTest {
    private static final boolean OUT = false;
    private static final boolean IN = true;

    private static UacDescriptors.AltSetting alt(boolean input, int ch, int sub, int... rates) {
        UacDescriptors.AltSetting a = new UacDescriptors.AltSetting();
        a.isInput = input;
        a.channels = ch;
        a.subframeBytes = sub;
        a.bitResolution = sub * 8;
        a.sampleRates = rates;
        return a;
    }

    @Test public void prefers48kStereo16() {
        UacDescriptors.AltSetting a1 = alt(OUT, 2, 2, 44100);
        UacDescriptors.AltSetting a2 = alt(OUT, 2, 3, 48000);
        UacDescriptors.AltSetting a3 = alt(OUT, 2, 2, 48000);
        UacStreamConfig c = selectPlayback(Arrays.asList(a1, a2, a3));
        assertEquals(48000, c.sampleRate);
        assertEquals(a3, c.alt);
        assertEquals(4, c.frameBytes());
    }

    @Test public void fallsBackTo441WhenNo48k() {
        assertEquals(44100, selectPlayback(Collections.singletonList(alt(OUT, 2, 2, 44100))).sampleRate);
    }

    @Test public void accepts24BitOnly() {
        assertEquals(3, selectPlayback(Collections.singletonList(alt(OUT, 2, 3, 48000))).alt.subframeBytes);
    }

    @Test public void acceptsMonoOutput() {
        assertEquals(1, selectPlayback(Collections.singletonList(alt(OUT, 1, 2, 48000))).alt.channels);
    }

    @Test public void captureOnlyDeviceHasNoPlayback() {
        assertNull(selectPlayback(Collections.singletonList(alt(IN, 1, 2, 16000))));
    }

    @Test public void capturePrefers48kMonoThen16k() {
        List<UacDescriptors.AltSetting> alts = Arrays.asList(alt(IN, 1, 2, 16000), alt(IN, 2, 2, 44100));
        assertEquals(16000, selectCapture(alts).sampleRate);
    }

    @Test public void continuousRangePicks48k() {
        UacDescriptors.AltSetting a = alt(OUT, 2, 2);
        a.minRate = 8000;
        a.maxRate = 96000;
        assertEquals(48000, selectPlayback(Collections.singletonList(a)).sampleRate);
    }

    @Test public void rejectsZeroChannelAndOddSubframe() {
        List<UacDescriptors.AltSetting> alts = Arrays.asList(alt(OUT, 0, 2, 48000), alt(OUT, 2, 1, 48000));
        assertNull(selectPlayback(alts));
    }

    @Test public void highestOtherRateUsed() {
        assertEquals(88200, selectPlayback(Collections.singletonList(alt(OUT, 2, 2, 22050, 88200))).sampleRate);
    }

    @Test public void playbackIgnoresInputAndRejectsNoRate() {
        assertNull(selectPlayback(Collections.singletonList(alt(OUT, 2, 2))));
        assertNull(selectCapture(Collections.singletonList(alt(OUT, 1, 2, 48000))));
    }
}
