package com.limelight.phonekeyboard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.format.DateUtils;

import com.limelight.R;

import java.util.List;

/** Lists paired phones; selecting one offers to remove it. */
public final class PairedPhonesDialog {
    private PairedPhonesDialog() {}

    public static void show(final Activity activity) {
        final PhoneKeyboardService service = PhoneKeyboardService.get(activity);
        final List<PairedPhone> phones = service.store().list();

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(R.string.title_phone_keyboard_paired_phones)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.phone_keyboard_pair_new, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        PairPhoneDialog.show(activity);
                    }
                });

        if (phones.isEmpty()) {
            builder.setMessage(R.string.phone_keyboard_none);
        } else {
            String[] items = new String[phones.size()];
            for (int i = 0; i < items.length; i++) {
                PairedPhone p = phones.get(i);
                items[i] = p.lastUsedMs == 0
                        ? p.label + " · " + activity.getString(R.string.phone_keyboard_never_used)
                        : p.label + " · " + activity.getString(R.string.phone_keyboard_last_used,
                                DateUtils.getRelativeTimeSpanString(p.lastUsedMs));
            }
            builder.setItems(items, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) {
                    confirmRemove(activity, service, phones.get(which));
                }
            });
        }
        builder.show();
    }

    private static void confirmRemove(final Activity activity, final PhoneKeyboardService service,
                                      final PairedPhone phone) {
        new AlertDialog.Builder(activity)
                .setMessage(activity.getString(R.string.phone_keyboard_remove_confirm, phone.label))
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        service.store().remove(phone.id);
                        service.refresh();
                        show(activity);
                    }
                })
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        show(activity);
                    }
                })
                .show();
    }
}
