package com.limelight.phonekeyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.limelight.LimeLog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;

/**
 * Process-wide owner of the phone keyboard server. The server only listens while a phone is
 * paired or a pairing code is active.
 */
public final class PhoneKeyboardService {
    private static final String FALLBACK_HTML =
            "<!doctype html><title>Pheme</title><p>Keyboard page missing.</p>";

    private static PhoneKeyboardService instance;

    private final Context context;
    private final PairedPhoneStore store;
    private final PairingCodes codes;
    private final PhoneKeyboardApi api;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile Runnable onPairedListener;
    private PhoneKeyboardServer server;
    private volatile boolean lastStartFailed;

    public static synchronized PhoneKeyboardService get(Context context) {
        if (instance == null) {
            instance = new PhoneKeyboardService(context.getApplicationContext());
        }
        return instance;
    }

    PhoneKeyboardService(Context appContext) {
        this.context = appContext;
        Clock clock = System::currentTimeMillis;
        SharedPreferences prefs = appContext.getSharedPreferences("phone_keyboard", Context.MODE_PRIVATE);
        this.store = new PairedPhoneStore(prefs, clock);
        this.codes = new PairingCodes(clock);
        this.api = new PhoneKeyboardApi(store, codes, PhoneKeyboardHub.get(), loadPage(appContext),
                this::dispatchPaired);
    }

    private static String loadPage(Context c) {
        try (InputStream in = c.getAssets().open("phone_keyboard/index.html")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (IOException e) {
            return FALLBACK_HTML;
        }
    }

    private void dispatchPaired() {
        final Runnable l = onPairedListener;
        if (l != null) mainHandler.post(l);
    }

    /** Starts or stops the server so it runs iff a phone is paired or pairing is active. */
    public synchronized void refresh() {
        boolean needed = !store.isEmpty() || codes.hasActive();
        if (needed && server == null) {
            PhoneKeyboardServer s = new PhoneKeyboardServer(api);
            try {
                s.start(5000, true);
                server = s;
            } catch (IOException | RuntimeException e) {
                LimeLog.warning("Phone keyboard server failed to start: " + e.getClass().getSimpleName());
            }
        } else if (!needed && server != null) {
            server.stop();
            server = null;
        }
    }

    /**
     * Returns the pairing URL, or null when pairing can't start. Distinguish the two causes with
     * {@link #lastStartFailed()}: server did not start, or else no LAN IPv4 is available.
     */
    public String startPairing() {
        String code = codes.issue();
        refresh();
        if (!isRunning()) {
            lastStartFailed = true;
            codes.cancelAll();
            refresh();
            return null;
        }
        lastStartFailed = false;
        String ip = findLanIpv4(context);
        return ip == null ? null : "http://" + ip + ":" + PhoneKeyboardServer.PORT + "/pair/" + code;
    }

    /** True when the last {@link #startPairing()} returned null because the server did not start. */
    public boolean lastStartFailed() {
        return lastStartFailed;
    }

    PairingCodes codes() {
        return codes;
    }

    public void endPairing() {
        codes.cancelAll();
        refresh();
    }

    /** The listener is invoked on the main thread; may be null. */
    public void setOnPairedListener(Runnable listener) {
        this.onPairedListener = listener;
    }

    public PairedPhoneStore store() {
        return store;
    }

    public synchronized boolean isRunning() {
        return server != null && server.isAlive();
    }

    static String findLanIpv4(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            if (Build.VERSION.SDK_INT >= 23) {
                return firstIpv4(cm.getLinkProperties(cm.getActiveNetwork()));
            }
            for (Network n : cm.getAllNetworks()) {
                String ip = firstIpv4(cm.getLinkProperties(n));
                if (ip != null) return ip;
            }
        } catch (RuntimeException e) {
            // no usable network info
        }
        return null;
    }

    private static String firstIpv4(LinkProperties props) {
        if (props == null) return null;
        for (LinkAddress la : props.getLinkAddresses()) {
            InetAddress a = la.getAddress();
            if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
        }
        return null;
    }
}
