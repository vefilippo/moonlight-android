package com.limelight.binding.audio.usb;

/**
 * Turns raw HID Input reports into volume steps: +1 on a Volume Increment press,
 * -1 on a Volume Decrement press (rising edges only; holding does not repeat).
 * Not thread-safe; owned by one reader thread. Pure Java.
 */
final class HidVolumeTracker {
    private final HidVolumeLayout layout;
    private boolean incDown;
    private boolean decDown;

    HidVolumeTracker(HidVolumeLayout layout) {
        this.layout = layout;
    }

    /**
     * @param report bytes as read from the interrupt endpoint (Report ID first if the layout has one)
     * @param len number of valid bytes
     * @return +1, -1 or 0
     */
    int onReport(byte[] report, int len) {
        if (report == null || len <= 0) return 0;
        len = Math.min(len, report.length);
        int start = 0;
        if (layout.reportId != 0) {
            if ((report[0] & 0xFF) != layout.reportId) return 0;
            start = 1;
        }
        boolean inc = layout.increment != null && layout.increment.isPressed(report, start, len);
        boolean dec = layout.decrement != null && layout.decrement.isPressed(report, start, len);
        int step = (inc && !incDown ? 1 : 0) - (dec && !decDown ? 1 : 0);
        incDown = inc;
        decDown = dec;
        return step;
    }
}
