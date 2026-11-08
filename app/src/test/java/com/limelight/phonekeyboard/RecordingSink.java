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
    public void sendText(String text) {
        record("text:" + text);
    }

    private void record(String event) {
        events.add(event);
        if (!firstDone) {
            firstDone = true;
            if (afterFirstEvent != null) afterFirstEvent.run();
        }
    }
}
