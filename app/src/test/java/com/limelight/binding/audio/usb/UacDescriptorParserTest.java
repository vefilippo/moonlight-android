package com.limelight.binding.audio.usb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.limelight.binding.audio.usb.UacDescriptors.AltSetting;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class UacDescriptorParserTest {

    /** Loads whitespace-separated hex bytes; lines starting with '#' are comments. */
    private static byte[] fixture(String name) {
        try (InputStream in = UacDescriptorParserTest.class.getResourceAsStream("/usb/" + name)) {
            assertNotNull("missing fixture " + name, in);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().startsWith("#")) continue;
                for (String tok : line.trim().split("\\s+")) {
                    if (!tok.isEmpty()) out.write(Integer.parseInt(tok, 16));
                }
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /** Concatenates descriptors given as int arrays. */
    private static byte[] bytes(int[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int[] p : parts) for (int b : p) out.write(b);
        return out.toByteArray();
    }

    private static AltSetting first(UacDescriptors d, boolean input) {
        for (AltSetting a : d.altSettings) if (a.isInput == input) return a;
        return null;
    }

    @Test
    public void parsesUac1Headset() {
        UacDescriptors d = UacDescriptorParser.parse(fixture("uac1_headset_descriptors.hex"));
        assertNotNull(d);
        assertEquals(1, d.uacVersion);
        assertEquals(0, d.controlInterface);
        AltSetting out = first(d, false), in = first(d, true);
        assertNotNull(out);
        assertNotNull(in);
        assertEquals(2, out.channels);
        assertTrue(out.supportsRate(48000));
        assertFalse(out.supportsRate(44100));
        assertEquals(2, out.subframeBytes);
        assertEquals(16, out.bitResolution);
        assertEquals(0, out.endpointAddress & 0x80);
        assertEquals(0x01, out.endpointAddress);
        assertEquals(192, out.maxPacketBytes);
        assertEquals(1, out.bInterval);
        assertEquals(2, out.syncType);
        assertEquals(0, out.feedbackEndpointAddress);
        assertTrue(out.hasSampleRateControl);
        assertEquals(1, out.terminalLink);
        assertEquals(-1, out.clockSourceId);
        assertEquals(0x80, in.endpointAddress & 0x80);
        assertEquals(1, in.channels);
        assertTrue(in.supportsRate(16000));
        assertEquals(1, in.syncType);
        assertFalse(in.hasSampleRateControl);
        assertNotEquals(out.interfaceNumber, in.interfaceNumber);
        assertEquals(2, d.featureUnits.size());
        assertEquals(2, d.featureUnits.get(0).unitId);
        assertTrue(d.featureUnits.get(0).hasMasterMute);
        assertTrue(d.featureUnits.get(0).hasMasterVolume);
    }

    @Test
    public void ignoresHidAndZeroBandwidthAlts() {
        UacDescriptors d = UacDescriptorParser.parse(fixture("uac1_headset_descriptors.hex"));
        assertNotNull(d);
        assertEquals(2, d.altSettings.size());
        for (AltSetting a : d.altSettings) {
            assertNotEquals(0, a.alternateSetting);
            assertNotEquals(3, a.interfaceNumber); // HID interface
            assertNotEquals(0, a.interfaceNumber); // AudioControl
        }
    }

    /** Real descriptors of a Logitech G435 receiver (UAC1, two HID interfaces 0 and 4). */
    @Test
    public void parsesG435() {
        UacDescriptors d = UacDescriptorParser.parse(fixture("g435_descriptors.hex"));
        assertNotNull(d);
        assertEquals(1, d.uacVersion);
        assertEquals(1, d.controlInterface);
        assertEquals(2, d.altSettings.size());
        for (AltSetting a : d.altSettings) {
            assertNotEquals(0, a.interfaceNumber); // HID
            assertNotEquals(4, a.interfaceNumber); // HID
            assertNotEquals(0, a.alternateSetting);
        }

        AltSetting out = first(d, false);
        assertNotNull(out);
        assertEquals(3, out.interfaceNumber);
        assertEquals(1, out.alternateSetting);
        assertEquals(2, out.channels);
        assertEquals(2, out.subframeBytes);
        assertEquals(16, out.bitResolution);
        assertTrue(out.supportsRate(48000));
        assertFalse(out.supportsRate(44100));
        assertEquals(0x06, out.endpointAddress);
        assertEquals(208, out.maxPacketBytes);
        assertEquals(1, out.bInterval);
        assertEquals(3, out.syncType);
        assertEquals(0, out.feedbackEndpointAddress);

        AltSetting in = first(d, true);
        assertNotNull(in);
        assertEquals(2, in.interfaceNumber);
        assertEquals(1, in.alternateSetting);
        assertEquals(1, in.channels);
        assertEquals(2, in.subframeBytes);
        assertEquals(16, in.bitResolution);
        assertTrue(in.supportsRate(48000));
        assertEquals(0x87, in.endpointAddress);
        assertEquals(104, in.maxPacketBytes);
        assertEquals(3, in.syncType);

        assertEquals(2, d.featureUnits.size());
        for (UacDescriptors.FeatureUnit fu : d.featureUnits) {
            assertTrue(fu.hasMasterMute);
            assertTrue(fu.hasMasterVolume);
        }
    }

    // Synthetic UAC2 headset: IAD, AC header, clock source, terminals, one async OUT alt with feedback.
    private static byte[] uac2() {
        return bytes(
            new int[]{0x09, 0x02, 0x00, 0x00, 0x02, 0x01, 0x00, 0x80, 0x32},
            new int[]{0x08, 0x0B, 0x00, 0x02, 0x01, 0x00, 0x20, 0x00},              // IAD
            new int[]{0x09, 0x04, 0x00, 0x00, 0x00, 0x01, 0x01, 0x20, 0x00},        // AC iface
            new int[]{0x09, 0x24, 0x01, 0x00, 0x02, 0x04, 0x40, 0x00, 0x00},        // header bcdADC 2.00
            new int[]{0x08, 0x24, 0x0A, 0x09, 0x01, 0x03, 0x00, 0x00},              // clock source 9
            new int[]{0x11, 0x24, 0x02, 0x01, 0x01, 0x01, 0x00, 0x09, 0x02,
                0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00},                   // IT 1, clock 9
            new int[]{0x0C, 0x24, 0x03, 0x03, 0x02, 0x03, 0x00, 0x01, 0x09,
                0x00, 0x00, 0x00},                                                 // OT 3, src 1, clock 9
            new int[]{0x12, 0x24, 0x06, 0x02, 0x01, 0x0F, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00},             // FU 2: master mute+vol
            new int[]{0x09, 0x04, 0x01, 0x00, 0x00, 0x01, 0x02, 0x20, 0x00},        // AS alt 0
            new int[]{0x09, 0x04, 0x01, 0x01, 0x02, 0x01, 0x02, 0x20, 0x00},        // AS alt 1 (2 eps)
            new int[]{0x10, 0x24, 0x01, 0x01, 0x00, 0x01, 0x01, 0x00, 0x00, 0x00,
                0x02, 0x03, 0x00, 0x00, 0x00, 0x00},                               // AS_GENERAL
            new int[]{0x06, 0x24, 0x02, 0x01, 0x03, 0x18},                          // FORMAT_TYPE_I 3B/24bit
            new int[]{0x07, 0x05, 0x01, 0x05, 0x40, 0x01, 0x01},                    // OUT async data
            new int[]{0x08, 0x25, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00},              // CS endpoint
            new int[]{0x07, 0x05, 0x81, 0x11, 0x04, 0x00, 0x04}                     // IN feedback (usage 1)
        );
    }

    @Test
    public void parsesUac2ClockSourceAndLeavesRatesEmpty() {
        UacDescriptors d = UacDescriptorParser.parse(uac2());
        assertNotNull(d);
        assertEquals(2, d.uacVersion);
        assertEquals(0, d.controlInterface);
        assertEquals(1, d.altSettings.size());
        AltSetting a = d.altSettings.get(0);
        assertEquals(1, a.interfaceNumber);
        assertEquals(1, a.alternateSetting);
        assertFalse(a.isInput);
        assertEquals(9, a.clockSourceId);
        assertEquals(1, a.terminalLink);
        assertEquals(0, a.sampleRates.length);
        assertEquals(2, a.channels);
        assertEquals(3, a.subframeBytes);
        assertEquals(24, a.bitResolution);
        assertEquals(0x01, a.endpointAddress);
        assertEquals(1, a.syncType);
        assertEquals(0x81, a.feedbackEndpointAddress);
        assertFalse(a.hasSampleRateControl);
        assertEquals(1, d.featureUnits.size());
        assertTrue(d.featureUnits.get(0).hasMasterMute);
        assertTrue(d.featureUnits.get(0).hasMasterVolume);
    }

    @Test
    public void parsesUac1ContinuousRange() {
        byte[] raw = bytes(
            new int[]{0x09, 0x02, 0x00, 0x00, 0x02, 0x01, 0x00, 0x80, 0x32},
            new int[]{0x09, 0x04, 0x00, 0x00, 0x00, 0x01, 0x01, 0x00, 0x00},
            new int[]{0x09, 0x24, 0x01, 0x00, 0x01, 0x09, 0x00, 0x01, 0x01},
            new int[]{0x09, 0x04, 0x01, 0x00, 0x00, 0x01, 0x02, 0x00, 0x00},
            new int[]{0x09, 0x04, 0x01, 0x01, 0x01, 0x01, 0x02, 0x00, 0x00},
            new int[]{0x07, 0x24, 0x01, 0x01, 0x01, 0x01, 0x00},
            // tSamFreqType 0: 8000 .. 48000
            new int[]{0x0E, 0x24, 0x02, 0x01, 0x02, 0x02, 0x10, 0x00,
                0x40, 0x1F, 0x00, 0x80, 0xBB, 0x00},
            new int[]{0x09, 0x05, 0x01, 0x09, 0xC0, 0x00, 0x01, 0x00, 0x00}
        );
        UacDescriptors d = UacDescriptorParser.parse(raw);
        assertNotNull(d);
        AltSetting a = d.altSettings.get(0);
        assertEquals(0, a.sampleRates.length);
        assertEquals(8000, a.minRate);
        assertEquals(48000, a.maxRate);
        assertTrue(a.supportsRate(44100));
        assertFalse(a.supportsRate(96000));
        assertFalse(a.supportsRate(7999));
    }

    @Test
    public void truncatedInputDoesNotThrow() {
        assertNull(UacDescriptorParser.parse(new byte[]{0x12, 0x01, 0x00}));
        assertNull(UacDescriptorParser.parse(new byte[0]));
        assertNull(UacDescriptorParser.parse(null));
        byte[] full = fixture("uac1_headset_descriptors.hex");
        for (int n = 0; n < full.length; n++) {
            UacDescriptorParser.parse(Arrays.copyOf(full, n)); // must not throw
        }
    }

    @Test
    public void nonAudioDeviceReturnsNull() {
        byte[] raw = bytes(
            new int[]{0x09, 0x02, 0x00, 0x00, 0x01, 0x01, 0x00, 0x80, 0x32},
            new int[]{0x09, 0x04, 0x00, 0x00, 0x02, 0xFF, 0x47, 0xD0, 0x00},
            new int[]{0x07, 0x05, 0x81, 0x03, 0x20, 0x00, 0x04},
            new int[]{0x07, 0x05, 0x01, 0x03, 0x20, 0x00, 0x08}
        );
        assertNull(UacDescriptorParser.parse(raw));
    }

    /**
     * G435 topology (decoded from the dump): IT 1 (microphone) -> FU 2 -> OT 3 (USB streaming,
     * capture terminalLink 3); IT 4 (USB streaming, playback terminalLink 4) -> FU 5 -> OT 6 (headset).
     */
    @Test
    public void g435PlaybackVolumeUnitIsFu5() {
        UacDescriptors d = UacDescriptorParser.parse(fixture("g435_descriptors.hex"));
        assertNotNull(d);
        AltSetting out = first(d, false), in = first(d, true);
        assertEquals(4, out.terminalLink);
        assertEquals(3, in.terminalLink);
        assertEquals(1, d.featureUnits.get(0).sourceId);
        assertEquals(4, d.featureUnits.get(1).sourceId);
        UacDescriptors.FeatureUnit fu = d.playbackVolumeUnit(out.terminalLink, in.terminalLink);
        assertNotNull(fu);
        assertEquals(5, fu.unitId);
    }

    @Test
    public void uac1HeadsetPlaybackVolumeUnitIsFu2() {
        UacDescriptors d = UacDescriptorParser.parse(fixture("uac1_headset_descriptors.hex"));
        assertNotNull(d);
        AltSetting out = first(d, false), in = first(d, true);
        UacDescriptors.FeatureUnit fu = d.playbackVolumeUnit(out.terminalLink, in.terminalLink);
        assertNotNull(fu);
        assertEquals(2, fu.unitId);
    }

    /** IT 1 -> selector 7 (inputs 9, 1) -> FU 8 -> OT 3; mic IT 4 -> FU 5 -> OT 6. */
    @Test
    public void playbackVolumeUnitFollowsChainThroughSelector() {
        byte[] raw = bytes(
            new int[]{0x09, 0x02, 0x00, 0x00, 0x02, 0x01, 0x00, 0x80, 0x32},
            new int[]{0x09, 0x04, 0x00, 0x00, 0x00, 0x01, 0x01, 0x00, 0x00},
            new int[]{0x09, 0x24, 0x01, 0x00, 0x01, 0x40, 0x00, 0x01, 0x01},
            new int[]{0x0C, 0x24, 0x02, 0x04, 0x01, 0x02, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00}, // IT 4 mic
            new int[]{0x09, 0x24, 0x06, 0x05, 0x04, 0x01, 0x03, 0x00, 0x00},                // FU 5 <- 4
            new int[]{0x09, 0x24, 0x03, 0x06, 0x01, 0x01, 0x00, 0x05, 0x00},                // OT 6 <- 5
            new int[]{0x0C, 0x24, 0x02, 0x01, 0x01, 0x01, 0x00, 0x02, 0x03, 0x00, 0x00, 0x00}, // IT 1 stream
            new int[]{0x08, 0x24, 0x05, 0x07, 0x02, 0x09, 0x01, 0x00},                      // SU 7 <- 9, 1
            new int[]{0x09, 0x24, 0x06, 0x08, 0x07, 0x01, 0x03, 0x00, 0x00},                // FU 8 <- 7
            new int[]{0x09, 0x24, 0x03, 0x03, 0x01, 0x03, 0x00, 0x08, 0x00},                // OT 3 <- 8
            new int[]{0x09, 0x04, 0x01, 0x00, 0x00, 0x01, 0x02, 0x00, 0x00},
            new int[]{0x09, 0x04, 0x01, 0x01, 0x01, 0x01, 0x02, 0x00, 0x00},
            new int[]{0x07, 0x24, 0x01, 0x01, 0x01, 0x01, 0x00},
            new int[]{0x0B, 0x24, 0x02, 0x01, 0x02, 0x02, 0x10, 0x01, 0x80, 0xBB, 0x00},
            new int[]{0x09, 0x05, 0x01, 0x09, 0xC0, 0x00, 0x01, 0x00, 0x00}
        );
        UacDescriptors d = UacDescriptorParser.parse(raw);
        assertNotNull(d);
        assertEquals(8, d.playbackVolumeUnit(1, 6).unitId);
        assertTrue(d.reaches(3, 1));
        assertFalse(d.reaches(3, 4));
    }

    @Test
    public void playbackVolumeUnitFallsBackToFirstNonCaptureUnitWithVolume() {
        UacDescriptors d = new UacDescriptors();
        UacDescriptors.FeatureUnit mic = fu(d, 2, 1, true);
        fu(d, 5, 99, false); // no volume control
        UacDescriptors.FeatureUnit other = fu(d, 7, 98, true);
        d.unitSources.put(3, new int[]{2}); // OT 3 <- FU 2 (capture path)
        // playback terminal 4 is not connected to anything known
        assertEquals(other, d.playbackVolumeUnit(4, 3));
        // without a capture terminal the first unit with volume wins
        assertEquals(mic, d.playbackVolumeUnit(4, -1));
        // capture-path unit only: none
        UacDescriptors only = new UacDescriptors();
        fu(only, 2, 1, true);
        only.unitSources.put(3, new int[]{2});
        assertNull(only.playbackVolumeUnit(4, 3));
    }

    @Test
    public void reachesSurvivesCycles() {
        UacDescriptors d = new UacDescriptors();
        d.unitSources.put(1, new int[]{2});
        d.unitSources.put(2, new int[]{1});
        assertFalse(d.reaches(1, 3));
        assertTrue(d.reaches(1, 2));
    }

    private static UacDescriptors.FeatureUnit fu(UacDescriptors d, int id, int src, boolean vol) {
        UacDescriptors.FeatureUnit f = new UacDescriptors.FeatureUnit();
        f.unitId = id;
        f.sourceId = src;
        f.hasMasterVolume = vol;
        d.featureUnits.add(f);
        d.unitSources.put(id, new int[]{src});
        return f;
    }

    @Test
    public void toHexFormatsBytes() {
        assertEquals("12 01 ff", UacDescriptorParser.toHex(new byte[]{0x12, 0x01, (byte) 0xFF}));
        assertEquals("", UacDescriptorParser.toHex(null));
    }
}
