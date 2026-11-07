package com.limelight.binding.audio.usb;

import android.app.Activity;
import android.os.Bundle;

/**
 * Exists only so the USB permission dialog offers "Always open Artemis when ... is connected",
 * which makes the permission permanent. Disabled in the manifest; UsbAudioManager enables it
 * right before the first prompt for an Android-unsupported headset. Never enabled on Android TV
 * (leanback), whose dialog stores no default anyway; the manager disables it there on start.
 */
public class UsbAudioAttachActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The system has just granted the default-package permission: refresh readiness now
        // instead of waiting for the next broadcast.
        UsbAudioManager.getInstance(this).findCandidate();
        finish();
    }
}
