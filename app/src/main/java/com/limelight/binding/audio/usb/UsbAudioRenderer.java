package com.limelight.binding.audio.usb;

import com.limelight.LimeLog;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.jni.MoonBridge;

/**
 * Game-audio renderer that plays to the Artemis-driven USB headset when one is available and
 * otherwise delegates to a fallback renderer (AndroidAudioRenderer), switching live in both
 * directions: to the fallback on unplug/stream death, back to USB when a headset becomes ready.
 *
 * Threading: all AudioRenderer methods are synchronized. playDecodedAudio runs on the audio
 * decoder thread; it allocates only when switching outputs and never lets exceptions escape.
 */
public class UsbAudioRenderer implements AudioRenderer {
    /** Stream input format: 48 kHz interleaved 16-bit PCM. */
    private static final int INPUT_RATE = 48000;
    private static final int MAX_INPUT_CHANNELS = 8;
    /** How often the fallback path polls for a usable headset. */
    static final long RECHECK_INTERVAL_MS = 500;
    /** Cap for the doubling retry delay after consecutive failed USB opens. */
    static final long MAX_RECHECK_INTERVAL_MS = 30_000;
    /** How often USB playback statistics are logged while on USB. */
    static final long STATS_INTERVAL_MS = 10_000;

    /** One acquired playback stream. close() releases it and is called exactly once. */
    public interface PlaybackOutput {
        UacStreamConfig config();
        boolean start();
        /** Bytes accepted, or negative if the stream is gone/closed. */
        int write(byte[] data, int length);
        boolean isAlive();
        /** One-line driver statistics, for logging. */
        String stats();
        void close();
    }

    public interface PlaybackProvider {
        boolean isHeadsetReady();
        /** Nullable. */
        PlaybackOutput openPlayback();
        float currentGain();
        /** Changes whenever headset availability may have changed; resets the retry backoff. */
        int availabilityGeneration();
    }

    /**
     * Millisecond clock. Declared here instead of java.util.function.LongSupplier, which does not
     * exist below API 24 (minSdk is 21 and core-library desugaring is not enabled).
     */
    public interface LongSupplier {
        long getAsLong();
    }

    private final PlaybackProvider provider;
    private final AudioRenderer fallback;
    private final LongSupplier clockMs;

    // Stored stream parameters
    private MoonBridge.AudioConfiguration audioConfig;
    private int sampleRate;
    private int samplesPerFrame;
    private int inChannels;
    private boolean started;
    /** Between setup() and cleanup(). */
    private boolean setUp;

    // USB path; output != null means USB is the active path
    private PlaybackOutput output;
    private int devChannels;
    private int subframeBytes;
    private int chunkFrames;
    private LinearResampler resampler;
    private short[] inBuf;
    private short[] mixBuf;
    private short[] resampleBuf;
    private byte[] packBuf;

    private long lastStatsMs;
    /** When the current USB output was opened; a loss sooner than STATS_INTERVAL_MS counts as a failed attempt. */
    private long openedAtMs;

    // Fallback path
    private boolean fallbackSetUp;
    private long lastCheckMs;
    /**
     * Current USB retry delay: doubles after each failed open and after each loss of a USB output
     * that lived less than STATS_INTERVAL_MS; reset by a loss after a longer life or a new generation.
     */
    private long retryDelayMs = RECHECK_INTERVAL_MS;
    private int generation;
    /** The last tryOpenUsb got past the readiness check (a real open attempt). */
    private boolean openAttempted;

    public UsbAudioRenderer(PlaybackProvider provider, AudioRenderer fallback, LongSupplier clockMs) {
        this.provider = provider;
        this.fallback = fallback;
        this.clockMs = clockMs;
    }

    @Override
    public synchronized int setup(MoonBridge.AudioConfiguration audioConfiguration, int sampleRate, int samplesPerFrame) {
        this.audioConfig = audioConfiguration;
        this.sampleRate = sampleRate;
        this.samplesPerFrame = samplesPerFrame;
        this.inChannels = Math.max(1, Math.min(MAX_INPUT_CHANNELS, audioConfiguration.channelCount));
        setUp = true;

        if (openUsbWithBackoff()) {
            return 0;
        }
        lastCheckMs = clockMs.getAsLong();
        int r = fallback.setup(audioConfiguration, sampleRate, samplesPerFrame);
        fallbackSetUp = r == 0;
        return r;
    }

    @Override
    public synchronized void start() {
        started = true;
        if (output == null && fallbackSetUp) {
            fallback.start();
        }
    }

    @Override
    public synchronized void stop() {
        started = false;
        if (output == null && fallbackSetUp) {
            fallback.stop();
        }
    }

    @Override
    public synchronized void playDecodedAudio(short[] audioData) {
        if (output != null) {
            if (playUsb(audioData)) return;
            switchToFallback();
        } else {
            maybeSwitchToUsb(false);
            if (output != null && playUsb(audioData)) return;
            if (output != null) switchToFallback();
        }
        if (fallbackSetUp) {
            fallback.playDecodedAudio(audioData);
        }
    }

    /**
     * Runs the fallback path's headset check without an audio frame. playDecodedAudio is the
     * only other caller, and the host sends no audio packets while it is silent, so without this
     * a headset that becomes ready during silence is not taken over until the game makes a
     * sound. Call it off the main thread (an open does blocking USB control transfers) whenever
     * headset availability changes. A changed availability generation skips the retry delay;
     * otherwise the backoff applies. No-op outside setup()..cleanup() and while on USB.
     */
    public synchronized void pollUsb() {
        if (!setUp || output != null) return;
        maybeSwitchToUsb(true);
    }

    @Override
    public synchronized void cleanup() {
        setUp = false;
        closeOutput();
        if (fallbackSetUp) {
            fallbackSetUp = false;
            fallback.cleanup();
        }
    }

    // ---- USB path ----

    /**
     * tryOpenUsb plus retry backoff: a real attempt that fails doubles the delay before the next
     * one (capped). Success leaves the delay alone: it is reset only once the output has stayed
     * alive for STATS_INTERVAL_MS (see onUsbLost), so open-then-immediate-stall cannot cycle at
     * the base interval. Not-ready checks are cheap and do not back off.
     */
    private boolean openUsbWithBackoff() {
        generation = provider.availabilityGeneration();
        if (tryOpenUsb()) {
            return true;
        }
        if (openAttempted) {
            growRetryDelay();
            LimeLog.info("UsbAudio: USB playback open failed; next attempt in " + retryDelayMs + " ms");
        }
        return false;
    }

    private void growRetryDelay() {
        retryDelayMs = Math.min(retryDelayMs * 2, MAX_RECHECK_INTERVAL_MS);
    }

    /** Backoff bookkeeping for a USB output that was just lost at time now. */
    private void onUsbLost(long now) {
        if (now - openedAtMs < STATS_INTERVAL_MS) {
            growRetryDelay();
            LimeLog.info("UsbAudio: USB playback lost " + (now - openedAtMs)
                    + " ms after opening; next attempt in " + retryDelayMs + " ms");
        } else {
            retryDelayMs = RECHECK_INTERVAL_MS;
        }
    }

    /** Opens and starts a USB output and sizes the buffers for it. Never throws. */
    private boolean tryOpenUsb() {
        PlaybackOutput out = null;
        openAttempted = false;
        try {
            if (!provider.isHeadsetReady()) return false;
            openAttempted = true;
            out = provider.openPlayback();
            if (out == null) return false;
            UacStreamConfig cfg = out.config();
            if (cfg == null || !out.start()) {
                out.close();
                return false;
            }
            configureBuffers(cfg);
            output = out;
            lastStatsMs = clockMs.getAsLong();
            openedAtMs = lastStatsMs;
            LimeLog.info("UsbAudio: game audio on USB headset (" + cfg.sampleRate + " Hz, "
                    + devChannels + " ch, " + (subframeBytes * 8) + " bit)");
            return true;
        } catch (RuntimeException e) {
            LimeLog.warning("UsbAudio: cannot open USB playback: " + e);
            if (out != null) {
                try {
                    out.close();
                } catch (RuntimeException ignored) {
                }
            }
            return false;
        }
    }

    private void configureBuffers(UacStreamConfig cfg) {
        devChannels = Math.max(1, cfg.alt.channels);
        subframeBytes = cfg.alt.subframeBytes;
        // 10 ms at the stream rate, or one decoded frame if that is longer
        chunkFrames = Math.max(Math.max(1, samplesPerFrame), INPUT_RATE / 100);
        int maxOut = LinearResampler.maxOutputFrames(chunkFrames, INPUT_RATE, cfg.sampleRate);
        inBuf = new short[chunkFrames * MAX_INPUT_CHANNELS];
        mixBuf = new short[chunkFrames * devChannels];
        resampleBuf = new short[maxOut * devChannels];
        packBuf = new byte[maxOut * devChannels * subframeBytes];
        resampler = new LinearResampler(INPUT_RATE, cfg.sampleRate, devChannels);
    }

    /** @return false if the output died and the caller must switch to the fallback. */
    private boolean playUsb(short[] audioData) {
        try {
            PlaybackOutput out = output;
            if (!out.isAlive()) return false;
            float gain = provider.currentGain();
            int frames = audioData.length / inChannels;
            for (int off = 0; off < frames; off += chunkFrames) {
                int n = Math.min(chunkFrames, frames - off);
                short[] src = audioData;
                if (off != 0) {
                    // Only when a decoded frame is longer than one chunk (not seen in practice)
                    System.arraycopy(audioData, off * inChannels, inBuf, 0, n * inChannels);
                    src = inBuf;
                }
                int mixed = PcmConverter.toDeviceChannels(src, n, inChannels, devChannels, mixBuf);
                int outFrames = resampler.process(mixBuf, mixed, resampleBuf);
                int len = PcmConverter.pack(resampleBuf, outFrames * devChannels, subframeBytes, gain, packBuf);
                if (len > 0 && out.write(packBuf, len) < 0) return false;
            }
        } catch (RuntimeException e) {
            LimeLog.warning("UsbAudio: USB playback failed: " + e);
            return false;
        }
        maybeLogStats(output);
        return true;
    }

    /** Logs the driver statistics every STATS_INTERVAL_MS while on USB. */
    private void maybeLogStats(PlaybackOutput out) {
        long now = clockMs.getAsLong();
        if (now - lastStatsMs < STATS_INTERVAL_MS) return;
        lastStatsMs = now;
        try {
            LimeLog.info("UsbAudio: stats " + out.stats());
        } catch (RuntimeException e) {
            LimeLog.warning("UsbAudio: reading USB stats failed: " + e);
        }
    }

    private void closeOutput() {
        PlaybackOutput out = output;
        if (out == null) return;
        output = null;
        try {
            out.close();
        } catch (RuntimeException e) {
            LimeLog.warning("UsbAudio: closing USB playback failed: " + e);
        }
    }

    // ---- Switching ----

    private void switchToFallback() {
        LimeLog.info("UsbAudio: USB headset stream lost, switching game audio to Android output");
        closeOutput();
        lastCheckMs = clockMs.getAsLong();
        onUsbLost(lastCheckMs);
        ensureFallback();
    }

    /** Sets up (and starts, if the stream is running) the fallback if it is not already. */
    private void ensureFallback() {
        if (fallbackSetUp || audioConfig == null) return;
        try {
            int r = fallback.setup(audioConfig, sampleRate, samplesPerFrame);
            if (r != 0) {
                LimeLog.warning("UsbAudio: fallback renderer setup failed (" + r + ")");
                return;
            }
            fallbackSetUp = true;
            if (started) fallback.start();
        } catch (RuntimeException e) {
            LimeLog.warning("UsbAudio: fallback renderer setup failed: " + e);
        }
    }

    /** @param pollNow skip the retry delay if the availability generation changed (pollUsb). */
    private void maybeSwitchToUsb(boolean pollNow) {
        long now = clockMs.getAsLong();
        boolean newGeneration = provider.availabilityGeneration() != generation;
        if (newGeneration) {
            // Attach, detach or a readiness flip: the next attempt may succeed, stop backing off
            generation = provider.availabilityGeneration();
            retryDelayMs = RECHECK_INTERVAL_MS;
        }
        if (!(pollNow && newGeneration) && now - lastCheckMs < retryDelayMs) return;
        lastCheckMs = now;
        if (openUsbWithBackoff()) {
            if (fallbackSetUp) {
                fallbackSetUp = false;
                try {
                    fallback.stop();
                    fallback.cleanup();
                } catch (RuntimeException e) {
                    LimeLog.warning("UsbAudio: fallback renderer cleanup failed: " + e);
                }
            }
        } else {
            // Retry a fallback whose earlier setup failed, at the same cadence
            ensureFallback();
        }
    }
}
