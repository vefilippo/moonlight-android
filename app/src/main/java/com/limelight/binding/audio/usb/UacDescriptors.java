package com.limelight.binding.audio.usb;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Parsed USB Audio Class (UAC1/UAC2) descriptor model for one device configuration. */
public final class UacDescriptors {
    /** 1 or 2. */
    public int uacVersion = 1;
    public int controlInterface = -1;
    public final List<AltSetting> altSettings = new ArrayList<>();
    public final List<FeatureUnit> featureUnits = new ArrayList<>();
    /** Unit or output terminal ID -> its source IDs (bSourceID / baSourceID). Input terminals have none. */
    public final Map<Integer, int[]> unitSources = new HashMap<>();
    public final List<Integer> outputTerminals = new ArrayList<>();

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
        public int sourceId;
        public boolean hasMasterMute;
        public boolean hasMasterVolume;
    }

    /**
     * Feature unit that controls the headset's playback volume, or null.
     * Order of preference, never choosing a unit on the capture path (upstream of the capture
     * alt's output terminal): a unit with master volume that is downstream of the playback
     * input terminal and upstream of an output terminal; one downstream of the playback input
     * terminal; the first unit with master volume.
     *
     * @param playbackTerminal terminalLink of the playback alt (USB streaming input terminal), -1 if none
     * @param captureTerminal  terminalLink of the capture alt (USB streaming output terminal), -1 if none
     */
    public FeatureUnit playbackVolumeUnit(int playbackTerminal, int captureTerminal) {
        FeatureUnit onPath = null, fromInput = null, first = null;
        for (FeatureUnit fu : featureUnits) {
            if (!fu.hasMasterVolume) continue;
            if (captureTerminal >= 0 && reaches(captureTerminal, fu.unitId)) continue;
            if (first == null) first = fu;
            if (playbackTerminal >= 0 && reaches(fu.unitId, playbackTerminal)) {
                if (fromInput == null) fromInput = fu;
                if (onPath == null && feedsOutputTerminal(fu.unitId)) onPath = fu;
            }
        }
        return onPath != null ? onPath : fromInput != null ? fromInput : first;
    }

    private boolean feedsOutputTerminal(int id) {
        for (int ot : outputTerminals) {
            if (ot != id && reaches(ot, id)) return true;
        }
        return false;
    }

    /** True if target is the entity from or upstream of it (following source IDs). Cycle-safe. */
    public boolean reaches(int from, int target) {
        Set<Integer> seen = new HashSet<>();
        ArrayDeque<Integer> todo = new ArrayDeque<>();
        todo.add(from);
        while (!todo.isEmpty()) {
            int id = todo.poll();
            if (id == target) return true;
            if (!seen.add(id)) continue;
            int[] src = unitSources.get(id);
            if (src != null) {
                for (int s : src) todo.add(s);
            }
        }
        return false;
    }
}
