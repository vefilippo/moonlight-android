package com.limelight.binding.audio.usb;

import static com.limelight.binding.audio.usb.HidVolumeReportParserTest.bytes;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class HidInterfacesTest {

    private static byte[] fixture(String name) {
        try (InputStream in = HidInterfacesTest.class.getResourceAsStream("/usb/" + name)) {
            assertNotNull("missing fixture " + name, in);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().startsWith("#")) continue;
                for (String tok : line.trim().split("\\s+")) {
                    if (!tok.isEmpty()) out.write(Integer.parseInt(tok, 16));
                }
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test public void g435HasTwoHidInterfaces() {
        List<HidInterfaces.Info> l = HidInterfaces.parse(fixture("g435_descriptors.hex"));
        assertEquals(2, l.size());

        HidInterfaces.Info hidpp = l.get(0);
        assertEquals(0, hidpp.interfaceNumber);
        assertEquals(34, hidpp.reportDescriptorLength);
        assertEquals(0x83, hidpp.inEndpointAddress);
        assertEquals(64, hidpp.inMaxPacketSize);

        HidInterfaces.Info consumer = l.get(1);
        assertEquals(4, consumer.interfaceNumber);
        assertEquals(23, consumer.reportDescriptorLength);
        assertEquals(0x81, consumer.inEndpointAddress);
        assertEquals(8, consumer.inMaxPacketSize);
    }

    @Test public void interfaceWithoutInterruptInIsSkipped() {
        byte[] raw = bytes(
                0x09, 0x02, 0x22, 0x00, 0x01, 0x01, 0x00, 0x80, 0x32,
                0x09, 0x04, 0x00, 0x00, 0x01, 0x03, 0x00, 0x00, 0x00,
                0x09, 0x21, 0x11, 0x01, 0x00, 0x01, 0x22, 0x17, 0x00,
                0x07, 0x05, 0x01, 0x03, 0x08, 0x00, 0x08); // OUT endpoint only
        assertTrue(HidInterfaces.parse(raw).isEmpty());
    }

    @Test public void garbageIsSafe() {
        assertTrue(HidInterfaces.parse(null).isEmpty());
        assertTrue(HidInterfaces.parse(bytes(0x09, 0x04, 0x00)).isEmpty());
        assertTrue(HidInterfaces.parse(bytes(0x00, 0x04, 0x00, 0x00)).isEmpty());
    }
}
