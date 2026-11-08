package com.limelight.phonekeyboard;

/** Time source; own interface because java.util.function.LongSupplier needs API 24 (minSdk is 21). */
public interface Clock {
    long nowMs();
}
