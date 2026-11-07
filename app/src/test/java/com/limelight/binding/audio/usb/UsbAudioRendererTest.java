package com.limelight.binding.audio.usb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.shadows.ShadowMoonBridge;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, shadows = {ShadowMoonBridge.class})
public class UsbAudioRendererTest {

    private static final MoonBridge.AudioConfiguration STEREO = new MoonBridge.AudioConfiguration(2, 0x3);
    private static final MoonBridge.AudioConfiguration SURROUND_51 = new MoonBridge.AudioConfiguration(6, 0x3F);

    private FakeProvider provider;
    private FakeFallback fallback;
    private long now;
    private UsbAudioRenderer renderer;

    @Before
    public void setUp() {
        ShadowMoonBridge.reset();
        provider = new FakeProvider();
        fallback = new FakeFallback();
        now = 1_000_000;
        renderer = new UsbAudioRenderer(provider, fallback, () -> now);
    }

    @Test
    public void headsetReadyUsesUsbAndNeverTouchesFallback() {
        provider.ready = true;
        assertEquals(0, renderer.setup(STEREO, 48000, 240));
        renderer.start();
        renderer.playDecodedAudio(new short[240 * 2]);

        assertEquals(1, provider.outputs);
        assertTrue(provider.output.started);
        assertEquals(960, provider.output.bytes.size());
        assertEquals(0, fallback.setupCalls);
        assertEquals(0, fallback.startCalls);
        assertEquals(0, fallback.plays);

        renderer.stop();
        renderer.cleanup();
        assertEquals(1, provider.output.closeCalls);
        assertEquals(0, fallback.stopCalls);
        assertEquals(0, fallback.cleanupCalls);
    }

    @Test
    public void noHeadsetUsesFallback() {
        provider.ready = false;
        assertEquals(0, renderer.setup(STEREO, 48000, 240));
        renderer.start();
        renderer.playDecodedAudio(new short[480]);
        renderer.playDecodedAudio(new short[480]);

        assertEquals(1, fallback.setupCalls);
        assertEquals(1, fallback.startCalls);
        assertEquals(2, fallback.plays);
        assertEquals(0, provider.outputs);

        renderer.stop();
        renderer.cleanup();
        assertEquals(1, fallback.stopCalls);
        assertEquals(1, fallback.cleanupCalls);
    }

    @Test
    public void fallbackSetupResultIsReturned() {
        provider.ready = false;
        fallback.setupResult = -2;
        assertEquals(-2, renderer.setup(STEREO, 48000, 240));
    }

    @Test
    public void unplugMidStreamFallsBackToAndroid() {
        provider.ready = true;
        assertEquals(0, renderer.setup(STEREO, 48000, 240));
        renderer.start();
        renderer.playDecodedAudio(new short[480]);
        FakeOutput out = provider.output;

        out.writeResult = -19;
        short[] frame = new short[480];
        frame[0] = 1234;
        renderer.playDecodedAudio(frame);

        assertEquals(1, out.closeCalls);
        assertEquals(1, fallback.setupCalls);
        assertEquals(1, fallback.startCalls);
        assertEquals(1, fallback.plays);
        assertEquals(1234, fallback.lastFrame[0]);

        renderer.playDecodedAudio(new short[480]);
        assertEquals(2, fallback.plays);
        assertEquals(1, fallback.setupCalls);

        renderer.cleanup();
        assertEquals(1, out.closeCalls);
        assertEquals(1, fallback.cleanupCalls);
    }

    @Test
    public void deadOutputFallsBackToAndroid() {
        provider.ready = true;
        renderer.setup(STEREO, 48000, 240);
        renderer.start();
        provider.output.alive = false;
        renderer.playDecodedAudio(new short[480]);

        assertEquals(1, provider.output.closeCalls);
        assertEquals(0, provider.output.bytes.size());
        assertEquals(1, fallback.setupCalls);
        assertEquals(1, fallback.plays);
    }

    @Test
    public void headsetAppearingMidStreamTakesOverAfterInterval() {
        provider.ready = false;
        renderer.setup(STEREO, 48000, 240);
        renderer.start();
        renderer.playDecodedAudio(new short[480]);
        assertEquals(1, fallback.plays);

        provider.ready = true;
        now += 400;
        renderer.playDecodedAudio(new short[480]);
        assertEquals(2, fallback.plays);
        assertEquals(0, provider.outputs);

        now += 200; // +600 ms
        renderer.playDecodedAudio(new short[480]);
        assertEquals(1, provider.outputs);
        assertEquals(1, fallback.stopCalls);
        assertEquals(1, fallback.cleanupCalls);
        assertEquals(2, fallback.plays);
        assertEquals(960, provider.output.bytes.size());

        renderer.playDecodedAudio(new short[480]);
        assertEquals(1920, provider.output.bytes.size());

        renderer.cleanup();
        assertEquals(1, fallback.cleanupCalls);
        assertEquals(1, provider.output.closeCalls);
    }

    @Test
    public void failedTakeoverClosesOutputAndStaysOnFallback() {
        provider.ready = false;
        renderer.setup(STEREO, 48000, 240);
        renderer.start();
        provider.ready = true;
        provider.startResult = false;
        now += 600;
        renderer.playDecodedAudio(new short[480]);

        assertEquals(1, provider.outputs);
        assertEquals(1, provider.output.closeCalls);
        assertEquals(0, fallback.stopCalls);
        assertEquals(1, fallback.plays);
    }

    @Test
    public void reconnectAfterUnplugReturnsToUsb() {
        provider.ready = true;
        renderer.setup(STEREO, 48000, 240);
        renderer.start();
        FakeOutput first = provider.output;
        first.writeResult = -19;
        renderer.playDecodedAudio(new short[480]);
        assertEquals(1, fallback.setupCalls);

        now += 600;
        renderer.playDecodedAudio(new short[480]);
        assertEquals(2, provider.outputs);
        assertEquals(1, fallback.cleanupCalls);
        assertEquals(960, provider.output.bytes.size());

        renderer.cleanup();
        assertEquals(1, first.closeCalls);
        assertEquals(1, provider.output.closeCalls);
        assertEquals(1, fallback.cleanupCalls);
    }

    @Test
    public void surroundStreamIsDownmixed() {
        provider.ready = true;
        renderer.setup(SURROUND_51, 48000, 240);
        renderer.start();
        renderer.playDecodedAudio(new short[240 * 6]);
        assertEquals(240 * 2 * 2, provider.output.bytes.size());
    }

    @Test
    public void frameLongerThanBufferIsPlayedInChunks() {
        provider.ready = true;
        renderer.setup(STEREO, 48000, 240);
        renderer.start();
        short[] frame = new short[1200 * 2];
        frame[frame.length - 1] = 0x1234;
        renderer.playDecodedAudio(frame);

        byte[] b = provider.output.bytes.toByteArray();
        assertEquals(1200 * 4, b.length);
        assertEquals(0x34, b[b.length - 2] & 0xFF);
        assertEquals(0x12, b[b.length - 1] & 0xFF);
    }

    @Test
    public void gainIsAppliedFromProvider() {
        provider.ready = true;
        provider.gain = 0.5f;
        renderer.setup(STEREO, 48000, 240);
        renderer.start();
        short[] frame = new short[480];
        for (int i = 0; i < frame.length; i++) frame[i] = 0x1000;
        renderer.playDecodedAudio(frame);

        byte[] b = provider.output.bytes.toByteArray();
        assertEquals(960, b.length);
        assertEquals(0x00, b[0] & 0xFF);
        assertEquals(0x08, b[1] & 0xFF);
        assertEquals(0x00, b[958] & 0xFF);
        assertEquals(0x08, b[959] & 0xFF);
    }

    @Test
    public void resamplesTo441kDevice() {
        provider.ready = true;
        provider.rate = 44100;
        renderer.setup(STEREO, 48000, 240);
        renderer.start();
        for (int i = 0; i < 100; i++) renderer.playDecodedAudio(new short[480]);

        int expected = (int) (44100L * 24000 / 48000 * 4);
        int actual = provider.output.bytes.size();
        assertTrue("bytes=" + actual, Math.abs(actual - expected) <= 8);
    }

    // ---- Fakes ----

    private static final class FakeOutput implements UsbAudioRenderer.PlaybackOutput {
        final UacStreamConfig config;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean startResult = true;
        boolean started;
        boolean alive = true;
        int writeResult = Integer.MIN_VALUE; // MIN_VALUE: accept everything
        int closeCalls;

        FakeOutput(UacStreamConfig config) {
            this.config = config;
        }

        @Override public UacStreamConfig config() { return config; }
        @Override public boolean start() { started = startResult; return startResult; }

        @Override
        public int write(byte[] data, int length) {
            if (writeResult != Integer.MIN_VALUE) return writeResult;
            bytes.write(data, 0, length);
            return length;
        }

        @Override public boolean isAlive() { return alive; }
        @Override public void close() { closeCalls++; }
    }

    private static final class FakeProvider implements UsbAudioRenderer.PlaybackProvider {
        boolean ready;
        boolean startResult = true;
        float gain = 1.0f;
        int rate = 48000;
        int outputs;
        FakeOutput output;

        @Override public boolean isHeadsetReady() { return ready; }

        @Override
        public UsbAudioRenderer.PlaybackOutput openPlayback() {
            UacDescriptors.AltSetting alt = new UacDescriptors.AltSetting();
            alt.channels = 2;
            alt.subframeBytes = 2;
            alt.bitResolution = 16;
            output = new FakeOutput(new UacStreamConfig(alt, rate));
            output.startResult = startResult;
            outputs++;
            return output;
        }

        @Override public float currentGain() { return gain; }
    }

    private static final class FakeFallback implements AudioRenderer {
        int setupResult = 0;
        int setupCalls, startCalls, stopCalls, cleanupCalls, plays;
        short[] lastFrame;

        @Override
        public int setup(MoonBridge.AudioConfiguration audioConfiguration, int sampleRate, int samplesPerFrame) {
            setupCalls++;
            return setupResult;
        }

        @Override public void start() { startCalls++; }
        @Override public void stop() { stopCalls++; }

        @Override
        public void playDecodedAudio(short[] audioData) {
            plays++;
            lastFrame = audioData.clone();
        }

        @Override public void cleanup() { cleanupCalls++; }
    }
}
