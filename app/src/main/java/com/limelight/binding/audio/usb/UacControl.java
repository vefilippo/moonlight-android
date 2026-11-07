package com.limelight.binding.audio.usb;

import android.hardware.usb.UsbDeviceConnection;

import com.limelight.LimeLog;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** UAC1/UAC2 class-specific control requests: sample rate, UAC2 rate ranges, feature-unit mute/volume. */
final class UacControl {
    private static final int TIMEOUT_MS = 1000;
    private static final int MAX_SUBRANGES = 64;

    private static final int OUT_CLASS_IF = 0x21;
    private static final int OUT_CLASS_EP = 0x22;
    private static final int IN_CLASS_IF = 0xA1;
    private static final int SET_CUR = 0x01;
    private static final int RANGE = 0x02;
    private static final int GET_MAX = 0x84;

    interface Transport {
        int control(int requestType, int request, int value, int index,
                    byte[] buffer, int length, int timeoutMs);
    }

    static Transport transportFor(final UsbDeviceConnection c) {
        return (requestType, request, value, index, buffer, length, timeoutMs) ->
                c.controlTransfer(requestType, request, value, index, buffer, length, timeoutMs);
    }

    private final Transport transport;
    private final UacDescriptors desc;

    UacControl(Transport transport, UacDescriptors desc) {
        this.transport = transport;
        this.desc = desc;
    }

    boolean setSampleRate(UacDescriptors.AltSetting alt, int rate) {
        int type, index, len;
        if (desc.uacVersion == 2) {
            type = OUT_CLASS_IF;
            index = (alt.clockSourceId << 8) | desc.controlInterface;
            len = 4;
        } else {
            if (!alt.hasSampleRateControl) return true;
            type = OUT_CLASS_EP;
            index = alt.endpointAddress;
            len = 3;
        }
        byte[] buf = new byte[len];
        putLe(buf, 0, len, rate);
        int r = transport.control(type, SET_CUR, 0x0100, index, buf, len, TIMEOUT_MS);
        if (r != len) {
            LimeLog.warning("UsbAudio: set sample rate " + rate + " failed (" + r + ")");
            return false;
        }
        return true;
    }

    void fillUac2Rates() {
        if (desc.uacVersion != 2) return;
        Map<Integer, int[][]> perClock = new HashMap<>(); // clock -> {discrete, {min, max}} or null
        Map<Integer, Boolean> done = new HashMap<>();
        for (UacDescriptors.AltSetting alt : desc.altSettings) {
            int clock = alt.clockSourceId;
            if (!done.containsKey(clock)) {
                perClock.put(clock, queryClockRates(clock));
                done.put(clock, true);
            }
            int[][] res = perClock.get(clock);
            if (res == null) {
                alt.sampleRates = new int[]{48000};
            } else {
                alt.sampleRates = res[0].clone();
                alt.minRate = res[1][0];
                alt.maxRate = res[1][1];
            }
        }
    }

    /** Returns {discreteRates, {min, max}} or null on failure. */
    private int[][] queryClockRates(int clock) {
        int index = (clock << 8) | desc.controlInterface;
        byte[] head = new byte[2];
        int r = transport.control(IN_CLASS_IF, RANGE, 0x0100, index, head, 2, TIMEOUT_MS);
        if (r != 2) {
            LimeLog.warning("UsbAudio: clock " + clock + " range query failed (" + r + ")");
            return null;
        }
        int n = getLe(head, 0, 2);
        if (n <= 0 || n > MAX_SUBRANGES) {
            LimeLog.warning("UsbAudio: clock " + clock + " bad subrange count " + n);
            return null;
        }
        int total = 2 + 12 * n;
        byte[] buf = new byte[total];
        r = transport.control(IN_CLASS_IF, RANGE, 0x0100, index, buf, total, TIMEOUT_MS);
        if (r != total) {
            LimeLog.warning("UsbAudio: clock " + clock + " range read failed (" + r + ")");
            return null;
        }
        int[] discrete = new int[n];
        int nd = 0;
        int min = 0, max = 0;
        for (int i = 0; i < n; i++) {
            int off = 2 + 12 * i;
            int lo = getLe(buf, off, 4);
            int hi = getLe(buf, off + 4, 4);
            if (lo == hi) {
                discrete[nd++] = lo;
            } else {
                min = lo;
                max = hi;
            }
        }
        return new int[][]{Arrays.copyOf(discrete, nd), {min, max}};
    }

    void unmuteAndSetUnityVolume() {
        for (UacDescriptors.FeatureUnit fu : desc.featureUnits) {
            int index = (fu.unitId << 8) | desc.controlInterface;
            if (fu.hasMasterMute) {
                int r = transport.control(OUT_CLASS_IF, SET_CUR, 0x0100, index,
                        new byte[]{0}, 1, TIMEOUT_MS);
                if (r != 1) {
                    LimeLog.warning("UsbAudio: unmute unit " + fu.unitId + " failed (" + r + ")");
                }
            }
            if (fu.hasMasterVolume) {
                Integer max = desc.uacVersion == 2 ? readVolumeMaxUac2(fu.unitId, index)
                        : readVolumeMaxUac1(fu.unitId, index);
                if (max == null) continue;
                int vol = Math.min(max, 0);
                byte[] buf = new byte[2];
                putLe(buf, 0, 2, vol);
                int r = transport.control(OUT_CLASS_IF, SET_CUR, 0x0200, index, buf, 2, TIMEOUT_MS);
                if (r != 2) {
                    LimeLog.warning("UsbAudio: set volume unit " + fu.unitId + " failed (" + r + ")");
                }
            }
        }
    }

    private Integer readVolumeMaxUac1(int unitId, int index) {
        byte[] buf = new byte[2];
        int r = transport.control(IN_CLASS_IF, GET_MAX, 0x0200, index, buf, 2, TIMEOUT_MS);
        if (r != 2) {
            LimeLog.warning("UsbAudio: get volume max unit " + unitId + " failed (" + r + ")");
            return null;
        }
        return (int) (short) getLe(buf, 0, 2);
    }

    private Integer readVolumeMaxUac2(int unitId, int index) {
        byte[] head = new byte[2];
        int r = transport.control(IN_CLASS_IF, RANGE, 0x0200, index, head, 2, TIMEOUT_MS);
        if (r != 2) {
            LimeLog.warning("UsbAudio: volume range unit " + unitId + " failed (" + r + ")");
            return null;
        }
        int n = getLe(head, 0, 2);
        if (n <= 0 || n > MAX_SUBRANGES) {
            LimeLog.warning("UsbAudio: volume range unit " + unitId + " bad count " + n);
            return null;
        }
        int total = 2 + 6 * n;
        byte[] buf = new byte[total];
        r = transport.control(IN_CLASS_IF, RANGE, 0x0200, index, buf, total, TIMEOUT_MS);
        if (r != total) {
            LimeLog.warning("UsbAudio: volume range read unit " + unitId + " failed (" + r + ")");
            return null;
        }
        int max = Short.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            max = Math.max(max, (short) getLe(buf, 2 + 6 * i + 2, 2));
        }
        return max;
    }

    private static void putLe(byte[] b, int off, int len, int v) {
        for (int i = 0; i < len; i++) b[off + i] = (byte) (v >> (8 * i));
    }

    private static int getLe(byte[] b, int off, int len) {
        int v = 0;
        for (int i = 0; i < len; i++) v |= (b[off + i] & 0xFF) << (8 * i);
        return v;
    }
}
