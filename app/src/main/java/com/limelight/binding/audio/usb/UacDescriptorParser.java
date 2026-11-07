package com.limelight.binding.audio.usb;

import com.limelight.LimeLog;

import java.util.HashMap;
import java.util.Map;

/**
 * Parses a raw USB descriptor blob (as read from sysfs or usbdevfs: optional device descriptor
 * followed by configuration descriptor(s)) into a {@link UacDescriptors} model.
 * Never throws on malformed input.
 */
public final class UacDescriptorParser {
    private static final int DT_INTERFACE = 0x04;
    private static final int DT_ENDPOINT = 0x05;
    private static final int DT_CS_INTERFACE = 0x24;
    private static final int DT_CS_ENDPOINT = 0x25;

    private static final int USB_CLASS_AUDIO = 1;
    private static final int SUBCLASS_CONTROL = 1;
    private static final int SUBCLASS_STREAMING = 2;

    // AudioControl subtypes
    private static final int AC_HEADER = 0x01;
    private static final int AC_INPUT_TERMINAL = 0x02;
    private static final int AC_OUTPUT_TERMINAL = 0x03;
    private static final int AC_MIXER_UNIT = 0x04;
    private static final int AC_SELECTOR_UNIT = 0x05;
    private static final int AC_FEATURE_UNIT = 0x06;
    private static final int AC_CLOCK_SOURCE = 0x0A;

    // AudioStreaming subtypes
    private static final int AS_GENERAL = 0x01;
    private static final int AS_FORMAT_TYPE = 0x02;

    private static final int UAC1_FORMAT_PCM = 1;

    private UacDescriptorParser() {}

    /**
     * @return the parsed model, or null if there is no AudioStreaming alt setting with a
     *         Type I PCM format and an endpoint.
     */
    public static UacDescriptors parse(byte[] raw) {
        if (raw == null) return null;
        try {
            return parseInternal(raw);
        } catch (RuntimeException e) {
            LimeLog.warning("UsbAudio: descriptor parse failed: " + e);
            return null;
        }
    }

    private static UacDescriptors parseInternal(byte[] raw) {
        UacDescriptors result = new UacDescriptors();
        // terminal id -> clock source id (UAC2 only)
        Map<Integer, Integer> terminalClock = new HashMap<>();

        int ifClass = -1, ifSubclass = -1, ifNumber = -1;
        UacDescriptors.AltSetting alt = null;
        boolean altFormatOk = false;
        boolean altPcmTag = false; // UAC1 wFormatTag == PCM

        int pos = 0;
        while (pos + 2 <= raw.length) {
            int len = raw[pos] & 0xFF;
            if (len < 2 || pos + len > raw.length) break;
            int type = raw[pos + 1] & 0xFF;

            if (type == DT_INTERFACE) {
                finishAlt(result, alt, altFormatOk);
                alt = null;
                altFormatOk = false;
                altPcmTag = false;
                ifNumber = u8(raw, pos, 2);
                int altNum = u8(raw, pos, 3);
                ifClass = u8(raw, pos, 5);
                ifSubclass = u8(raw, pos, 6);
                if (ifClass == USB_CLASS_AUDIO) {
                    if (ifSubclass == SUBCLASS_CONTROL && result.controlInterface < 0) {
                        result.controlInterface = ifNumber;
                    } else if (ifSubclass == SUBCLASS_STREAMING && altNum != 0) {
                        alt = new UacDescriptors.AltSetting();
                        alt.interfaceNumber = ifNumber;
                        alt.alternateSetting = altNum;
                    }
                }
            } else if (type == DT_CS_INTERFACE && ifClass == USB_CLASS_AUDIO) {
                int subtype = u8(raw, pos, 2);
                if (ifSubclass == SUBCLASS_CONTROL) {
                    parseAudioControl(result, terminalClock, raw, pos, len, subtype);
                } else if (ifSubclass == SUBCLASS_STREAMING && alt != null) {
                    if (subtype == AS_GENERAL) altPcmTag = u16(raw, pos, 5) == UAC1_FORMAT_PCM;
                    altFormatOk |= parseAudioStreaming(result, alt, altPcmTag, raw, pos, len, subtype);
                }
            } else if (type == DT_ENDPOINT && alt != null) {
                parseEndpoint(alt, raw, pos, len);
            } else if (type == DT_CS_ENDPOINT && alt != null && result.uacVersion == 1
                    && u8(raw, pos, 2) == 0x01) {
                alt.hasSampleRateControl = (u8(raw, pos, 3) & 0x01) != 0;
            }
            pos += len;
        }
        finishAlt(result, alt, altFormatOk);

        if (result.altSettings.isEmpty()) return null;
        if (result.uacVersion == 2) {
            for (UacDescriptors.AltSetting a : result.altSettings) {
                Integer clock = terminalClock.get(a.terminalLink);
                if (clock != null) a.clockSourceId = clock;
            }
        }
        return result;
    }

    private static void parseAudioControl(UacDescriptors result, Map<Integer, Integer> terminalClock,
                                          byte[] raw, int pos, int len, int subtype) {
        boolean uac2 = result.uacVersion == 2;
        switch (subtype) {
            case AC_HEADER:
                result.uacVersion = u16(raw, pos, 3) >= 0x0200 ? 2 : 1;
                break;
            case AC_INPUT_TERMINAL:
                if (uac2) terminalClock.put(u8(raw, pos, 3), u8(raw, pos, 7));
                break;
            case AC_OUTPUT_TERMINAL:
                if (uac2) terminalClock.put(u8(raw, pos, 3), u8(raw, pos, 8));
                // bSourceID at 7 in both UAC1 and UAC2
                if (len >= 8) {
                    result.outputTerminals.add(u8(raw, pos, 3));
                    result.unitSources.put(u8(raw, pos, 3), new int[]{u8(raw, pos, 7)});
                }
                break;
            case AC_MIXER_UNIT:
            case AC_SELECTOR_UNIT:
                // bNrInPins at 4, baSourceID from 5
                putSources(result, raw, pos, len, 4);
                break;
            case AC_FEATURE_UNIT: {
                UacDescriptors.FeatureUnit fu = new UacDescriptors.FeatureUnit();
                fu.unitId = u8(raw, pos, 3);
                fu.sourceId = u8(raw, pos, 4);
                if (len >= 5) result.unitSources.put(fu.unitId, new int[]{fu.sourceId});
                if (uac2) {
                    // bmaControls[0] is a 32-bit bitmap, 2 bits per control, at offset 5
                    if (len >= 9) {
                        long controls = u16(raw, pos, 5) | ((long) u16(raw, pos, 7) << 16);
                        fu.hasMasterMute = (controls & 0x3) != 0;
                        fu.hasMasterVolume = (controls & 0xC) != 0;
                    }
                } else {
                    // bControlSize at 5, bmaControls[0] (master) at 6
                    if (len >= 7 && u8(raw, pos, 5) >= 1) {
                        int controls = u8(raw, pos, 6);
                        fu.hasMasterMute = (controls & 0x01) != 0;
                        fu.hasMasterVolume = (controls & 0x02) != 0;
                    }
                }
                result.featureUnits.add(fu);
                break;
            }
            case 0x07: // UAC1 processing unit; UAC2 effect unit (bSourceID at 6)
                if (uac2) {
                    if (len >= 7) result.unitSources.put(u8(raw, pos, 3), new int[]{u8(raw, pos, 6)});
                } else {
                    putSources(result, raw, pos, len, 6);
                }
                break;
            case 0x08: // UAC1 extension unit; UAC2 processing unit
            case 0x09: // UAC2 extension unit
                if (uac2 || subtype == 0x08) putSources(result, raw, pos, len, 6);
                break;
            case 0x0D: // UAC2 sample rate converter (bSourceID at 4)
                if (uac2 && len >= 5) result.unitSources.put(u8(raw, pos, 3), new int[]{u8(raw, pos, 4)});
                break;
            default:
                break;
        }
    }

    /** Records a unit's bNrInPins source IDs (count at nrOff, IDs follow), bounded by the descriptor. */
    private static void putSources(UacDescriptors result, byte[] raw, int pos, int len, int nrOff) {
        if (len <= nrOff) return;
        int n = Math.min(u8(raw, pos, nrOff), len - nrOff - 1);
        if (n <= 0) return;
        int[] src = new int[n];
        for (int i = 0; i < n; i++) src[i] = u8(raw, pos, nrOff + 1 + i);
        result.unitSources.put(u8(raw, pos, 3), src);
    }

    /** @return true if this descriptor established a Type I PCM format for the alt setting. */
    private static boolean parseAudioStreaming(UacDescriptors result, UacDescriptors.AltSetting alt, boolean pcmTag,
                                               byte[] raw, int pos, int len, int subtype) {
        boolean uac2 = result.uacVersion == 2;
        if (subtype == AS_GENERAL) {
            alt.terminalLink = u8(raw, pos, 3);
            if (uac2) {
                alt.channels = u8(raw, pos, 10);
                return false;
            }
            return false;
        }
        if (subtype == AS_FORMAT_TYPE) {
            if (uac2) {
                if (u8(raw, pos, 3) != 1) return false;
                alt.subframeBytes = u8(raw, pos, 4);
                alt.bitResolution = u8(raw, pos, 5);
                return true;
            }
            if (u8(raw, pos, 3) != 1 || !pcmTag) return false;
            alt.channels = u8(raw, pos, 4);
            alt.subframeBytes = u8(raw, pos, 5);
            alt.bitResolution = u8(raw, pos, 6);
            int n = u8(raw, pos, 7);
            if (n == 0) {
                alt.minRate = u24(raw, pos, 8);
                alt.maxRate = u24(raw, pos, 11);
            } else {
                int count = Math.min(n, Math.max(0, (len - 8) / 3));
                int[] rates = new int[count];
                for (int i = 0; i < count; i++) rates[i] = u24(raw, pos, 8 + 3 * i);
                alt.sampleRates = rates;
            }
            return true;
        }
        return false;
    }

    private static void parseEndpoint(UacDescriptors.AltSetting alt, byte[] raw, int pos, int len) {
        int address = u8(raw, pos, 2);
        int attributes = u8(raw, pos, 3);
        if ((attributes & 0x03) != 0x01) return; // not isochronous
        int usage = (attributes >> 4) & 0x03;
        if (usage == 1) { // feedback endpoint
            if (alt.feedbackEndpointAddress == 0) alt.feedbackEndpointAddress = address;
            return;
        }
        if (alt.endpointAddress != 0) return; // first data endpoint wins
        alt.endpointAddress = address;
        alt.isInput = (address & 0x80) != 0;
        alt.maxPacketBytes = u16(raw, pos, 4);
        alt.bInterval = u8(raw, pos, 6);
        alt.syncType = (attributes >> 2) & 0x03;
        if (len >= 9 && alt.feedbackEndpointAddress == 0) {
            alt.feedbackEndpointAddress = u8(raw, pos, 8); // UAC1 bSynchAddress
        }
    }

    private static void finishAlt(UacDescriptors result, UacDescriptors.AltSetting alt, boolean formatOk) {
        if (alt != null && formatOk && alt.endpointAddress != 0) {
            result.altSettings.add(alt);
        }
    }

    /** Fixed-offset readers that return 0 past the end of the buffer. */
    private static int u8(byte[] raw, int pos, int off) {
        int i = pos + off;
        return i < raw.length ? raw[i] & 0xFF : 0;
    }

    private static int u16(byte[] raw, int pos, int off) {
        return u8(raw, pos, off) | (u8(raw, pos, off + 1) << 8);
    }

    private static int u24(byte[] raw, int pos, int off) {
        return u16(raw, pos, off) | (u8(raw, pos, off + 2) << 16);
    }

    public static String toHex(byte[] raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length * 3);
        for (int i = 0; i < raw.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(Character.forDigit((raw[i] >> 4) & 0xF, 16)).append(Character.forDigit(raw[i] & 0xF, 16));
        }
        return sb.toString();
    }
}
