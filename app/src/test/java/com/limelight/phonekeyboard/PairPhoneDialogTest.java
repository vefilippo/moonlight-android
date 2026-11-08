package com.limelight.phonekeyboard;

import static org.junit.Assert.assertEquals;

import android.graphics.Bitmap;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33})
public class PairPhoneDialogTest {
    @Test
    public void qrRendersSquareBitmap() {
        Bitmap b = PairPhoneDialog.renderQr("http://192.168.178.35:47900/pair/abc", 600);
        assertEquals(600, b.getWidth());
        assertEquals(600, b.getHeight());
    }
}
