package com.limelight.binding.audio.usb;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;

/** Decides whether a USB device is an audio headset that Android itself does not drive. */
final class UsbAudioDetector {
    private static final int USB_CLASS_AUDIO = 1;
    private static final int SUBCLASS_STREAMING = 2;
    // AudioDeviceInfo.TYPE_USB_DEVICE, TYPE_USB_ACCESSORY, TYPE_USB_HEADSET
    private static final int TYPE_USB_DEVICE = 11;
    private static final int TYPE_USB_ACCESSORY = 12;
    private static final int TYPE_USB_HEADSET = 22;

    private UsbAudioDetector() {}

    /**
     * @param interfaceClassSubclass one {class, subclass} pair per interface
     * @param androidAudioDeviceTypes AudioDeviceInfo types Android currently exposes
     */
    static boolean needsArtemisDriver(int sdkInt, int[][] interfaceClassSubclass, int[] androidAudioDeviceTypes) {
        if (sdkInt < 23) return false;
        boolean streaming = false;
        for (int[] cs : interfaceClassSubclass) {
            if (cs.length >= 2 && cs[0] == USB_CLASS_AUDIO && cs[1] == SUBCLASS_STREAMING) {
                streaming = true;
                break;
            }
        }
        if (!streaming) return false;
        for (int t : androidAudioDeviceTypes) {
            if (t == TYPE_USB_HEADSET || t == TYPE_USB_DEVICE || t == TYPE_USB_ACCESSORY) return false;
        }
        return true;
    }

    static boolean needsArtemisDriver(Context c, UsbDevice d) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || d == null) return false;
        int n = d.getInterfaceCount();
        int[][] ifs = new int[n][];
        for (int i = 0; i < n; i++) {
            UsbInterface intf = d.getInterface(i);
            ifs[i] = new int[]{intf.getInterfaceClass(), intf.getInterfaceSubclass()};
        }
        int[] types = new int[0];
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            AudioDeviceInfo[] devs = am.getDevices(AudioManager.GET_DEVICES_ALL);
            types = new int[devs.length];
            for (int i = 0; i < devs.length; i++) types[i] = devs[i].getType();
        }
        return needsArtemisDriver(Build.VERSION.SDK_INT, ifs, types);
    }
}
