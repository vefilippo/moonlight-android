package com.limelight.phonekeyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Exercises the real NanoHTTPD transport on 127.0.0.1:47900. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33})
public class PhoneKeyboardServerTest {
    private PhoneKeyboardService service;
    private PairedPhone phone;

    @Before
    public void setUp() {
        service = new PhoneKeyboardService(ApplicationProvider.getApplicationContext());
        for (PairedPhone p : service.store().list()) service.store().remove(p.id);
        phone = service.store().addPhone("test phone");
        service.refresh();
        assertTrue("server should be running", service.isRunning());
    }

    @After
    public void tearDown() {
        for (PairedPhone p : service.store().list()) service.store().remove(p.id);
        service.endPairing(); // refresh() stops the server once nothing needs it
    }

    private HttpURLConnection open(String method, String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + PhoneKeyboardServer.PORT + path).openConnection();
        c.setRequestMethod(method);
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(5000);
        c.setReadTimeout(5000);
        return c;
    }

    private HttpURLConnection post(String path, String key, String json) throws Exception {
        HttpURLConnection c = open("POST", path);
        if (key != null) c.setRequestProperty("X-Pheme-Key", key);
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(b.length);
        try (OutputStream out = c.getOutputStream()) { out.write(b); }
        return c;
    }

    @Test public void pageIsServedAsHtmlWithNoStore() throws Exception {
        HttpURLConnection c = open("GET", "/k/" + phone.secret);
        assertEquals(200, c.getResponseCode());
        assertTrue(c.getContentType().startsWith("text/html"));
        assertEquals("no-store", c.getHeaderField("Cache-Control"));
    }

    @Test public void textWithKeyButNoStreamIs409() throws Exception {
        HttpURLConnection c = post("/api/text", phone.secret, "{\"text\":\"a\"}");
        assertEquals(409, c.getResponseCode());
        assertEquals("no-store", c.getHeaderField("Cache-Control"));
    }

    @Test public void textWithoutKeyIs401() throws Exception {
        HttpURLConnection c = post("/api/text", null, "{\"text\":\"a\"}");
        assertEquals(401, c.getResponseCode());
        assertEquals("no-store", c.getHeaderField("Cache-Control"));
    }

    @Test public void pairingRedirectsToTheNewPhonesPage() throws Exception {
        String code = service.codes().issue();
        HttpURLConnection c = open("GET", "/pair/" + code);
        assertEquals(302, c.getResponseCode());
        String location = c.getHeaderField("Location");
        assertNotNull(location);
        assertTrue(location, location.startsWith("/k/"));
        String secret = location.substring("/k/".length());
        assertNotNull(service.store().findBySecret(secret));
        assertEquals("no-store", c.getHeaderField("Cache-Control"));
    }

    @Test public void usedPairingCodeIsGone() throws Exception {
        String code = service.codes().issue();
        assertEquals(302, open("GET", "/pair/" + code).getResponseCode());
        assertEquals(410, open("GET", "/pair/" + code).getResponseCode());
    }
}
