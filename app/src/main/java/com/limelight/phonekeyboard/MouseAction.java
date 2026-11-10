package com.limelight.phonekeyboard;

import java.util.Objects;

/** One step of mouse input from the phone touchpad: a relative move, a button change or a scroll. */
public final class MouseAction {
    public enum Kind { MOVE, BUTTON, SCROLL }

    private final Kind kind;
    private final short dx;
    private final short dy;
    private final byte button;
    private final boolean down;
    private final short scroll;

    private MouseAction(Kind kind, short dx, short dy, byte button, boolean down, short scroll) {
        this.kind = kind;
        this.dx = dx;
        this.dy = dy;
        this.button = button;
        this.down = down;
        this.scroll = scroll;
    }

    public static MouseAction move(short dx, short dy) {
        return new MouseAction(Kind.MOVE, dx, dy, (byte) 0, false, (short) 0);
    }

    public static MouseAction button(byte button, boolean down) {
        return new MouseAction(Kind.BUTTON, (short) 0, (short) 0, button, down, (short) 0);
    }

    /** High-resolution vertical scroll; 120 is one wheel notch, positive scrolls up. */
    public static MouseAction scroll(short amount) {
        return new MouseAction(Kind.SCROLL, (short) 0, (short) 0, (byte) 0, false, amount);
    }

    public Kind kind() { return kind; }
    public short dx() { return dx; }
    public short dy() { return dy; }
    public byte button() { return button; }
    public boolean down() { return down; }
    public short scroll() { return scroll; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MouseAction)) return false;
        MouseAction a = (MouseAction) o;
        return kind == a.kind && dx == a.dx && dy == a.dy && button == a.button
                && down == a.down && scroll == a.scroll;
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, dx, dy, button, down, scroll);
    }

    @Override
    public String toString() {
        return "MouseAction{" + kind + " dx=" + dx + " dy=" + dy + " button=" + button
                + " down=" + down + " scroll=" + scroll + "}";
    }
}
