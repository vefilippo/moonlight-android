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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final byte[] rawDescriptors;
    /** Null: never take over the HID volume wheel (everything but Android TV). */
    private final UsbHidVolumeReader.Sink volumeSink;
    /** Playback feature-unit volume driven by the headset volume level; null means software gain. */
    private final UacHardwareVolume hardwareVolume;
    /** One "UsbAudioVolume" thread for level changes (control transfers); null without hardwareVolume. */
    private final ExecutorService volumeExecutor;
    private final LatestLevelExecutor volumeLevels;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    /** Guarded by lock; 0 once closed. */
    private long handle;
    private boolean playbackStarted;
    private boolean captureStarted;
    /** Written under the read lock + this monitor, or under the write lock. */
    private boolean hidVolumeTried;
    private UsbHidVolumeReader hidVolume;
    private volatile boolean gone;

    private UsbHeadset(UsbDevice device, UsbDeviceConnection connection, List<UsbInterface> claimed,
                       UacControl control, UacStreamConfig playback, UacStreamConfig capture, long handle,
                       byte[] rawDescriptors, UsbHidVolumeReader.Sink volumeSink,
                       UacHardwareVolume hardwareVolume) {
        this.device = device;
        this.connection = connection;
        this.claimed = claimed;
        this.control = control;
        this.playback = playback;
        this.capture = capture;
        this.handle = handle;
        this.rawDescriptors = rawDescriptors;
        this.volumeSink = volumeSink;
        this.hardwareVolume = hardwareVolume;
        if (hardwareVolume != null) {
            volumeExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "UsbAudioVolume");
                t.setDaemon(true);
                return t;
            });
            volumeLevels = new LatestLevelExecutor(volumeExecutor, this::applyVolumeLevel);
        } else {
            volumeExecutor = null;
            volumeLevels = null;
        }
        this.key = UsbAudioPermissionPolicy.deviceKey(device.getVendorId(), device.getProductId());
    }

    /**
     * Opens and claims the headset. Returns null (with everything released) on any failure.
     *
     * @param volumeSink non-null only on Android TV: once streaming starts, the HID interface
     *                   carrying the volume wheel is taken over and its steps go here
     */
    static UsbHeadset open(UsbManager m, UsbDevice d, UsbHidVolumeReader.Sink volumeSink) {
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

            // The microphone's unit is never chosen; unreadable range -> software gain.
            UacHardwareVolume hw = null;
            if (play != null) {
                hw = UacHardwareVolume.create(control, desc.playbackVolumeUnit(
                        play.alt.terminalLink, cap != null ? cap.alt.terminalLink : -1));
            }

            h = UsbAudioNative.open(conn.getFileDescriptor());
            if (h == 0) {
                LimeLog.warning("UsbAudio: native open failed");
                throw new OpenFailure();
            }

            LimeLog.info("UsbAudio: opened " + d.getProductName() + " UAC" + desc.uacVersion
                    + " playback=" + describe(play) + " capture=" + describe(cap)
                    + " volume=" + (hw != null ? "hardware unit " + hw.unitId() : "software"));
            return new UsbHeadset(d, conn, claimed, control, play, cap, h, raw, volumeSink, hw);
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

    /**
     * Selects the alt setting, sets the rate, unmutes, sets the hardware volume (if any) to
     * level and starts streaming. Idempotent once started.
     */
    boolean startPlayback(int level) {
        lock.readLock().lock();
        try {
            if (handle == 0 || playback == null) return false;
            synchronized (this) {
                if (playbackStarted) return true;
                UacDescriptors.AltSetting alt = playback.alt;
                if (!selectAlt(alt.interfaceNumber, alt.alternateSetting)) return false;
                control.setSampleRate(alt, playback.sampleRate); // logs; non-fatal (fixed-rate devices)
                control.unmuteAndSetUnityVolume(hardwareVolumeUnit());
                // On this (stream start) thread, before audio flows, so the first frames are at level.
                if (hardwareVolume != null) hardwareVolume.apply(level);
                int r = UsbAudioNative.startPlayback(handle, alt.endpointAddress, alt.maxPacketBytes,
                        alt.bInterval, playback.frameBytes(), playback.sampleRate, alt.feedbackEndpointAddress);
                if (r != 0) {
                    LimeLog.warning("UsbAudio: native startPlayback failed (" + r + ")");
                    return false;
                }
                playbackStarted = true;
                startHidVolume();
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
                control.unmuteAndSetUnityVolume(hardwareVolumeUnit());
                int r = UsbAudioNative.startCapture(handle, alt.endpointAddress, alt.maxPacketBytes,
                        alt.bInterval, capture.frameBytes(), capture.sampleRate);
                if (r != 0) {
                    LimeLog.warning("UsbAudio: native startCapture failed (" + r + ")");
                    return false;
                }
                captureStarted = true;
                startHidVolume();
                return true;
            }
        } catch (RuntimeException e) {
            LimeLog.warning("UsbAudio: startCapture failed: " + e);
            return false;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Android TV only (volumeSink != null): takes over the HID volume wheel once, after the first
     * stream starts. Caller holds the read lock with handle != 0 and this monitor, so close()
     * (write lock) cannot run concurrently and always sees the reader it has to stop.
     */
    private void startHidVolume() {
        if (volumeSink == null || hidVolumeTried) return;
        hidVolumeTried = true;
        try {
            hidVolume = UsbHidVolumeReader.start(device, connection, rawDescriptors, volumeSink);
        } catch (RuntimeException e) {
            LimeLog.warning("UsbAudio: HID volume takeover failed: " + e);
        }
    }

    /** The playback feature unit drives the headset volume level (software gain stays 1.0). */
    boolean hasHardwareVolume() {
        return hardwareVolume != null;
    }

    private int hardwareVolumeUnit() {
        return hardwareVolume != null ? hardwareVolume.unitId() : -1;
    }

    /**
     * Queues the hardware volume change (no-op without one). Never blocks: the control transfers
     * run on the UsbAudioVolume thread and rapid changes coalesce to the latest level.
     */
    void setVolumeLevel(int level) {
        if (volumeLevels != null) volumeLevels.submit(level);
    }

    /** UsbAudioVolume thread. Skipped once closed; close() waits for an apply in progress. */
    private void applyVolumeLevel(int level) {
        lock.readLock().lock();
        try {
            if (handle == 0) return;
            hardwareVolume.apply(level);
        } catch (RuntimeException e) {
            LimeLog.warning("UsbAudio: hardware volume change failed: " + e);
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

    /**
     * Hands the HID wheel back, stops streaming, resets alt settings, releases interfaces and
     * closes the connection. Idempotent; bounded (the HID reader is joined with a timeout).
     */
    void close() {
        lock.writeLock().lock();
        try {
            if (handle == 0) return;
            long h = handle;
            handle = 0;
            // Queued volume tasks now see handle == 0; none is running (it would hold the read lock).
            if (volumeExecutor != null) volumeExecutor.shutdownNow();
            UsbHidVolumeReader hid = hidVolume;
            hidVolume = null;
            if (hid != null) {
                // The reader takes no lock of ours, so joining it under the write lock cannot deadlock.
                try {
                    hid.stop();
                } catch (RuntimeException e) {
                    LimeLog.warning("UsbAudio: HID volume stop failed: " + e);
                }
            }
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
