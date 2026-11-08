package com.limelight.phonekeyboard;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33})
public class PhoneKeyboardServiceTest {
    private PhoneKeyboardService service;

    @Before
    public void setUp() {
        service = new PhoneKeyboardService(ApplicationProvider.getApplicationContext());
    }

    @After
    public void tearDown() {
        for (PairedPhone p : service.store().list()) service.store().remove(p.id);
        service.endPairing();
    }

    @Test public void notRunningWithNoPhonesAndNoPairing() {
        service.refresh();
        assertFalse(service.isRunning());
    }

    @Test public void runsWhilePairingAndStopsAfter() {
        service.startPairing();
        assertTrue(service.isRunning());
        service.endPairing();
        assertFalse(service.isRunning());
    }

    @Test public void keepsRunningWhileAPhoneIsPaired() {
        service.store().addPhone("x");
        service.refresh();
        assertTrue(service.isRunning());
    }
}
