package com.limelight.binding.audio.usb;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.os.SystemClock;

import com.limelight.LimeLog;

import java.util.List;

/**
 * Android TV only: takes over the headset's HID interface that reports Consumer Volume
 * Increment/Decrement (Android TV consumes volume keys before any app sees them) and reads
 * the wheel on a daemon thread. stop() hands the interface back: releaseInterface makes
 * Android reconnect the kernel HID driver.
 *
 * Only an interface whose report descriptor has a volume usage in an Input report is claimed.
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
     * Finds the volume HID interface, claims it and starts reading. Returns null (claiming
     * nothing) if there is none or anything fails.
     */
    static UsbHidVolumeReader start(UsbDevice device, UsbDeviceConnection conn, byte[] rawDescriptors,
                                    Sink sink) {
        List<HidInterfaces.Info> hids = HidInterfaces.parse(rawDescriptors);
        if (hids.isEmpty()) {
            LimeLog.info("UsbAudio: no HID interface with an interrupt IN endpoint; wheel not taken over");
            return null;
        }
        for (HidInterfaces.Info info : hids) {
            UsbInterface intf = findInterface(device, info.interfaceNumber);
            if (intf == null) continue;
            byte[] report = readReportDescriptor(conn, intf, info);
            if (report == null) continue;
            HidVolumeLayout layout = HidVolumeReportParser.parse(report);
            if (layout == null) {
                LimeLog.info("UsbAudio: HID interface " + info.interfaceNumber + " has no volume usage");
                continue;
            }
            UsbEndpoint ep = findEndpoint(intf, info.inEndpointAddress);
            if (ep == null) {
                LimeLog.warning("UsbAudio: HID endpoint 0x" + Integer.toHexString(info.inEndpointAddress)
                        + " not found on interface " + info.interfaceNumber);
                return null;
            }
            boolean claimed;
            try {
                claimed = conn.claimInterface(intf, true);
            } catch (RuntimeException e) {
                claimed = false;
            }
            if (!claimed) {
                LimeLog.warning("UsbAudio: cannot claim HID interface " + info.interfaceNumber
                        + "; wheel not taken over");
                return null;
            }
            LimeLog.info("UsbAudio: took over HID interface " + info.interfaceNumber + " ep=0x"
                    + Integer.toHexString(info.inEndpointAddress) + " mps=" + info.inMaxPacketSize
                    + " layout[" + layout + "] report=" + UacDescriptorParser.toHex(report));
            UsbHidVolumeReader r = new UsbHidVolumeReader(conn, intf, ep, layout, sink);
            try {
                r.thread.start();
            } catch (RuntimeException | OutOfMemoryError e) {
                LimeLog.warning("UsbAudio: cannot start HID reader: " + e);
                r.running = false;
                r.release();
                return null;
            }
            return r;
        }
        LimeLog.info("UsbAudio: no HID volume interface found; wheel not taken over");
        return null;
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
     * GET_DESCRIPTOR(HID Report) without claiming. The standard request goes to the interface
     * (0x81); Linux usbfs only lets that through for an interface it can claim, which a HID
     * interface bound to the kernel driver is not, so the device-recipient form (0x80) is tried
     * too. A reply must have exactly the advertised length, so a device that ignores wIndex
     * cannot make one interface look like another.
     */
    private static byte[] readReportDescriptor(UsbDeviceConnection conn, UsbInterface intf,
                                               HidInterfaces.Info info) {
        int len = info.reportDescriptorLength;
        if (len <= 0 || len > MAX_REPORT_DESCRIPTOR) return null;
        int[] requestTypes = {0x81, 0x80};
        for (int requestType : requestTypes) {
            byte[] buf = new byte[len];
            int r;
            try {
                r = conn.controlTransfer(requestType, 0x06, 0x2200, info.interfaceNumber, buf, len,
                        CONTROL_TIMEOUT_MS);
            } catch (RuntimeException e) {
                r = -1;
            }
            if (requestType == 0x81 && r > 0) {
                // usbfs may have claimed an interface that had no driver to let this through;
                // drop that claim (a no-op otherwise) so only the volume interface stays taken.
                releaseQuietly(conn, intf);
            }
            if (r == len) return buf;
            LimeLog.info("UsbAudio: HID report descriptor of interface " + info.interfaceNumber
                    + " via 0x" + Integer.toHexString(requestType) + ": " + r + "/" + len);
        }
        return null;
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
            int step = tracker.onReport(buf, n);
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
