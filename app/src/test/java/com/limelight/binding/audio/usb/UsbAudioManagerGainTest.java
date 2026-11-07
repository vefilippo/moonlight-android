package com.limelight.binding.audio.usb;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.shadows.ShadowMoonBridge;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, shadows = {ShadowMoonBridge.class})
public class UsbAudioManagerGainTest {
    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit();
    }

    @Test
    public void gainDefaultsToModerateAndPersistsPerHeadset() {
        UsbAudioManager m = new UsbAudioManager(context);
        assertEquals(PcmConverter.DEFAULT_GAIN_LEVEL, m.getGainLevel("046d:0adf"));

        m.setGainLevel("046d:0adf", 12);
        UsbAudioManager fresh = new UsbAudioManager(context);
        assertEquals(12, fresh.getGainLevel("046d:0adf"));
        assertEquals(12, PreferenceManager.getDefaultSharedPreferences(context)
                .getInt("usb_audio_gain_046d_0adf", -1));
        assertEquals(PcmConverter.DEFAULT_GAIN_LEVEL, fresh.getGainLevel("1234:5678"));

        fresh.setGainLevel("046d:0adf", 40);
        assertEquals(15, fresh.getGainLevel("046d:0adf"));
        fresh.setGainLevel("046d:0adf", -3);
        assertEquals(0, fresh.getGainLevel("046d:0adf"));
    }

    @Test
    public void hardwareVolumeMakesSoftwareGainUnityAndReceivesLevels() {
        UsbAudioManager m = new UsbAudioManager(context);
        m.setGainLevel("046d:0adf", 4);
        java.util.List<Integer> levels = new java.util.ArrayList<>();
        m.attachHardwareVolume("046d:0adf", levels::add);
        assertEquals(1.0f, m.currentGain(), 0f);

        m.setGainLevel("046d:0adf", 7);
        m.setGainLevel("1234:5678", 2); // another headset: not forwarded
        assertEquals(java.util.Arrays.asList(7), levels);
        assertEquals(1.0f, m.currentGain(), 0f);
        assertEquals(7, m.getGainLevel("046d:0adf"));

        m.attachHardwareVolume(null, null);
        m.setGainLevel("046d:0adf", 9);
        assertEquals(java.util.Arrays.asList(7), levels);
        assertEquals(PcmConverter.gainForLevel(PcmConverter.DEFAULT_GAIN_LEVEL), m.currentGain(), 0f);
    }

    @Test
    public void noDeviceMeansDefaultLevelAndNoOpSet() {
        UsbAudioManager m = new UsbAudioManager(context);
        assertEquals(PcmConverter.DEFAULT_GAIN_LEVEL, m.getGainLevel());
        m.setGainLevel(3);
        assertEquals(PcmConverter.DEFAULT_GAIN_LEVEL, m.getGainLevel());
        assertEquals(PcmConverter.gainForLevel(PcmConverter.DEFAULT_GAIN_LEVEL), m.currentGain(), 0f);
    }
}
