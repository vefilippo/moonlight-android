package com.limelight.binding.audio.usb;

import com.limelight.LimeLog;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hands volume levels to a target on an executor, coalescing rapid changes: at most one task is
 * queued and it applies the latest level (latest wins). submit() never blocks.
 */
final class LatestLevelExecutor {
    interface Target {
        void apply(int level);
    }

    private final Executor executor;
    private final Target target;
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private volatile int latest;

    private final Runnable drain = new Runnable() {
        @Override
        public void run() {
            // Clear before reading: a submit after this point schedules a new task.
            scheduled.set(false);
            target.apply(latest);
        }
    };

    LatestLevelExecutor(Executor executor, Target target) {
        this.executor = executor;
        this.target = target;
    }

    void submit(int level) {
        latest = level;
        if (scheduled.getAndSet(true)) return;
        try {
            executor.execute(drain);
        } catch (RejectedExecutionException e) {
            // Shut down (headset closing): nothing left to drive.
            LimeLog.info("UsbAudio: volume change after close ignored");
        }
    }
}
