package com.limelight.binding.audio.usb;

import java.util.ArrayList;
import java.util.List;

/**
 * Lists the HID (class 3) interfaces in raw USB descriptors (as from
 * UsbDeviceConnection.getRawDescriptors) with their report descriptor length and
 * interrupt IN endpoint. Alternate setting 0 only. Pure Java.
 */
final class HidInterfaces {
    static final class Info {
        final int interfaceNumber;
        /** wDescriptorLength of the HID Report descriptor (type 0x22). */
        final int reportDescriptorLength;
        final int inEndpointAddress;
        final int inMaxPacketSize;
        final int subclass;
        final int protocol;

        Info(int interfaceNumber, int reportDescriptorLength, int inEndpointAddress, int inMaxPacketSize,
             int subclass, int protocol) {
            this.interfaceNumber = interfaceNumber;
            this.reportDescriptorLength = reportDescriptorLength;
            this.inEndpointAddress = inEndpointAddress;
            this.inMaxPacketSize = inMaxPacketSize;
            this.subclass = subclass;
            this.protocol = protocol;
        }

        /** Boot-protocol keyboard (subclass 1, protocol 1) or mouse (subclass 1, protocol 2). */
        boolean isBootKeyboardOrMouse() {
            return subclass == 1 && (protocol == 1 || protocol == 2);
        }
    }

    private static final int DT_INTERFACE = 0x04;
    private static final int DT_ENDPOINT = 0x05;
    private static final int DT_HID = 0x21;
    private static final int DT_HID_REPORT = 0x22;
    private static final int USB_CLASS_HID = 3;

    private HidInterfaces() {}

    /** Interfaces with both a report descriptor length and an interrupt IN endpoint; never null. */
    static List<Info> parse(byte[] raw) {
        List<Info> out = new ArrayList<>();
        if (raw == null) return out;
        int intf = -1, reportLen = 0, ep = 0, mps = 0, sub = 0, proto = 0;
        boolean inHid = false;
        int i = 0;
        while (i + 1 < raw.length) {
            int len = raw[i] & 0xFF;
            if (len < 2 || i + len > raw.length) break;
            int type = raw[i + 1] & 0xFF;
            if (type == DT_INTERFACE && len >= 9) {
                if (inHid) add(out, intf, reportLen, ep, mps, sub, proto);
                inHid = (raw[i + 5] & 0xFF) == USB_CLASS_HID && (raw[i + 3] & 0xFF) == 0;
                intf = raw[i + 2] & 0xFF;
                sub = raw[i + 6] & 0xFF;
                proto = raw[i + 7] & 0xFF;
                reportLen = 0;
                ep = 0;
                mps = 0;
            } else if (inHid && type == DT_HID && len >= 6) {
                int n = raw[i + 5] & 0xFF;
                for (int k = 0; k < n && 6 + 3 * k + 2 < len; k++) {
                    int o = i + 6 + 3 * k;
                    if ((raw[o] & 0xFF) == DT_HID_REPORT) {
                        reportLen = (raw[o + 1] & 0xFF) | ((raw[o + 2] & 0xFF) << 8);
                        break;
                    }
                }
            } else if (inHid && type == DT_ENDPOINT && len >= 7 && ep == 0) {
                int addr = raw[i + 2] & 0xFF;
                int attrs = raw[i + 3] & 0xFF;
                if ((addr & 0x80) != 0 && (attrs & 0x03) == 0x03) {
                    ep = addr;
                    mps = ((raw[i + 4] & 0xFF) | ((raw[i + 5] & 0xFF) << 8)) & 0x7FF;
                }
            }
            i += len;
        }
        if (inHid) add(out, intf, reportLen, ep, mps, sub, proto);
        return out;
    }

    private static void add(List<Info> out, int intf, int reportLen, int ep, int mps, int sub, int proto) {
        if (reportLen > 0 && ep != 0 && mps > 0) out.add(new Info(intf, reportLen, ep, mps, sub, proto));
    }
}
