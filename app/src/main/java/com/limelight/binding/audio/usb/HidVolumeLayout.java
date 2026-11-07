package com.limelight.binding.audio.usb;

/**
 * Where Consumer Volume Increment / Decrement live in one HID Input report.
 * Bit offsets count from the first data byte, after the Report ID byte if there is one.
 */
final class HidVolumeLayout {
    /** One volume usage inside the report. */
    static final class Field {
        /** A dedicated field: pressed while its value is non-zero. */
        static final int VARIABLE = 0;
        /** Array slots holding usage indices: pressed while any slot equals {@link #value}. */
        static final int ARRAY = 1;

        final int kind;
        final int bitOffset;
        final int bitSize;
        /** Number of array slots (1 for VARIABLE). */
        final int count;
        /** ARRAY only: the slot value that means this usage. */
        final int value;

        Field(int kind, int bitOffset, int bitSize, int count, int value) {
            this.kind = kind;
            this.bitOffset = bitOffset;
            this.bitSize = bitSize;
            this.count = count;
            this.value = value;
        }

        boolean isPressed(byte[] report, int dataStart, int len) {
            if (kind == VARIABLE) return read(report, dataStart, len, bitOffset, bitSize) != 0;
            for (int i = 0; i < count; i++) {
                if (read(report, dataStart, len, bitOffset + i * bitSize, bitSize) == value) return true;
            }
            return false;
        }

        /** Little-endian unsigned field (up to 32 bits); bits past len read as 0. */
        private static int read(byte[] b, int dataStart, int len, int bitOffset, int bitSize) {
            long v = 0;
            for (int i = 0; i < bitSize; i++) {
                int bit = bitOffset + i;
                int idx = dataStart + (bit >> 3);
                if (idx < len && ((b[idx] >> (bit & 7)) & 1) != 0) v |= 1L << i;
            }
            return (int) v;
        }

        @Override
        public String toString() {
            return (kind == VARIABLE ? "var" : "array") + "@" + bitOffset + "/" + bitSize
                    + (kind == ARRAY ? "x" + count + "=0x" + Integer.toHexString(value) : "");
        }
    }

    /** 0 = the interface uses no Report IDs. */
    final int reportId;
    /** Nullable (at least one of increment/decrement is set). */
    final Field increment;
    final Field decrement;
    /** Data bytes of the report, excluding the Report ID byte. */
    final int reportBytes;

    HidVolumeLayout(int reportId, Field increment, Field decrement, int reportBytes) {
        this.reportId = reportId;
        this.increment = increment;
        this.decrement = decrement;
        this.reportBytes = reportBytes;
    }

    @Override
    public String toString() {
        return "reportId=" + reportId + " inc=" + increment + " dec=" + decrement + " bytes=" + reportBytes;
    }
}
