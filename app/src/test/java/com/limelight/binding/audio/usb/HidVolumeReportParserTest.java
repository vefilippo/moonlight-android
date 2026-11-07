package com.limelight.binding.audio.usb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class HidVolumeReportParserTest {

    static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    /** Consumer control, 1-bit variable fields: Mute, Play/Pause, Next, Prev, Vol+, Vol-, 2 bits padding. */
    static final byte[] VARIABLE = bytes(
            0x05, 0x0C,       // Usage Page (Consumer)
            0x09, 0x01,       // Usage (Consumer Control)
            0xA1, 0x01,       // Collection (Application)
            0x15, 0x00,       //   Logical Minimum (0)
            0x25, 0x01,       //   Logical Maximum (1)
            0x75, 0x01,       //   Report Size (1)
            0x95, 0x06,       //   Report Count (6)
            0x09, 0xE2,       //   Usage (Mute)
            0x09, 0xCD,       //   Usage (Play/Pause)
            0x09, 0xB5,       //   Usage (Scan Next)
            0x09, 0xB6,       //   Usage (Scan Previous)
            0x09, 0xE9,       //   Usage (Volume Increment)
            0x09, 0xEA,       //   Usage (Volume Decrement)
            0x81, 0x02,       //   Input (Data, Var, Abs)
            0x95, 0x02,       //   Report Count (2)
            0x81, 0x03,       //   Input (Const, Var, Abs)
            0xC0);            // End Collection

    /** Consumer control as a 16-bit array of two slots with a usage range 0..0x3FF. */
    static final byte[] ARRAY = bytes(
            0x05, 0x0C,       // Usage Page (Consumer)
            0x09, 0x01,       // Usage (Consumer Control)
            0xA1, 0x01,       // Collection (Application)
            0x15, 0x00,       //   Logical Minimum (0)
            0x26, 0xFF, 0x03, //   Logical Maximum (0x3FF)
            0x19, 0x00,       //   Usage Minimum (0)
            0x2A, 0xFF, 0x03, //   Usage Maximum (0x3FF)
            0x75, 0x10,       //   Report Size (16)
            0x95, 0x02,       //   Report Count (2)
            0x81, 0x00,       //   Input (Data, Array, Abs)
            0xC0);            // End Collection

    /** A keyboard report (ID 1) followed by a consumer report (ID 3) with a 1-byte constant prefix. */
    static final byte[] WITH_REPORT_ID = bytes(
            0x05, 0x01,       // Usage Page (Generic Desktop)
            0x09, 0x06,       // Usage (Keyboard)
            0xA1, 0x01,       // Collection (Application)
            0x85, 0x01,       //   Report ID (1)
            0x05, 0x07,       //   Usage Page (Keyboard)
            0x19, 0xE0,       //   Usage Minimum (0xE0)
            0x29, 0xE7,       //   Usage Maximum (0xE7)
            0x15, 0x00,       //   Logical Minimum (0)
            0x25, 0x01,       //   Logical Maximum (1)
            0x75, 0x01,       //   Report Size (1)
            0x95, 0x08,       //   Report Count (8)
            0x81, 0x02,       //   Input (Data, Var, Abs)
            0x95, 0x06,       //   Report Count (6)
            0x75, 0x08,       //   Report Size (8)
            0x81, 0x00,       //   Input (Data, Array) — 6 key slots
            0xC0,             // End Collection
            0x05, 0x0C,       // Usage Page (Consumer)
            0x09, 0x01,       // Usage (Consumer Control)
            0xA1, 0x01,       // Collection (Application)
            0x85, 0x03,       //   Report ID (3)
            0x75, 0x08,       //   Report Size (8)
            0x95, 0x01,       //   Report Count (1)
            0x81, 0x03,       //   Input (Const) — 8 bits padding
            0x75, 0x01,       //   Report Size (1)  (logical 0..1 persists from the keyboard report)
            0x95, 0x02,       //   Report Count (2)
            0x09, 0xEA,       //   Usage (Volume Decrement)
            0x09, 0xE9,       //   Usage (Volume Increment)
            0x81, 0x02,       //   Input (Data, Var, Abs)
            0x95, 0x06,       //   Report Count (6)
            0x81, 0x03,       //   Input (Const)
            0xC0);            // End Collection

    /** Vendor page (like the G435's HID++ interface): must not be taken. */
    static final byte[] VENDOR = bytes(
            0x06, 0x00, 0xFF, // Usage Page (Vendor 0xFF00)
            0x09, 0x01,       // Usage (1)
            0xA1, 0x01,       // Collection (Application)
            0x85, 0x10,       //   Report ID (0x10)
            0x75, 0x08,       //   Report Size (8)
            0x95, 0x06,       //   Report Count (6)
            0x15, 0x00,       //   Logical Minimum (0)
            0x26, 0xFF, 0x00, //   Logical Maximum (255)
            0x09, 0xE9,       //   Usage (0xE9 on the vendor page — not volume)
            0x81, 0x00,       //   Input (Data, Array)
            0x09, 0x01,       //   Usage (1)
            0x91, 0x00,       //   Output (Data, Array)
            0xC0);            // End Collection

    @Test public void oneBitVariableFields() {
        HidVolumeLayout l = HidVolumeReportParser.parse(VARIABLE);
        assertNotNull(l);
        assertEquals(0, l.reportId);
        assertEquals(1, l.reportBytes);
        assertEquals(HidVolumeLayout.Field.VARIABLE, l.increment.kind);
        assertEquals(4, l.increment.bitOffset);
        assertEquals(1, l.increment.bitSize);
        assertEquals(5, l.decrement.bitOffset);
    }

    @Test public void arrayFieldWithUsageRange() {
        HidVolumeLayout l = HidVolumeReportParser.parse(ARRAY);
        assertNotNull(l);
        assertEquals(0, l.reportId);
        assertEquals(4, l.reportBytes);
        assertEquals(HidVolumeLayout.Field.ARRAY, l.increment.kind);
        assertEquals(0, l.increment.bitOffset);
        assertEquals(16, l.increment.bitSize);
        assertEquals(2, l.increment.count);
        assertEquals(0xE9, l.increment.value);
        assertEquals(0xEA, l.decrement.value);
    }

    @Test public void reportIdAndPerReportOffsets() {
        HidVolumeLayout l = HidVolumeReportParser.parse(WITH_REPORT_ID);
        assertNotNull(l);
        assertEquals(3, l.reportId);
        assertEquals(2, l.reportBytes);
        assertEquals(9, l.increment.bitOffset);
        assertEquals(8, l.decrement.bitOffset);
    }

    @Test public void vendorDescriptorIsNull() {
        assertNull(HidVolumeReportParser.parse(VENDOR));
    }

    @Test public void volumeOnlyInOutputIsNull() {
        byte[] d = bytes(0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01,
                0x95, 0x02, 0x09, 0xE9, 0x09, 0xEA, 0x91, 0x02, 0xC0);
        assertNull(HidVolumeReportParser.parse(d));
    }

    @Test public void onlyDecrementIsEnough() {
        byte[] d = bytes(0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01,
                0x95, 0x01, 0x09, 0xEA, 0x81, 0x02, 0x95, 0x07, 0x81, 0x03, 0xC0);
        HidVolumeLayout l = HidVolumeReportParser.parse(d);
        assertNotNull(l);
        assertNull(l.increment);
        assertEquals(0, l.decrement.bitOffset);
    }

    @Test public void extendedUsageCarriesItsOwnPage() {
        // Usage Page Generic Desktop, but a 4-byte usage 0x000C00E9 names Consumer Volume Increment.
        byte[] d = bytes(0x05, 0x01, 0xA1, 0x01, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x01,
                0x0B, 0xE9, 0x00, 0x0C, 0x00, 0x81, 0x02, 0x95, 0x07, 0x81, 0x03, 0xC0);
        HidVolumeLayout l = HidVolumeReportParser.parse(d);
        assertNotNull(l);
        assertEquals(0, l.increment.bitOffset);
    }

    @Test public void garbageAndTruncationAreSafe() {
        assertNull(HidVolumeReportParser.parse(null));
        assertNull(HidVolumeReportParser.parse(new byte[0]));
        assertNull(HidVolumeReportParser.parse(bytes(0x05, 0x0C, 0x09)));
        byte[] cut = new byte[VARIABLE.length - 9]; // drops the Input item
        System.arraycopy(VARIABLE, 0, cut, 0, cut.length);
        assertNull(HidVolumeReportParser.parse(cut));
    }
}
