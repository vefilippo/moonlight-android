package com.limelight.binding.audio.usb;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.limelight.LimeLog;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-wide owner of the userspace USB headset: detection, the one-time permission prompt,
 * a reference-counted shared UsbHeadset, and the per-headset gain store.
 */
public final class UsbAudioManager implements UsbAudioRenderer.PlaybackProvider,
        UsbHeadsetPcmSource.Provider {
    static final String ACTION_USB_AUDIO_PERMISSION = "com.limelight.USB_AUDIO_PERMISSION";
    private static final String GAIN_PREF_PREFIX = "usb_audio_gain_";

    public interface Listener {
        void onHeadsetAvailabilityChanged(boolean available);
    }

    private static UsbAudioManager instance;

    public static synchronized UsbAudioManager getInstance(Context c) {
        if (instance == null) {
            instance = new UsbAudioManager(c);
        }
        return instance;
    }

    private final Context context;
    private final UsbManager usbManager;
    private final SharedPreferences prefs;
    private final UsbAudioPermissionPolicy policy = new UsbAudioPermissionPolicy();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private final Object lock = new Object();
    // Guarded by lock
    private boolean started;
    private int refCount;
    /** A thread is inside UsbHeadset.open (outside the lock); acquirers wait on lock. */
    private boolean opening;
    private String pendingPromptKey;
    private volatile UsbHeadset active;
    private volatile boolean ready;
    /** Bumped on every readiness flip, attach and detach (written under lock). */
    private volatile int generation;

    private final Object gainLock = new Object();
    // Guarded by gainLock (written), volatile for lock-free reads
    private volatile String gainKey;
    private volatile float gain = PcmConverter.gainForLevel(PcmConverter.DEFAULT_GAIN_LEVEL);

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            handleBroadcast(intent);
        }
    };

    UsbAudioManager(Context c) {
        Context app = c.getApplicationContext();
        context = app != null ? app : c;
        usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
    }

    /** Registers the USB broadcast receiver. Idempotent. */
    public void start() {
        synchronized (lock) {
            if (started) return;
            started = true;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(ACTION_USB_AUDIO_PERMISSION);
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        if (isLeanback()) {
            // An earlier build may have enabled it here; on TV it only causes a plug-in popup.
            setAttachActivityState(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
        }
        refresh();
    }

    /**
     * Android TV. Its USB permission dialog stores no default package ("Always" has no effect),
     * and a matching attach activity only makes SystemUI show a confirm dialog on every plug-in.
     * So on TV the attach activity stays disabled and permission is requested when a stream
     * starts, once per plug-in or reboot.
     */
    private boolean isLeanback() {
        try {
            return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void handleBroadcast(Intent intent) {
        String action = intent.getAction();
        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        String key = device != null ? keyOf(device) : null;
        boolean forceNotify = false;

        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
            if (key != null) policy.onAttached(key);
            synchronized (lock) {
                generation++;
            }
        } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
            synchronized (lock) {
                generation++;
            }
            if (device != null) {
                synchronized (lock) {
                    UsbHeadset h = active;
                    if (h != null && device.getDeviceName().equals(h.deviceName())) {
                        LimeLog.info("UsbAudio: active headset detached");
                        h.markGone();
                        forceNotify = true;
                    }
                    if (key != null && key.equals(pendingPromptKey)) pendingPromptKey = null;
                }
            }
        } else if (ACTION_USB_AUDIO_PERMISSION.equals(action)) {
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            synchronized (lock) {
                pendingPromptKey = null;
            }
            if (key != null && !granted) {
                LimeLog.info("UsbAudio: permission denied for " + key);
                policy.onDenied(key);
            } else if (granted) {
                LimeLog.info("UsbAudio: permission granted for " + key);
            }
        }

        boolean changed = refresh();
        if (forceNotify && !changed) {
            synchronized (lock) {
                notifyListeners(ready);
            }
        }
    }

    /** First attached device that needs the Artemis driver, or null. Also refreshes isHeadsetReady. */
    public UsbDevice findCandidate() {
        return updateState(null);
    }

    private UsbDevice findCandidateInternal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || usbManager == null) return null;
        try {
            for (UsbDevice d : usbManager.getDeviceList().values()) {
                if (UsbAudioDetector.needsArtemisDriver(context, d)) return d;
            }
        } catch (RuntimeException e) {
            LimeLog.warning("UsbAudio: device enumeration failed: " + e);
        }
        return null;
    }

    /** @return true if the readiness flag flipped. */
    private boolean refresh() {
        boolean[] changed = new boolean[1];
        updateState(changed);
        return changed[0];
    }

    /**
     * Recomputes readiness. Enumeration and the permission check run under the lock, so a
     * concurrent release()/detach cannot leave ready=true for a device that is already gone.
     *
     * @param changedOut nullable; element 0 is set to whether the readiness flag flipped
     * @return the current candidate, or null
     */
    private UsbDevice updateState(boolean[] changedOut) {
        UsbDevice candidate;
        boolean changed;
        synchronized (lock) {
            candidate = findCandidateInternal();
            boolean r = candidate != null && hasPermission(candidate);
            changed = r != ready;
            if (changed) {
                ready = r;
                generation++;
                notifyListeners(r); // posted under the lock so notifications keep their order
            }
        }
        if (changedOut != null) changedOut[0] = changed;
        UsbHeadset h = active;
        retargetGain(h != null ? h.deviceKey() : candidate != null ? keyOf(candidate) : null);
        return candidate;
    }

    /** Changes whenever headset availability may have changed (ready flip, attach, detach). */
    @Override
    public int availabilityGeneration() {
        return generation;
    }

    private boolean hasPermission(UsbDevice d) {
        try {
            return usbManager.hasPermission(d);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void notifyListeners(final boolean available) {
        mainHandler.post(() -> {
            for (Listener l : listeners) {
                l.onHeadsetAvailabilityChanged(available);
            }
        });
    }

    /**
     * Prompts once for USB permission if a candidate exists, has no permission, has not been
     * denied since it was attached, and no prompt is already pending for it.
     */
    public void requestAccessIfNeeded() {
        start();
        UsbDevice d = updateState(null);
        if (d == null || hasPermission(d)) return;
        String key = keyOf(d);
        synchronized (lock) {
            if (!policy.shouldPrompt(key) || key.equals(pendingPromptKey)) return;
            pendingPromptKey = key;
        }
        if (!isLeanback()) {
            // Phones: makes the dialog offer "Always open Artemis", which keeps the permission
            setAttachActivityState(PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        }
        try {
            int flags = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Mutable so the framework can fill in EXTRA_DEVICE and EXTRA_PERMISSION_GRANTED.
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            // Explicit package: our receiver is not exported (required on Android 14+).
            Intent i = new Intent(ACTION_USB_AUDIO_PERMISSION);
            i.setPackage(context.getPackageName());
            LimeLog.info("UsbAudio: requesting permission for " + key);
            usbManager.requestPermission(d, PendingIntent.getBroadcast(context, 0, i, flags));
        } catch (SecurityException e) {
            // Some device policies (e.g. Samsung Knox) throw instead of denying.
            LimeLog.warning("UsbAudio: requestPermission blocked: " + e);
            synchronized (lock) {
                pendingPromptKey = null;
            }
            policy.onDenied(key);
        }
    }

    private void setAttachActivityState(int state) {
        try {
            PackageManager pm = context.getPackageManager();
            ComponentName c = new ComponentName(context, UsbAudioAttachActivity.class);
            if (pm.getComponentEnabledSetting(c) == state) return;
            pm.setComponentEnabledSetting(c, state, PackageManager.DONT_KILL_APP);
        } catch (RuntimeException e) {
            LimeLog.warning("UsbAudio: cannot change attach activity state to " + state + ": " + e);
        }
    }

    /** A candidate exists and Artemis has permission for it. */
    @Override
    public boolean isHeadsetReady() {
        return ready;
    }

    /**
     * Returns the shared headset (opening it on first use) and takes a reference, or null.
     * Returns null while a detached headset is still referenced; its holders must release it first.
     */
    UsbHeadset acquire() {
        synchronized (lock) {
            // Only one thread opens; others wait (releasing the lock) and then share its result.
            while (opening) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            UsbHeadset h = active;
            if (h != null) {
                if (!h.isAlive()) return null;
                refCount++;
                return h;
            }
            opening = true;
        }
        // Outside the lock: open does USB control transfers and binder calls that can block for
        // seconds, and the main-thread broadcast receiver needs the lock on unplug.
        UsbHeadset opened = null;
        try {
            UsbDevice d = findCandidateInternal();
            if (d != null && hasPermission(d)) {
                opened = UsbHeadset.open(usbManager, d);
            }
        } finally {
            synchronized (lock) {
                opening = false;
                if (opened != null) {
                    active = opened;
                    refCount = 1;
                }
                lock.notifyAll();
            }
        }
        if (opened != null) retargetGain(opened.deviceKey());
        return opened;
    }

    /**
     * Acquires the shared headset for game-audio playback. Null if there is no headset or it has
     * no playback direction (the reference is dropped again in that case).
     */
    @Override
    public UsbAudioRenderer.PlaybackOutput openPlayback() {
        final UsbHeadset h = acquire();
        if (h == null) return null;
        final UacStreamConfig cfg = h.playbackConfig();
        if (cfg == null) {
            release(h);
            return null;
        }
        return new UsbAudioRenderer.PlaybackOutput() {
            private boolean closed;

            @Override
            public UacStreamConfig config() {
                return cfg;
            }

            @Override
            public boolean start() {
                return h.startPlayback();
            }

            @Override
            public int write(byte[] data, int length) {
                return h.writePlayback(data, length);
            }

            @Override
            public boolean isAlive() {
                return h.isAlive();
            }

            @Override
            public String stats() {
                return h.stats();
            }

            @Override
            public synchronized void close() {
                if (closed) return;
                closed = true;
                release(h);
            }
        };
    }

    /**
     * Acquires the shared headset for microphone capture. Null if there is no headset or it has
     * no capture direction (the reference is dropped again in that case).
     */
    @Override
    public UsbHeadsetPcmSource.CaptureInput openCapture() {
        final UsbHeadset h = acquire();
        if (h == null) return null;
        final UacStreamConfig cfg = h.captureConfig();
        if (cfg == null) {
            release(h);
            return null;
        }
        return new UsbHeadsetPcmSource.CaptureInput() {
            private boolean closed;

            @Override
            public UacStreamConfig config() {
                return cfg;
            }

            @Override
            public boolean start() {
                return h.startCapture();
            }

            @Override
            public int read(byte[] buf, int max, int timeoutMs) {
                return h.readCapture(buf, max, timeoutMs);
            }

            @Override
            public synchronized void close() {
                if (closed) return;
                closed = true;
                release(h);
            }
        };
    }

    /** Product name of the active headset, else of the current candidate; null if there is none. */
    @Override
    public String headsetLabel() {
        UsbHeadset h = active;
        if (h != null) return h.label();
        UsbDevice d = findCandidateInternal();
        if (d == null) return null;
        String name = d.getProductName();
        return name != null && !name.trim().isEmpty() ? name : "USB headset";
    }

    /** Drops a reference taken by acquire(); closes the headset when the last one goes. */
    void release(UsbHeadset h) {
        if (h == null) return;
        UsbHeadset toClose = null;
        synchronized (lock) {
            if (h != active) {
                LimeLog.warning("UsbAudio: release of a headset that is not active");
                return;
            }
            if (--refCount <= 0) {
                refCount = 0;
                active = null;
                toClose = h;
            }
        }
        if (toClose != null) {
            // Outside the lock: close may wait for one in-flight readCapture timeout.
            toClose.close();
            refresh();
        }
    }

    UsbHeadset activeHeadset() {
        return active;
    }

    public void addListener(Listener l) {
        if (l != null) listeners.addIfAbsent(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    // ---- Gain store ----

    /** Gain level of the active headset, else the current candidate; default if there is none. */
    public int getGainLevel() {
        String k = gainKey;
        return k == null ? PcmConverter.DEFAULT_GAIN_LEVEL : getGainLevel(k);
    }

    /** Sets the level for the active headset, else the current candidate; no-op if there is none. */
    public void setGainLevel(int level) {
        String k = gainKey;
        if (k != null) setGainLevel(k, level);
    }

    /** Linear gain for the current target headset. Lock-free; read from the audio thread. */
    @Override
    public float currentGain() {
        return gain;
    }

    int getGainLevel(String deviceKey) {
        return clampLevel(prefs.getInt(prefKey(deviceKey), PcmConverter.DEFAULT_GAIN_LEVEL));
    }

    void setGainLevel(String deviceKey, int level) {
        int l = clampLevel(level);
        synchronized (gainLock) {
            prefs.edit().putInt(prefKey(deviceKey), l).apply();
            if (deviceKey.equals(gainKey)) gain = PcmConverter.gainForLevel(l);
        }
    }

    private void retargetGain(String deviceKey) {
        synchronized (gainLock) {
            if (deviceKey == null ? gainKey == null : deviceKey.equals(gainKey)) return;
            gainKey = deviceKey;
            gain = PcmConverter.gainForLevel(deviceKey == null
                    ? PcmConverter.DEFAULT_GAIN_LEVEL : getGainLevel(deviceKey));
        }
    }

    private static int clampLevel(int level) {
        return Math.max(0, Math.min(PcmConverter.MAX_GAIN_LEVEL, level));
    }

    /** "046d:0adf" -> "usb_audio_gain_046d_0adf". */
    private static String prefKey(String deviceKey) {
        return GAIN_PREF_PREFIX + deviceKey.replace(':', '_');
    }

    private static String keyOf(UsbDevice d) {
        return UsbAudioPermissionPolicy.deviceKey(d.getVendorId(), d.getProductId());
    }
}
