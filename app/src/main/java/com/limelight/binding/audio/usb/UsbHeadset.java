package com.limelight.binding.audio.usb;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import com.limelight.LimeLog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * One opened, claimed USB Audio Class headset driven by the native isochronous engine.
 *
 * Thread safety: every call that touches the native handle holds the read lock; close()
 * takes the write lock, zeroes the handle and frees it, so no native call can start
 * after (or race with) close. close() may therefore wait for one in-flight readCapture
 * timeout. Never call close() from a thread that is inside another method of this object.
 * After close every method returns a negative/false/empty result instead of throwing.
 */
final class UsbHeadset {
    private static final int USB_CLASS_AUDIO = 1;
    private static final int ERR_CLOSED = -9;

    private final UsbDevice device;
    private final UsbDeviceConnection connection;
    private final List<UsbInterface> claimed;
    private final UacControl control;
    private final UacStreamConfig playback;
    private final UacStreamConfig capture;
    private final String key;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    /** Guarded by lock; 0 once closed. */
    private long handle;
    private boolean playbackStarted;
    private boolean captureStarted;
    private volatile boolean gone;

    private UsbHeadset(UsbDevice device, UsbDeviceConnection connection, List<UsbInterface> claimed,
                       UacControl control, UacStreamConfig playback, UacStreamConfig capture, long handle) {
        this.device = device;
        this.connection = connection;
        this.claimed = claimed;
        this.control = control;
        this.playback = playback;
        this.capture = capture;
        this.handle = handle;
        this.key = UsbAudioPermissionPolicy.deviceKey(device.getVendorId(), device.getProductId());
    }

    /** Opens and claims the headset. Returns null (with everything released) on any failure. */
    static UsbHeadset open(UsbManager m, UsbDevice d) {
        if (m == null || d == null) return null;
        UsbDeviceConnection conn;
        try {
            conn = m.openDevice(d);
        } catch (RuntimeException e) {
            LimeLog.warning("UsbAudio: openDevice failed: " + e);
            return null;
        }
        if (conn == null) {
            LimeLog.warning("UsbAudio: openDevice returned null");
            return null;
        }
        List<UsbInterface> claimed = new ArrayList<>();
        long h = 0;
        try {
            byte[] raw = conn.getRawDescriptors();
            LimeLog.info("UsbAudio: descriptors " + UacDescriptorParser.toHex(raw));
            UacDescriptors desc = UacDescriptorParser.parse(raw);
            if (desc == null) {
                LimeLog.warning("UsbAudio: no usable AudioStreaming alt setting");
                throw new OpenFailure();
            }

            Set<Integer> ids = new HashSet<>();
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                UsbInterface intf = d.getInterface(i);
                if (intf.getInterfaceClass() != USB_CLASS_AUDIO || !ids.add(intf.getId())) continue;
                if (!conn.claimInterface(intf, true)) {
                    LimeLog.warning("UsbAudio: claimInterface " + intf.getId() + " failed");
                    throw new OpenFailure();
                }
                claimed.add(intf);
            }

            UacControl control = new UacControl(UacControl.transportFor(conn), desc);
            control.fillUac2Rates();

            UacStreamConfig play = UacFormatSelector.selectPlayback(desc.altSettings);
            UacStreamConfig cap = UacFormatSelector.selectCapture(desc.altSettings);
            if (play == null && cap == null) {
                LimeLog.warning("UsbAudio: no supported playback or capture format");
                throw new OpenFailure();
            }

            h = UsbAudioNative.open(conn.getFileDescriptor());
            if (h == 0) {
                LimeLog.warning("UsbAudio: native open failed");
                throw new OpenFailure();
            }

            LimeLog.info("UsbAudio: opened " + d.getProductName() + " UAC" + desc.uacVersion
                    + " playback=" + describe(play) + " capture=" + describe(cap));
            return new UsbHeadset(d, conn, claimed, control, play, cap, h);
        } catch (OpenFailure | RuntimeException | LinkageError e) {
            if (!(e instanceof OpenFailure)) {
                LimeLog.warning("UsbAudio: open failed: " + e);
            }
            if (h != 0) {
                UsbAudioNative.close(h);
            }
            releaseAll(conn, claimed);
            return null;
        }
    }

    private static final class OpenFailure extends Exception {
        OpenFailure() {
            // Exception(String, Throwable, boolean, boolean) is API 24; minSdk is 21.
            super("UsbAudio: open failed");
        }
    }

    private static String describe(UacStreamConfig c) {
        if (c == null) return "none";
        UacDescriptors.AltSetting a = c.alt;
        return "if" + a.interfaceNumber + "/alt" + a.alternateSetting + " " + c.sampleRate + "Hz "
                + a.channels + "ch " + (a.subframeBytes * 8) + "bit(" + a.bitResolution + ") ep=0x"
                + Integer.toHexString(a.endpointAddress) + " mps=" + a.maxPacketBytes
                + " sync=" + a.syncType + " fb=0x" + Integer.toHexString(a.feedbackEndpointAddress);
    }

    private static void releaseAll(UsbDeviceConnection conn, List<UsbInterface> claimed) {
        for (UsbInterface intf : claimed) {
            try {
                conn.releaseInterface(intf);
            } catch (RuntimeException ignored) {
            }
        }
        try {
            conn.close();
        } catch (RuntimeException ignored) {
        }
    }

    UacStreamConfig playbackConfig() {
        return playback;
    }

    UacStreamConfig captureConfig() {
        return capture;
    }

    /** Selects the alt setting, sets the rate, unmutes and starts streaming. Idempotent once started. */
    boolean startPlayback() {
        lock.readLock().lock();
        try {
            if (handle == 0 || playback == null) return false;
            synchronized (this) {
                if (playbackStarted) return true;
                UacDescriptors.AltSetting alt = playback.alt;
                if (!selectAlt(alt.interfaceNumber, alt.alternateSetting)) return false;
                control.setSampleRate(alt, playback.sampleRate); // logs; non-fatal (fixed-rate devices)
                control.unmuteAndSetUnityVolume();
                int r = UsbAudioNative.startPlayback(handle, alt.endpointAddress, alt.maxPacketBytes,
                        alt.bInterval, playback.frameBytes(), playback.sampleRate, alt.feedbackEndpointAddress);
                if (r != 0) {
                    LimeLog.warning("UsbAudio: native startPlayback failed (" + r + ")");
                    return false;
                }
                playbackStarted = true;
                return true;
            }
        } catch (RuntimeException e) {
            LimeLog.warning("UsbAudio: startPlayback failed: " + e);
            return false;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Non-blocking. Returns bytes accepted, or negative if closed/gone/not started. */
    int writePlayback(byte[] d, int len) {
        lock.readLock().lock();
        try {
            if (handle == 0) return ERR_CLOSED;
            return UsbAudioNative.writePlayback(handle, d, len);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Selects the alt setting, sets the rate, unmutes and starts capturing. Idempotent once
     * started (native start is not repeated); a repeat call drains stale captured data.
     */
    boolean startCapture() {
        lock.readLock().lock();
        try {
            if (handle == 0 || capture == null) return false;
            synchronized (this) {
                if (captureStarted) {
                    // Shared headset: a new capture user must not get audio queued for the last one.
                    drainCapture();
                    return true;
                }
                UacDescriptors.AltSetting alt = capture.alt;
                if (!selectAlt(alt.interfaceNumber, alt.alternateSetting)) return false;
                control.setSampleRate(alt, capture.sampleRate); // logs; non-fatal (fixed-rate devices)
                control.unmuteAndSetUnityVolume();
                int r = UsbAudioNative.startCapture(handle, alt.endpointAddress, alt.maxPacketBytes,
                        alt.bInterval, capture.frameBytes(), capture.sampleRate);
                if (r != 0) {
                    LimeLog.warning("UsbAudio: native startCapture failed (" + r + ")");
                    return false;
                }
                captureStarted = true;
                return true;
            }
        } catch (RuntimeException e) {
            LimeLog.warning("UsbAudio: startCapture failed: " + e);
            return false;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Discards captured data already queued. Caller holds the read lock with handle != 0. */
    private void drainCapture() {
        byte[] scratch = new byte[Math.max(capture.frameBytes(), 1) * 480];
        // Bounded so a device that keeps up with timeout-0 reads cannot spin us forever.
        for (int i = 0; i < 1000; i++) {
            if (UsbAudioNative.readCapture(handle, scratch, scratch.length, 0) <= 0) return;
        }
    }

    /** Blocks up to timeoutMs. Returns bytes, 0 on timeout, negative if closed/gone/not started. */
    int readCapture(byte[] b, int max, int timeoutMs) {
        lock.readLock().lock();
        try {
            if (handle == 0) return ERR_CLOSED;
            return UsbAudioNative.readCapture(handle, b, max, timeoutMs);
        } finally {
            lock.readLock().unlock();
        }
    }

    boolean isAlive() {
        if (gone) return false;
        lock.readLock().lock();
        try {
            return handle != 0 && UsbAudioNative.getState(handle) == 0;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Called by UsbAudioManager when the device is detached. */
    void markGone() {
        gone = true;
    }

    int vendorId() {
        return device.getVendorId();
    }

    int productId() {
        return device.getProductId();
    }

    String deviceName() {
        return device.getDeviceName();
    }

    String label() {
        String name = device.getProductName();
        return name != null && !name.trim().isEmpty() ? name : "USB headset";
    }

    String deviceKey() {
        return key;
    }

    String stats() {
        lock.readLock().lock();
        try {
            if (handle == 0) return "closed";
            return UsbAudioNative.getStats(handle);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Stops streaming, resets alt settings, releases interfaces and closes the connection. Idempotent. */
    void close() {
        lock.writeLock().lock();
        try {
            if (handle == 0) return;
            long h = handle;
            handle = 0;
            UsbAudioNative.close(h);
            try {
                if (playback != null) selectAlt(playback.alt.interfaceNumber, 0);
                if (capture != null) selectAlt(capture.alt.interfaceNumber, 0);
            } catch (RuntimeException ignored) {
            }
            releaseAll(connection, claimed);
            LimeLog.info("UsbAudio: closed " + label());
        } finally {
            lock.writeLock().unlock();
        }
    }

    private boolean selectAlt(int interfaceNumber, int alternateSetting) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            if (intf.getId() == interfaceNumber && intf.getAlternateSetting() == alternateSetting) {
                if (connection.setInterface(intf)) return true;
                LimeLog.warning("UsbAudio: setInterface " + interfaceNumber + "/" + alternateSetting + " failed");
                return false;
            }
        }
        LimeLog.warning("UsbAudio: no interface " + interfaceNumber + "/" + alternateSetting);
        return false;
    }
}
