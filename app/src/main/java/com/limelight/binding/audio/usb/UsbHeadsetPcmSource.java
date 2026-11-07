package com.limelight.binding.audio.usb;

import com.limelight.binding.audio.MicrophoneCaptureManager;

/**
 * Microphone source backed by the Artemis-driven USB headset: reads raw capture bytes with a
 * 100 ms timeout, downmixes to mono and resamples to 48 kHz for the host mic pipeline.
 * read() is called from one capture thread; close() may be called from any thread.
 */
public final class UsbHeadsetPcmSource implements MicrophoneCaptureManager.PcmSource {
    static final int READ_TIMEOUT_MS = 100;
    private static final int OUTPUT_RATE = 48000;
    private static final int MAX_READ_DIVISOR = 10; // cap one read at 100 ms of input

    /** One acquired capture stream. close() releases it and is called exactly once. */
    public interface CaptureInput {
        UacStreamConfig config();
        boolean start();
        /** Blocks up to timeoutMs. Bytes read, 0 on timeout, negative if gone/closed. */
        int read(byte[] buf, int max, int timeoutMs);
        void close();
    }

    public interface Provider {
        boolean isHeadsetReady();
        /** Nullable. Acquires the headset; the returned input must be closed. */
        CaptureInput openCapture();
        /** Nullable. */
        String headsetLabel();
    }

    private final CaptureInput in;
    private final String label;
    private final int channels;
    private final int subframeBytes;
    private final int frameBytes;
    private final int inputRate;
    private final LinearResampler resampler;
    private final Object closeLock = new Object();
    private volatile boolean closed;

    private byte[] raw = new byte[0];
    private short[] mono = new short[0];

    public UsbHeadsetPcmSource(CaptureInput in) {
        this(in, "USB headset");
    }

    public UsbHeadsetPcmSource(CaptureInput in, String label) {
        this.in = in;
        this.label = label != null ? label : "USB headset";
        UacStreamConfig cfg = in.config();
        channels = Math.max(1, cfg.alt.channels);
        subframeBytes = Math.max(2, cfg.alt.subframeBytes);
        frameBytes = channels * subframeBytes;
        inputRate = cfg.sampleRate > 0 ? cfg.sampleRate : OUTPUT_RATE;
        resampler = new LinearResampler(inputRate, OUTPUT_RATE, 1);
    }

    @Override
    public int read(short[] buffer) {
        if (closed) return -1;
        // Largest input chunk whose resampled output is guaranteed to fit in buffer.
        long fit = ((long) buffer.length - 2) * inputRate / OUTPUT_RATE;
        int maxFrames = (int) Math.min(fit, Math.max(1, inputRate / MAX_READ_DIVISOR));
        if (maxFrames < 1) return 0;
        int maxBytes = maxFrames * frameBytes;
        if (raw.length < maxBytes) raw = new byte[maxBytes];
        if (mono.length < maxFrames) mono = new short[maxFrames];

        int n = in.read(raw, maxBytes, READ_TIMEOUT_MS);
        if (closed) return -1;
        if (n < 0) return n;
        if (n == 0) return 0;
        int frames = PcmConverter.unpackToMono(raw, Math.min(n, maxBytes), channels, subframeBytes, mono);
        if (frames <= 0) return 0;
        return resampler.process(mono, frames, buffer);
    }

    @Override
    public void close() {
        synchronized (closeLock) {
            if (closed) return;
            closed = true;
        }
        in.close();
    }

    @Override
    public String label() {
        return label;
    }
}
