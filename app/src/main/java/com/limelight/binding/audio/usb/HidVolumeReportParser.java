package com.limelight.binding.audio.usb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds Consumer Volume Increment (0x0C:0xE9) / Decrement (0x0C:0xEA) in a HID report
 * descriptor's Input reports. Handles short items (Usage Page, Usage, Usage Minimum/Maximum,
 * Logical Minimum, Report Size/Count/ID, Input/Output/Feature, Collection) with global items
 * persisting across main items; Push/Pop and Designator/String items are ignored, long items
 * are skipped. Pure Java.
 */
final class HidVolumeReportParser {
    static final int VOLUME_INCREMENT = 0x000C00E9;
    static final int VOLUME_DECREMENT = 0x000C00EA;

    private static final int TYPE_MAIN = 0;
    private static final int TYPE_GLOBAL = 1;
    private static final int TYPE_LOCAL = 2;
    private static final int MAIN_INPUT = 0x8;
    private static final int GLOBAL_USAGE_PAGE = 0x0;
    private static final int GLOBAL_LOGICAL_MIN = 0x1;
    private static final int GLOBAL_REPORT_SIZE = 0x7;
    private static final int GLOBAL_REPORT_ID = 0x8;
    private static final int GLOBAL_REPORT_COUNT = 0x9;
    private static final int LOCAL_USAGE = 0x0;
    private static final int LOCAL_USAGE_MIN = 0x1;
    private static final int LOCAL_USAGE_MAX = 0x2;
    /** Marks a usage that was given as a 32-bit item and already carries its page. */
    private static final long EXTENDED = 1L << 32;
    private static final int MAX_RANGE = 0x10000;
    /** Cap on one report's total bits (4096 bytes); also keeps every bit offset a small positive int. */
    private static final long MAX_REPORT_BITS = 8 * 4096;

    private HidVolumeReportParser() {}

    /** @return the layout of the first Input report carrying a volume usage, or null if none. */
    static HidVolumeLayout parse(byte[] d) {
        if (d == null) return null;
        int usagePage = 0, logicalMin = 0, reportSize = 0, reportCount = 0, reportId = 0;
        List<Long> usages = new ArrayList<>();
        long usageMin = -1;
        Map<Integer, Integer> inputBits = new HashMap<>();
        HidVolumeLayout.Field inc = null, dec = null;
        int foundId = -1;

        int i = 0;
        while (i < d.length) {
            int prefix = d[i] & 0xFF;
            if (prefix == 0xFE) { // long item: bDataSize, bLongItemTag, data
                if (i + 1 >= d.length) return null;
                i += 3 + (d[i + 1] & 0xFF);
                continue;
            }
            int size = prefix & 3;
            if (size == 3) size = 4;
            if (i + 1 + size > d.length) return null;
            long u = 0;
            for (int k = 0; k < size; k++) u |= (long) (d[i + 1 + k] & 0xFF) << (8 * k);
            int s = size == 0 ? 0 : (int) (u << (64 - 8 * size) >> (64 - 8 * size));
            int type = (prefix >> 2) & 3;
            int tag = prefix >> 4;
            i += 1 + size;

            if (type == TYPE_MAIN) {
                if (tag == MAIN_INPUT) {
                    int offset = inputBits.containsKey(reportId) ? inputBits.get(reportId) : 0;
                    if (offset + (long) reportSize * reportCount > MAX_REPORT_BITS) return null;
                    boolean data = (u & 1) == 0;
                    boolean variable = (u & 2) != 0;
                    boolean idOk = foundId == -1 || foundId == reportId;
                    if (data && idOk && reportSize > 0 && reportSize <= 32 && !usages.isEmpty()) {
                        int[] resolved = resolve(usages, usagePage);
                        if (variable) {
                            for (int k = 0; k < reportCount; k++) {
                                int usage = resolved[Math.min(k, resolved.length - 1)];
                                int bit = offset + k * reportSize;
                                if (usage == VOLUME_INCREMENT && inc == null) {
                                    inc = new HidVolumeLayout.Field(HidVolumeLayout.Field.VARIABLE, bit, reportSize, 1, 0);
                                } else if (usage == VOLUME_DECREMENT && dec == null) {
                                    dec = new HidVolumeLayout.Field(HidVolumeLayout.Field.VARIABLE, bit, reportSize, 1, 0);
                                }
                            }
                        } else if (reportCount > 0) {
                            for (int k = 0; k < resolved.length; k++) {
                                if (resolved[k] == VOLUME_INCREMENT && inc == null) {
                                    inc = new HidVolumeLayout.Field(HidVolumeLayout.Field.ARRAY, offset, reportSize,
                                            reportCount, logicalMin + k);
                                } else if (resolved[k] == VOLUME_DECREMENT && dec == null) {
                                    dec = new HidVolumeLayout.Field(HidVolumeLayout.Field.ARRAY, offset, reportSize,
                                            reportCount, logicalMin + k);
                                }
                            }
                        }
                        if (foundId == -1 && (inc != null || dec != null)) foundId = reportId;
                    }
                    inputBits.put(reportId, offset + reportSize * reportCount);
                }
                // Local items apply to one main item only.
                usages.clear();
                usageMin = -1;
            } else if (type == TYPE_GLOBAL) {
                switch (tag) {
                    case GLOBAL_USAGE_PAGE: usagePage = (int) (u & 0xFFFF); break;
                    case GLOBAL_LOGICAL_MIN: logicalMin = s; break;
                    case GLOBAL_REPORT_SIZE: reportSize = (int) Math.min(u, Integer.MAX_VALUE); break;
                    case GLOBAL_REPORT_ID: reportId = (int) (u & 0xFF); break;
                    case GLOBAL_REPORT_COUNT: reportCount = (int) Math.min(u, Integer.MAX_VALUE); break;
                    default: break;
                }
            } else if (type == TYPE_LOCAL) {
                long usage = size == 4 ? (u | EXTENDED) : (u & 0xFFFF);
                if (tag == LOCAL_USAGE) {
                    usages.add(usage);
                } else if (tag == LOCAL_USAGE_MIN) {
                    usageMin = usage;
                } else if (tag == LOCAL_USAGE_MAX && usageMin >= 0) {
                    long flag = usageMin & EXTENDED;
                    long lo = usageMin & 0xFFFFFFFFL;
                    long hi = usage & 0xFFFFFFFFL;
                    if (hi >= lo && hi - lo < MAX_RANGE) {
                        for (long v = lo; v <= hi; v++) usages.add(v | flag);
                    }
                    usageMin = -1;
                }
            }
        }

        if (inc == null && dec == null) return null;
        int bits = inputBits.get(foundId);
        return new HidVolumeLayout(foundId, inc, dec, (bits + 7) / 8);
    }

    /** Full 32-bit usages: a short usage takes the Usage Page current at the main item. */
    private static int[] resolve(List<Long> usages, int usagePage) {
        int[] r = new int[usages.size()];
        for (int k = 0; k < r.length; k++) {
            long u = usages.get(k);
            r[k] = (u & EXTENDED) != 0 ? (int) u : (usagePage << 16) | (int) (u & 0xFFFF);
        }
        return r;
    }
}
