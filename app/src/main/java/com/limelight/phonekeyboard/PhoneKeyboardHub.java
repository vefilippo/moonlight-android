package com.limelight.phonekeyboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Routes typing from the phone server to whichever stream is active. All typing runs on one
 * background thread, one task per call, so two sends never interleave.
 */
public final class PhoneKeyboardHub {
    private static PhoneKeyboardHub instance;

    private final Executor executor;
    private volatile TypingSink sink;

    public PhoneKeyboardHub(Executor executor) {
        this.executor = executor;
    }

    public static synchronized PhoneKeyboardHub get() {
        if (instance == null) {
            instance = new PhoneKeyboardHub(Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "PhoneKeyboardTyping");
                    t.setDaemon(true);
                    return t;
                }
            }));
        }
        return instance;
    }

    public void attach(TypingSink s) {
        sink = s;
    }

    /** Detaches s only if it is still the attached sink. */
    public synchronized void detach(TypingSink s) {
        if (sink == s) sink = null;
    }

    public boolean isStreamActive() {
        return sink != null;
    }

    /** Returns false when no stream is active; otherwise queues the actions as one task. */
    public boolean type(List<TypingAction> actions) {
        final TypingSink captured = sink;
        if (captured == null) return false;
        final List<TypingAction> copy = new ArrayList<>(actions);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                for (TypingAction a : copy) {
                    if (sink != captured) return; // stream ended or changed: drop the rest
                    if (a.kind() == TypingAction.Kind.KEY) {
                        captured.sendKey(a.vk(), a.shift());
                    } else {
                        captured.sendText(a.text());
                    }
                }
            }
        });
        return true;
    }

    public boolean press(SpecialKey key) {
        return type(Collections.singletonList(TypingAction.key(key.vk(), false)));
    }
}
