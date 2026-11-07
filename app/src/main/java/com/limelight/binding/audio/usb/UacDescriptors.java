package com.limelight.binding.audio.usb;

import java.util.ArrayList;
import java.util.List;

/** Parsed USB Audio Class (UAC1/UAC2) descriptor model for one device configuration. */
public final class UacDescriptors {
    /** 1 or 2. */
    public int uacVersion = 1;
    public int controlInterface = -1;
    public final List<AltSetting> altSettings = new ArrayList<>();
    public final List<FeatureUnit> featureUnits = new ArrayList<>();

    /** One non-zero-bandwidth AudioStreaming alternate setting with a Type I PCM format. */
    public static final class AltSetting {
        public int interfaceNumber;
        public int alternateSetting;
        /** Endpoint direction is IN (device to host). */
        public boolean isInput;
        public int channels;
        public int subframeBytes;
        public int bitResolution;
        /** Discrete rates. Empty on UAC2 (queried from the clock source) and for continuous ranges. */
        public int[] sampleRates = new int[0];
        /** Continuous range, 0 if none. */
        public int minRate;
        public int maxRate;
        public int endpointAddress;
        public int maxPacketBytes;
        public int bInterval;
        /** 0 none, 1 async, 2 adaptive, 3 sync (bmAttributes bits 2-3). */
        public int syncType;
        /** 0 if none. */
        public int feedbackEndpointAddress;
        /** UAC1 class-specific endpoint bmAttributes bit 0. */
        public boolean hasSampleRateControl;
        public int terminalLink;
        /** -1 on UAC1. */
        public int clockSourceId = -1;

        public boolean supportsRate(int rate) {
            for (int r : sampleRates) {
                if (r == rate) return true;
            }
            return maxRate > 0 && rate >= minRate && rate <= maxRate;
        }
    }

    public static final class FeatureUnit {
        public int unitId;
        public boolean hasMasterMute;
        public boolean hasMasterVolume;
    }
}
