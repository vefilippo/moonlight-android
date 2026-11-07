package com.limelight.binding.audio.usb;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

public class LatestLevelExecutorTest {
    private static final class ManualExecutor implements Executor {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();

        @Override
        public void execute(Runnable r) {
            queue.add(r);
        }

        void runAll() {
            Runnable r;
            while ((r = queue.poll()) != null) r.run();
        }
    }

    @Test public void rapidChangesCoalesceToLatest() {
        ManualExecutor ex = new ManualExecutor();
        List<Integer> applied = new ArrayList<>();
        LatestLevelExecutor l = new LatestLevelExecutor(ex, applied::add);
        l.submit(3);
        l.submit(4);
        l.submit(5);
        assertEquals(1, ex.queue.size());
        ex.runAll();
        assertEquals(Arrays.asList(5), applied);
    }

    @Test public void submitAfterRunSchedulesAgain() {
        ManualExecutor ex = new ManualExecutor();
        List<Integer> applied = new ArrayList<>();
        LatestLevelExecutor l = new LatestLevelExecutor(ex, applied::add);
        l.submit(3);
        ex.runAll();
        l.submit(9);
        assertEquals(1, ex.queue.size());
        ex.runAll();
        assertEquals(Arrays.asList(3, 9), applied);
    }

    @Test public void changeDuringApplyIsAppliedNext() {
        ManualExecutor ex = new ManualExecutor();
        List<Integer> applied = new ArrayList<>();
        LatestLevelExecutor[] self = new LatestLevelExecutor[1];
        self[0] = new LatestLevelExecutor(ex, level -> {
            applied.add(level);
            if (level == 1) self[0].submit(2); // arrives while the first apply runs
        });
        self[0].submit(1);
        ex.runAll();
        assertEquals(Arrays.asList(1, 2), applied);
    }

    @Test public void rejectedExecutionIsSwallowed() {
        List<Integer> applied = new ArrayList<>();
        LatestLevelExecutor l = new LatestLevelExecutor(r -> {
            throw new RejectedExecutionException("shut down");
        }, applied::add);
        l.submit(4); // must not throw
        l.submit(5);
        assertEquals(0, applied.size());
    }
}
