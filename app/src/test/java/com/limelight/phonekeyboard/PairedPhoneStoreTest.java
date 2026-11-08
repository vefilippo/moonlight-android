package com.limelight.phonekeyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33})
public class PairedPhoneStoreTest {
    private long now = 0;
    private SharedPreferences prefs;
    private PairedPhoneStore store;

    @Before public void setUp() {
        prefs = ((android.content.Context) ApplicationProvider.getApplicationContext())
                .getSharedPreferences("test_phones", 0);
        prefs.edit().clear().commit();
        store = new PairedPhoneStore(prefs, () -> now);
    }

    @Test public void addedPhoneIsFoundBySecretAndSurvivesReload() {
        PairedPhone p = store.addPhone("iPhone");
        assertEquals(43, p.secret.length());
        assertEquals(p.id, new PairedPhoneStore(prefs, () -> 0L).findBySecret(p.secret).id);
    }
    @Test public void unknownOrNullSecretIsNotFound() {
        store.addPhone("x");
        assertNull(store.findBySecret("nope"));
        assertNull(store.findBySecret(null));
    }
    @Test public void removedPhoneIsGone() {
        PairedPhone p = store.addPhone("x");
        assertTrue(store.remove(p.id));
        assertNull(store.findBySecret(p.secret));
        assertTrue(store.isEmpty());
    }
    @Test public void markUsedUpdatesLastUsed() {
        now = 1000; PairedPhone p = store.addPhone("x");
        now = 5000; store.markUsed(p.id);
        assertEquals(5000, store.list().get(0).lastUsedMs);
    }
}
