package com.limelight.binding.audio.usb;

/** The alternate setting and sample rate chosen for one streaming direction. */
final class UacStreamConfig {
    final UacDescriptors.AltSetting alt;
    final int sampleRate;

    UacStreamConfig(UacDescriptors.AltSetting alt, int sampleRate) {
        this.alt = alt;
        this.sampleRate = sampleRate;
    }

    int frameBytes() {
        return alt.channels * alt.subframeBytes;
    }
}
