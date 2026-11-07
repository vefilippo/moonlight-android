package com.limelight.binding.audio.usb;

import com.limelight.LimeLog;

/**
 * Drives the headset's playback feature-unit volume from the 0..15 headset volume level.
 * Headsets such as the Logitech G435 mirror this host-controlled volume and only report wheel
 * steps that move it, so it is used instead of software gain whenever the device has one.
 *
 * Curve: level 0 mutes (or goes to the device minimum without a mute control). Levels 1..15 are
 * linear in dB from bottom = max(min, top - 30 dB) up to top = min(max, 0 dB), about 2.1 dB per
 * step, rounded to the nearest multiple of RES above min and never above top. Values are in
 * 1/256 dB as on the wire.
 *
 * Not thread safe on its own; apply() is synchronized so calls from the volume executor and the
 * playback start cannot interleave their transfers.
 */
final class UacHardwareVolume {
    static final int SPAN = 30 * 256;

    private final UacControl control;
    private final int unitId;
    private final boolean hasMute;
    private final UacControl.VolumeRange range;

    private UacHardwareVolume(UacControl control, int unitId, boolean hasMute, UacControl.VolumeRange range) {
        this.control = control;
        this.unitId = unitId;
        this.hasMute = hasMute;
        this.range = range;
    }

    /** Reads the unit's volume range (control transfers). Null if fu is null or the range is unreadable. */
    static UacHardwareVolume create(UacControl control, UacDescriptors.FeatureUnit fu) {
        if (fu == null || !fu.hasMasterVolume) return null;
        UacControl.VolumeRange range = control.readVolumeRange(fu.unitId);
        if (range == null) return null;
        LimeLog.info("UsbAudio: hardware volume on unit " + fu.unitId + " range " + range);
        return new UacHardwareVolume(control, fu.unitId, fu.hasMasterMute, range);
    }

    int unitId() {
        return unitId;
    }

    /** Sets the hardware volume for level (control transfers; never on the main or audio thread per frame). */
    synchronized void apply(int level) {
        if (level <= 0) {
            if (hasMute) {
                control.setMute(unitId, true);
            } else {
                control.setVolume(unitId, range.min);
            }
            return;
        }
        // Volume first, then unmute, so unmuting never plays a step at the old level.
        control.setVolume(unitId, volumeForLevel(level, range.min, range.max, range.res));
        if (hasMute) control.setMute(unitId, false);
    }

    /** Level 1..15 (clamped) to a device volume in 1/256 dB; see the class comment for the curve. */
    static int volumeForLevel(int level, int min, int max, int res) {
        int maxLevel = PcmConverter.MAX_GAIN_LEVEL;
        int l = Math.max(1, Math.min(maxLevel, level));
        if (res <= 0) res = 1;
        int top = Math.max(min, Math.min(max, 0));
        int bottom = Math.max(min, top - SPAN);
        double v = bottom + (top - bottom) * (double) (l - 1) / (maxLevel - 1);
        long steps = Math.round((v - min) / res);
        long maxSteps = ((long) top - min) / res;
        steps = Math.max(0, Math.min(maxSteps, steps));
        return (int) (min + steps * res);
    }
}
