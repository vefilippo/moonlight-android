package com.limelight.phonekeyboard;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.limelight.R;

import java.util.EnumMap;
import java.util.Map;

/** TV dialog that shows a QR code a phone scans to pair with the phone keyboard. */
public final class PairPhoneDialog {
    private PairPhoneDialog() {}

    public static void show(final Activity activity) {
        final PhoneKeyboardService service = PhoneKeyboardService.get(activity);
        final String url = service.startPairing();

        final AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(R.string.phone_keyboard_title)
                .setPositiveButton(android.R.string.ok, null);

        final LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(activity, 24);
        content.setPadding(pad, dp(activity, 8), pad, 0);

        final ImageView qrView;
        final TextView status;
        if (url == null) {
            qrView = null;
            status = addText(activity, content, activity.getString(R.string.phone_keyboard_no_network), 18);
        } else {
            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            // About 60% of the dialog width, but never taller than about half the screen.
            int qrPx = Math.min((int) (dm.widthPixels * 0.6f * 0.8f), (int) (dm.heightPixels * 0.5f));
            qrView = new ImageView(activity);
            qrView.setImageBitmap(renderQr(url, qrPx));
            qrView.setContentDescription(url);
            content.addView(qrView, new LinearLayout.LayoutParams(qrPx, qrPx));
            status = addText(activity, content, activity.getString(R.string.phone_keyboard_scan), 18);
            addText(activity, content, url, 16);
            addText(activity, content, activity.getString(R.string.phone_keyboard_ip_tip), 14);
            addText(activity, content, activity.getString(R.string.phone_keyboard_apollo_tip), 14);
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        builder.setView(scroll);
        final AlertDialog dialog = builder.create();

        final Application app = activity.getApplication();
        final Application.ActivityLifecycleCallbacks callbacks = new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityDestroyed(Activity a) {
                if (a == activity) {
                    app.unregisterActivityLifecycleCallbacks(this);
                    if (dialog.isShowing()) dialog.dismiss();
                }
            }
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityResumed(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
        };

        // Every way out (button, back, dismiss, activity finishing) lands here.
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface d) {
                app.unregisterActivityLifecycleCallbacks(callbacks);
                service.setOnPairedListener(null);
                service.endPairing();
            }
        });

        if (url != null) {
            service.setOnPairedListener(new Runnable() {
                @Override public void run() {
                    if (qrView != null) qrView.setVisibility(View.GONE);
                    status.setText(R.string.phone_keyboard_paired);
                }
            });
        }

        app.registerActivityLifecycleCallbacks(callbacks);
        dialog.show();
    }

    private static TextView addText(Activity activity, LinearLayout parent, String text, int sp) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(activity, 12);
        parent.addView(tv, lp);
        return tv;
    }

    private static int dp(Activity activity, int dp) {
        return (int) (dp * activity.getResources().getDisplayMetrics().density);
    }

    /** Black-on-white QR code with a quiet zone, exactly sizePx by sizePx. */
    public static Bitmap renderQr(String text, int sizePx) {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 4);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        BitMatrix matrix;
        try {
            matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
        } catch (WriterException e) {
            throw new IllegalArgumentException("Cannot encode QR code", e);
        }
        int w = matrix.getWidth(), h = matrix.getHeight();
        int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                pixels[y * w + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
        }
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.setPixels(pixels, 0, w, 0, 0, w, h);
        return bmp;
    }
}
