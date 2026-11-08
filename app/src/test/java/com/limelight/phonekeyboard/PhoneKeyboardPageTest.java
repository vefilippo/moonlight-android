package com.limelight.phonekeyboard;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33})
public class PhoneKeyboardPageTest {
    private String readAsset(String path) throws Exception {
        Context c = ApplicationProvider.getApplicationContext();
        try (InputStream in = c.getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    @Test public void pageIsSelfContainedAndUsesTheApi() throws Exception {
        String html = readAsset("phone_keyboard/index.html");
        assertFalse("no external resources", html.matches("(?s).*(src|href)=\"https?://.*"));
        assertFalse("no url() or http refs", html.contains("http://") || html.contains("https://"));
        assertTrue(html.contains("/api/text")); assertTrue(html.contains("/api/key")); assertTrue(html.contains("/api/status"));
        assertTrue(html.contains("X-Pheme-Key"));
        for (String k : new String[]{"enter","backspace","tab","esc","left","up","down","right"}) assertTrue(k, html.contains("'" + k + "'"));
        assertTrue(html.contains("allow keyboard input for this client"));
    }
}
