package com.limelight.phonekeyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.Executor;

public class PhoneKeyboardHubTest {
    private static final class QueuedExecutor implements Executor {
        private final Queue<Runnable> q = new ArrayDeque<>();
        @Override public void execute(Runnable r) { q.add(r); }
        void runAll() { Runnable r; while ((r = q.poll()) != null) r.run(); }
    }

    private PhoneKeyboardHub hub;
    private RecordingSink sink;

    @Before
    public void setUp() {
        hub = new PhoneKeyboardHub(new Executor() {
            @Override public void execute(Runnable r) { r.run(); }
        });
        sink = new RecordingSink();
    }

    @Test public void typeFailsWithoutSink() {
        assertFalse(hub.type(TextSplitter.split("a")));
        assertFalse(hub.press(SpecialKey.ENTER));
        assertFalse(hub.isStreamActive());
    }

    @Test public void typeDeliversActionsInOrder() {
        hub.attach(sink);
        assertTrue(hub.type(TextSplitter.split("a!")));
        assertEquals(Arrays.asList("key:65:false", "text:!"), sink.events);
    }

    @Test public void pressSendsUnshiftedKey() {
        hub.attach(sink);
        assertTrue(hub.press(SpecialKey.ENTER));
        assertEquals(Arrays.asList("key:" + (int) SpecialKey.ENTER.vk() + ":false"), sink.events);
    }

    @Test public void comboIsQueuedAfterTyping() {
        QueuedExecutor queued = new QueuedExecutor();
        hub = new PhoneKeyboardHub(queued);
        hub.attach(sink);
        hub.type(TextSplitter.split("a"));
        assertTrue(hub.combo((byte) 0x08, (short) 9));
        assertTrue(sink.events.isEmpty());
        queued.runAll();
        assertEquals(Arrays.asList("key:65:false", "combo:8:9"), sink.events);
    }

    @Test public void mouseRunsImmediatelyInOrder() {
        QueuedExecutor queued = new QueuedExecutor();
        hub = new PhoneKeyboardHub(queued);
        assertFalse(hub.mouse(Arrays.asList(MouseAction.move((short) 1, (short) 2))));
        hub.attach(sink);
        assertTrue(hub.mouse(Arrays.asList(
                MouseAction.move((short) 1, (short) -2),
                MouseAction.button((byte) 1, true),
                MouseAction.button((byte) 1, false),
                MouseAction.scroll((short) -120))));
        assertEquals(Arrays.asList("move:1:-2", "down:1", "up:1", "scroll:-120"), sink.events);
    }

    @Test public void hub_doesNotInterleaveTwoSends() {
        QueuedExecutor queued = new QueuedExecutor();
        hub = new PhoneKeyboardHub(queued);
        hub.attach(sink);
        hub.type(TextSplitter.split("ab"));
        hub.type(TextSplitter.split("cd"));
        queued.runAll();
        assertEquals(Arrays.asList("key:65:false", "key:66:false", "key:67:false", "key:68:false"),
                sink.events);
    }

    @Test public void hub_dropsRemainingActionsWhenSinkDetachedMidSend() {
        final RecordingSink detaching = new RecordingSink();
        detaching.afterFirstEvent = new Runnable() {
            @Override public void run() { hub.detach(detaching); }
        };
        hub.attach(detaching);
        hub.type(TextSplitter.split("abc"));
        assertEquals(1, detaching.events.size());
    }

    @Test public void queuedSendIsDroppedIfSinkReplacedBeforeItRuns() {
        QueuedExecutor queued = new QueuedExecutor();
        hub = new PhoneKeyboardHub(queued);
        hub.attach(sink);
        hub.type(TextSplitter.split("ab"));
        RecordingSink other = new RecordingSink();
        hub.attach(other);
        queued.runAll();
        assertTrue(sink.events.isEmpty());
        assertTrue(other.events.isEmpty());
    }

    @Test public void detachIgnoresOtherSink() {
        hub.attach(sink);
        hub.detach(new RecordingSink());
        assertTrue(hub.isStreamActive());
        hub.detach(sink);
        assertFalse(hub.isStreamActive());
    }
}
