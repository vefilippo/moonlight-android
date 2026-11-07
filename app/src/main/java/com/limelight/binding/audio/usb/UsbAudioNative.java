package com.limelight.binding.audio.usb;

/**
 * JNI bridge to libartemis-usbaudio, which drives isochronous transfers with
 * raw usbdevfs ioctls on the fd of an open UsbDeviceConnection.
 *
 * Only UsbHeadset may touch this class (loading it loads the native library).
 * The caller claims interfaces and selects alt settings before starting a
 * direction. Handles are opaque; 0 means failure.
 *
 * Threading: writePlayback from one writer thread, readCapture from one
 * reader thread; close may run concurrently and wakes a blocked reader.
 * After close the handle must not be used again.
 */
final class UsbAudioNative {
    static {
        System.loadLibrary("artemis-usbaudio");
    }

    private UsbAudioNative() {}

    /** Returns a handle, or 0 on failure. Does not take ownership of fd. */
    static native long open(int fd);

    /** Returns 0 or -errno. feedbackEndpoint <= 0 means none. */
    static native int startPlayback(long h, int endpoint, int maxPacketBytes, int bInterval,
                                    int frameBytes, int sampleRate, int feedbackEndpoint);

    /** Non-blocking. Returns bytes accepted (whole frames), -19 if the device is gone. */
    static native int writePlayback(long h, byte[] data, int length);

    /** Returns 0 or -errno. */
    static native int startCapture(long h, int endpoint, int maxPacketBytes, int bInterval,
                                   int frameBytes, int sampleRate);

    /** Blocks up to timeoutMs. Returns bytes, 0 on timeout, negative if gone or closed. */
    static native int readCapture(long h, byte[] buffer, int maxLength, int timeoutMs);

    /** 0 OK, -19 device gone. */
    static native int getState(long h);

    static native String getStats(long h);

    /** Stops both directions, reaps all URBs, joins the thread and frees the handle. */
    static native void close(long h);
}
