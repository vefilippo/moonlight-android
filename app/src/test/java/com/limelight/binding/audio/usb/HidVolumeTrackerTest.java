package com.limelight.binding.audio.usb;

import static com.limelight.binding.audio.usb.HidVolumeReportParserTest.bytes;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HidVolumeTrackerTest {

    private static HidVolumeTracker tracker(byte[] descriptor) {
        return new HidVolumeTracker(HidVolumeReportParser.parse(descriptor));
    }

    @Test public void variableRisingEdgesOnly() {
        HidVolumeTracker t = tracker(HidVolumeReportParserTest.VARIABLE);
        assertEquals(+1, t.onReport(bytes(0x10), 1)); // Vol+ pressed
        assertEquals(0, t.onReport(bytes(0x10), 1));  // still held
        assertEquals(0, t.onReport(bytes(0x00), 1));  // released
        assertEquals(+1, t.onReport(bytes(0x10), 1)); // pressed again
        assertEquals(-1, t.onReport(bytes(0x20), 1)); // Vol+ released, Vol- pressed
        assertEquals(0, t.onReport(bytes(0x01), 1));  // other key (mute)
    }

    @Test public void arraySlots() {
        HidVolumeTracker t = tracker(HidVolumeReportParserTest.ARRAY);
        assertEquals(+1, t.onReport(bytes(0xE9, 0x00, 0x00, 0x00), 4));
        assertEquals(0, t.onReport(bytes(0x00, 0x00, 0xE9, 0x00), 4)); // moved slot, still held
        assertEquals(0, t.onReport(bytes(0, 0, 0, 0), 4));
        assertEquals(-1, t.onReport(bytes(0xCD, 0x00, 0xEA, 0x00), 4));
        assertEquals(0, t.onReport(bytes(0xEA, 0x01, 0x00, 0x00), 4)); // 0x1EA is not 0xEA
        assertEquals(0, t.onReport(bytes(0, 0, 0, 0), 4));
    }

    @Test public void reportIdFiltersOtherReports() {
        HidVolumeTracker t = tracker(HidVolumeReportParserTest.WITH_REPORT_ID);
        assertEquals(0, t.onReport(bytes(0x01, 0xFF, 0xFF), 3));  // keyboard report: ignored
        assertEquals(+1, t.onReport(bytes(0x03, 0x00, 0x02), 3)); // Vol+ (bit 9)
        assertEquals(0, t.onReport(bytes(0x01, 0x00, 0x00), 3));  // other report: state kept
        assertEquals(0, t.onReport(bytes(0x03, 0x00, 0x02), 3));  // still held
        assertEquals(-1, t.onReport(bytes(0x03, 0x00, 0x01), 3)); // Vol- (bit 8)
    }

    @Test public void shortOrEmptyReportsReleaseWithoutCrashing() {
        HidVolumeTracker t = tracker(HidVolumeReportParserTest.VARIABLE);
        assertEquals(+1, t.onReport(bytes(0x10), 1));
        assertEquals(0, t.onReport(new byte[8], 0)); // zero-length read: no change
        assertEquals(0, t.onReport(bytes(0x10), 1));
        HidVolumeTracker ids = tracker(HidVolumeReportParserTest.WITH_REPORT_ID);
        assertEquals(0, ids.onReport(bytes(0x03), 1)); // id only: fields read as released
        assertEquals(+1, ids.onReport(bytes(0x03, 0x00, 0x02), 3));
    }

    @Test public void levelSteppingClamps() {
        assertEquals(9, UsbHeadsetVolumeKeys.stepLevel(8, +1));
        assertEquals(7, UsbHeadsetVolumeKeys.stepLevel(8, -1));
        assertEquals(PcmConverter.MAX_GAIN_LEVEL,
                UsbHeadsetVolumeKeys.stepLevel(PcmConverter.MAX_GAIN_LEVEL, +1));
        assertEquals(0, UsbHeadsetVolumeKeys.stepLevel(0, -1));
        assertEquals(8, UsbHeadsetVolumeKeys.stepLevel(8, 0));
    }
}
