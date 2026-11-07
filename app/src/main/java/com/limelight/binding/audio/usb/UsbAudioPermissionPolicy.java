package com.limelight.binding.audio.usb;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Remembers permission denials in memory so a denied headset is not prompted for again
 * until it is re-plugged or the process restarts. Thread-safe.
 */
final class UsbAudioPermissionPolicy {
    private final Set<String> denied = new HashSet<>();

    static String deviceKey(int vendorId, int productId) {
        return String.format(Locale.US, "%04x:%04x", vendorId, productId);
    }

    synchronized boolean shouldPrompt(String deviceKey) {
        return !denied.contains(deviceKey);
    }

    synchronized void onDenied(String deviceKey) {
        denied.add(deviceKey);
    }

    synchronized void onAttached(String deviceKey) {
        denied.remove(deviceKey);
    }
}
