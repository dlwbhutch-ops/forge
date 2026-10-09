package com.housecommander.lab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.widget.*;
import com.housecommander.core.CardDataUpdates;
import java.io.InputStream;

/** Optional network check; game controls and resources remain usable offline. */
public final class CardUpdatesUi {
    private CardUpdatesUi() { }
    public static void add(Activity activity, LinearLayout parent) {
        Button button = new Button(activity);
        button.setText("Card rules & set updates");
        parent.addView(button);
        button.setOnClickListener(v -> show(activity));
    }
    private static void show(Activity activity) {
        TextView text = new TextView(activity);
        int padding = (int)(20 * activity.getResources().getDisplayMetrics().density);
        text.setPadding(padding, padding, padding, padding);
        text.setTextSize(16);
        text.setText("Bundled data checked October 8, 2026. Check for newer published card rules, token effects, and sets. Applying future engine changes requires a compatible HOUSE app update.");
        ScrollView scroll = new ScrollView(activity); scroll.addView(text);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Card rules & set updates")
                .setView(scroll).setPositiveButton("Check now", null).setNegativeButton("Close", null)
                .setNeutralButton("Forge releases", (d, which) -> {
                    try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(CardDataUpdates.RELEASES))); }
                    catch (Exception error) { Toast.makeText(activity, "No browser is available to open release notes.", Toast.LENGTH_LONG).show(); }
                }).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            text.setText("Checking published Forge card rules, token effects, and set definitions…");
            Thread worker = new Thread(() -> {
                String result;
                try (InputStream input = activity.getAssets().open("house-card-data.json")) {
                    result = CardDataUpdates.check(CardDataUpdates.read(input));
                } catch (Exception error) { result = "Could not check for updates. Your installed card data is unchanged. Please try again."; }
                final String message = result;
                activity.runOnUiThread(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed() && dialog.isShowing()) {
                        text.setText(message);
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    }
                });
            }, "HOUSE-Card-Update-Check");
            worker.setDaemon(true); worker.start();
        }));
        dialog.show();
    }
}
