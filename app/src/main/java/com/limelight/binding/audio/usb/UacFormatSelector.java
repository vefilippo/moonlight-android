package com.limelight.binding.audio.usb;

import java.util.List;

/** Picks the alternate setting and sample rate to use for playback or capture. */
final class UacFormatSelector {
    private static final int[] PLAYBACK_RATES = {48000, 96000, 44100};
    private static final int[] CAPTURE_RATES = {48000, 16000, 32000, 44100, 24000};
    private static final int[] PLAYBACK_CHANNELS = {2, 1};
    private static final int[] CAPTURE_CHANNELS = {1, 2};
    private static final int[] SUBFRAMES = {2, 3, 4};

    private UacFormatSelector() {}

    static UacStreamConfig selectPlayback(List<UacDescriptors.AltSetting> alts) {
        return select(alts, false, PLAYBACK_RATES, PLAYBACK_CHANNELS);
    }

    static UacStreamConfig selectCapture(List<UacDescriptors.AltSetting> alts) {
        return select(alts, true, CAPTURE_RATES, CAPTURE_CHANNELS);
    }

    private static UacStreamConfig select(List<UacDescriptors.AltSetting> alts, boolean input,
                                          int[] ratePrefs, int[] channelPrefs) {
        UacStreamConfig best = null;
        int bestRateRank = 0, bestChRank = 0, bestSubRank = 0;
        for (UacDescriptors.AltSetting a : alts) {
            if (a.isInput != input || a.channels <= 0) continue;
            int subRank = indexOf(SUBFRAMES, a.subframeBytes);
            if (subRank == SUBFRAMES.length) continue;
            int rate = bestRate(a, ratePrefs);
            if (rate <= 0) continue;
            int rateRank = indexOf(ratePrefs, rate);
            int chRank = indexOf(channelPrefs, a.channels);
            boolean better;
            if (best == null) {
                better = true;
            } else if (rateRank != bestRateRank) {
                better = rateRank < bestRateRank;
            } else if (rateRank == ratePrefs.length && rate != best.sampleRate) {
                better = rate > best.sampleRate;
            } else if (chRank != bestChRank) {
                better = chRank < bestChRank;
            } else if (chRank == channelPrefs.length && a.channels != best.alt.channels) {
                better = a.channels < best.alt.channels;
            } else {
                better = subRank < bestSubRank;
            }
            if (better) {
                best = new UacStreamConfig(a, rate);
                bestRateRank = rateRank;
                bestChRank = chRank;
                bestSubRank = subRank;
            }
        }
        return best;
    }

    /** Best preferred rate the alt supports, else its highest rate, else 0. */
    private static int bestRate(UacDescriptors.AltSetting a, int[] prefs) {
        for (int r : prefs) {
            if (a.supportsRate(r)) return r;
        }
        int max = a.maxRate;
        for (int r : a.sampleRates) {
            if (r > max) max = r;
        }
        return max;
    }

    private static int indexOf(int[] arr, int v) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == v) return i;
        }
        return arr.length;
    }
}
