package com.limelight.binding.audio.usb;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.os.SystemClock;

import com.limelight.LimeLog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Android TV only: takes over the headset's HID interface that reports Consumer Volume
 * Increment/Decrement (Android TV consumes volume keys before any app sees them) and reads
 * the wheel on a daemon thread. stop() hands the interface back: releaseInterface makes
 * Android reconnect the kernel HID driver.
 *
 * Only an interface whose report descriptor has a volume usage in an Input report stays claimed;
 * other HID interfaces (never boot keyboards/mice) are at most claimed briefly to read their
 * report descriptor when it cannot be read otherwise, and released at once.
 * The reader only uses its own interrupt endpoint, so it can share the connection with the
 * audio engine. Lifecycle is owned by UsbHeadset (start after streaming starts, stop in close).
 */
final class UsbHidVolumeReader {
    /** Called on the reader thread with +1 or -1 per wheel step. */
    interface Sink {
        void onVolumeStep(int delta);
    }

    private static final int USB_CLASS_HID = 3;
    private static final int READ_TIMEOUT_MS = 200;
    private static final int CONTROL_TIMEOUT_MS = 500;
    private static final int MAX_REPORT_DESCRIPTOR = 4096;
    private static final long JOIN_TIMEOUT_MS = 1000;
    /** A failed read that returns well before its timeout is an error, not a timeout. */
    private static final long FAST_FAILURE_MS = 50;
    private static final int MAX_FAST_FAILURES = 5;

    private final UsbDeviceConnection connection;
    private final UsbInterface intf;
    private final UsbEndpoint endpoint;
    private final HidVolumeTracker tracker;
    private final Sink sink;
    private final Thread thread;
    private volatile boolean running = true;
    private boolean stopped;

    private UsbHidVolumeReader(UsbDeviceConnection connection, UsbInterface intf, UsbEndpoint endpoint,
                               HidVolumeLayout layout, Sink sink) {
        this.connection = connection;
        this.intf = intf;
        this.endpoint = endpoint;
        this.tracker = new HidVolumeTracker(layout);
        this.sink = sink;
        this.thread = new Thread(this::run, "UsbHidVolume");
        this.thread.setDaemon(true);
    }

    /**
     * Finds the volume HID interface, claims it and starts reading. Returns null (holding no
     * claim) if there is none or anything fails.
     *
     * First every HID interface's report descriptor is read without claiming. Only if that finds
     * no volume interface are the interfaces whose descriptor could not be read probed: claimed,
     * read, and released again at once unless they carry the volume usages. Boot keyboards and
     * mice are never probed. Called only on Android TV, under the headset's read lock, so
     * UsbHeadset.close() cannot run during the probe.
     */
    static UsbHidVolumeReader start(UsbDevice device, UsbDeviceConnection conn, byte[] rawDescriptors,
                                    Sink sink) {
        List<HidInterfaces.Info> hids = HidInterfaces.parse(rawDescriptors);
        if (hids.isEmpty()) {
            LimeLog.info("UsbAudio: no HID interface with an interrupt IN endpoint; wheel not taken over");
            return null;
        }
        List<HidInterfaces.Info> unread = new ArrayList<>();
        for (HidInterfaces.Info info : hids) {
            UsbInterface intf = findInterface(device, info.interfaceNumber);
            if (intf == null) continue;
            byte[] report = readReportDescriptor(conn, intf, info);
            if (report == null) {
                unread.add(info);
                continue;
            }
            HidVolumeLayout layout = HidVolumeReportParser.parse(report);
            if (layout == null) {
                LimeLog.info("UsbAudio: HID interface " + info.interfaceNumber + " has no volume usage");
                continue;
            }
            return claimAndStart(conn, intf, info, report, layout, sink, false);
        }
        for (HidInterfaces.Info info : unread) {
            if (info.isBootKeyboardOrMouse()) {
                LimeLog.info("UsbAudio: not probing HID interface " + info.interfaceNumber
                        + " (boot keyboard/mouse)");
                continue;
            }
            UsbInterface intf = findInterface(device, info.interfaceNumber);
            if (intf == null) continue;
            UsbHidVolumeReader r = probe(conn, intf, info, sink);
            if (r != null) return r;
        }
        LimeLog.info("UsbAudio: no HID volume interface found; wheel not taken over");
        return null;
    }

    /**
     * Claims the interface (detaching the kernel driver), reads its report descriptor and keeps
     * the claim only if it carries the volume usages; otherwise releases it at once, which lets
     * Android reconnect the kernel driver. Every path that does not hand the claim to a running
     * reader releases it, exceptions included.
     */
    private static UsbHidVolumeReader probe(UsbDeviceConnection conn, UsbInterface intf,
                                            HidInterfaces.Info info, Sink sink) {
        if (!claim(conn, intf)) {
            LimeLog.warning("UsbAudio: probe: cannot claim HID interface " + info.interfaceNumber);
            return null;
        }
        boolean handedOff = false;
        try {
            int[] result = new int[1];
            byte[] buf = requestReportDescriptor(conn, info, result);
            LimeLog.info("UsbAudio: probe HID interface " + info.interfaceNumber + ": " + result[0] + "/"
                    + info.reportDescriptorLength
                    + (buf != null ? " report=" + UacDescriptorParser.toHex(buf) : ""));
            if (buf == null) return null;
            HidVolumeLayout layout = HidVolumeReportParser.parse(buf);
            if (layout == null) {
                LimeLog.info("UsbAudio: probe: HID interface " + info.interfaceNumber + " has no volume usage");
                return null;
            }
            handedOff = true; // claimAndStart releases it itself if it cannot start
            return claimAndStart(conn, intf, info, buf, layout, sink, true);
        } catch (RuntimeException e) {
            LimeLog.warning("UsbAudio: probe of HID interface " + info.interfaceNumber + " failed: " + e);
            return null;
        } finally {
            if (!handedOff) {
                releaseQuietly(conn, intf);
                LimeLog.info("UsbAudio: probe: released HID interface " + info.interfaceNumber);
            }
        }
    }

    private static boolean claim(UsbDeviceConnection conn, UsbInterface intf) {
        try {
            return conn.claimInterface(intf, true);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Claims (unless already claimed) and starts the reader; on any failure nothing stays claimed. */
    private static UsbHidVolumeReader claimAndStart(UsbDeviceConnection conn, UsbInterface intf,
                                                    HidInterfaces.Info info, byte[] report,
                                                    HidVolumeLayout layout, Sink sink, boolean claimed) {
        boolean started = false;
        try {
            UsbEndpoint ep = findEndpoint(intf, info.inEndpointAddress);
            if (ep == null) {
                LimeLog.warning("UsbAudio: HID endpoint 0x" + Integer.toHexString(info.inEndpointAddress)
                        + " not found on interface " + info.interfaceNumber);
                return null;
            }
            if (!claimed) {
                claimed = claim(conn, intf);
                if (!claimed) {
                    LimeLog.warning("UsbAudio: cannot claim HID interface " + info.interfaceNumber
                            + "; wheel not taken over");
                    return null;
                }
            }
            LimeLog.info("UsbAudio: took over HID interface " + info.interfaceNumber + " ep=0x"
                    + Integer.toHexString(info.inEndpointAddress) + " mps=" + info.inMaxPacketSize
                    + " layout[" + layout + "] report=" + UacDescriptorParser.toHex(report));
            UsbHidVolumeReader r = new UsbHidVolumeReader(conn, intf, ep, layout, sink);
            r.thread.start();
            started = true;
            return r;
        } catch (RuntimeException | OutOfMemoryError e) {
            LimeLog.warning("UsbAudio: cannot start HID reader: " + e);
            return null;
        } finally {
            if (!started && claimed) {
                releaseQuietly(conn, intf);
                LimeLog.info("UsbAudio: released HID interface " + info.interfaceNumber);
            }
        }
    }

    private static UsbInterface findInterface(UsbDevice device, int number) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            if (intf.getId() == number && intf.getAlternateSetting() == 0
                    && intf.getInterfaceClass() == USB_CLASS_HID) {
                return intf;
            }
        }
        return null;
    }

    private static UsbEndpoint findEndpoint(UsbInterface intf, int address) {
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint ep = intf.getEndpoint(i);
            if (ep.getAddress() == address) return ep;
        }
        return null;
    }

    /**
     * GET_DESCRIPTOR(HID Report) to the interface (0x81) without claiming. Linux usbfs only lets
     * that through for an interface it can claim, which a HID interface bound to the kernel
     * driver is not; probe() covers that case.
     */
    private static byte[] readReportDescriptor(UsbDeviceConnection conn, UsbInterface intf,
                                               HidInterfaces.Info info) {
        int[] result = new int[1];
        byte[] d;
        try {
            d = requestReportDescriptor(conn, info, result);
        } catch (RuntimeException e) {
            d = null;
            result[0] = -1;
        }
        if (result[0] > 0) {
            // usbfs may have claimed an interface that had no driver to let this through;
            // drop that claim (a no-op otherwise) so only the volume interface stays taken.
            releaseQuietly(conn, intf);
        }
        if (d == null) {
            LimeLog.info("UsbAudio: HID report descriptor of interface " + info.interfaceNumber
                    + " unclaimed: " + result[0] + "/" + info.reportDescriptorLength);
        }
        return d;
    }

    /**
     * Interface-recipient GET_DESCRIPTOR(HID Report). Asks for one byte more than advertised and
     * accepts only a reply of exactly the advertised length, so a longer descriptor (e.g. another
     * interface's) cannot pass as this one truncated.
     *
     * @param result element 0 receives the transfer result
     * @return the descriptor, or null
     */
    private static byte[] requestReportDescriptor(UsbDeviceConnection conn, HidInterfaces.Info info,
                                                  int[] result) {
        int len = info.reportDescriptorLength;
        result[0] = -1;
        if (len <= 0 || len > MAX_REPORT_DESCRIPTOR) return null;
        byte[] buf = new byte[len + 1];
        int r = conn.controlTransfer(0x81, 0x06, 0x2200, info.interfaceNumber, buf, buf.length,
                CONTROL_TIMEOUT_MS);
        result[0] = r;
        return r == len ? Arrays.copyOf(buf, len) : null;
    }

    private static void releaseQuietly(UsbDeviceConnection conn, UsbInterface intf) {
        try {
            conn.releaseInterface(intf);
        } catch (RuntimeException ignored) {
        }
    }

    private void run() {
        byte[] buf = new byte[Math.max(endpoint.getMaxPacketSize(), 1)];
        int fastFailures = 0;
        while (running) {
            long t0 = SystemClock.uptimeMillis();
            int n;
            try {
                n = connection.bulkTransfer(endpoint, buf, buf.length, READ_TIMEOUT_MS);
            } catch (RuntimeException e) {
                n = -1;
            }
            if (!running) break;
            if (n < 0) {
                // bulkTransfer reports timeouts and errors alike; only quick failures are errors.
                if (SystemClock.uptimeMillis() - t0 >= FAST_FAILURE_MS) {
                    fastFailures = 0;
                    continue;
                }
                if (++fastFailures >= MAX_FAST_FAILURES) {
                    LimeLog.info("UsbAudio: HID reader stopping after repeated read errors (unplugged?)");
                    break;
                }
                SystemClock.sleep(FAST_FAILURE_MS);
                continue;
            }
            fastFailures = 0;
            int step;
            try {
                step = tracker.onReport(buf, n);
            } catch (RuntimeException e) {
                // Never let a malformed report kill the process. Exit like on unplug: close()
                // releases the interface.
                LimeLog.warning("UsbAudio: HID reader stopping, bad report: " + e);
                break;
            }
            if (step != 0) {
                try {
                    sink.onVolumeStep(step);
                } catch (RuntimeException e) {
                    LimeLog.warning("UsbAudio: volume step failed: " + e);
                }
            }
        }
    }

    /**
     * Stops the reader (bounded wait) and releases the interface, which returns it to the kernel
     * driver. Idempotent. Must be called before the connection is closed, never from the reader.
     */
    synchronized void stop() {
        if (stopped) return;
        stopped = true;
        running = false;
        try {
            thread.join(JOIN_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            LimeLog.warning("UsbAudio: HID reader did not stop within " + JOIN_TIMEOUT_MS + " ms");
        }
        release();
    }

    private void release() {
        releaseQuietly(connection, intf);
        LimeLog.info("UsbAudio: released HID interface " + intf.getId());
    }
}
