package com.limelight.phonekeyboard;

import java.util.ArrayList;
import java.util.List;

/** Test helper: records what it is asked to type. */
public class RecordingSink implements TypingSink {
    public final List<String> events = new ArrayList<>();
    /** Run once, after the first recorded event. May be null. */
    public Runnable afterFirstEvent;
    private boolean firstDone;

    @Override
    public void sendKey(short vk, boolean shift) {
        record("key:" + (int) vk + ":" + shift);
    }

    @Override
    public void sendCombo(byte modifiers, short vk) {
        record("combo:" + (int) modifiers + ":" + (int) vk);
    }

    @Override
    public void sendText(String text) {
        record("text:" + text);
    }

    @Override
    public void sendMouseMove(short dx, short dy) {
        record("move:" + dx + ":" + dy);
    }

    @Override
    public void sendMouseButton(byte button, boolean down) {
        record((down ? "down:" : "up:") + (int) button);
    }

    @Override
    public void sendMouseScroll(short amount) {
        record("scroll:" + amount);
    }

    private void record(String event) {
        events.add(event);
        if (!firstDone) {
            firstDone = true;
            if (afterFirstEvent != null) afterFirstEvent.run();
        }
    }
}
