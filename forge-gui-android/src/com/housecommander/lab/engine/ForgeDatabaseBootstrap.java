package com.housecommander.lab.engine;

import android.app.Activity;
import android.content.Intent;

/**
 * HOUSE Commander Lab helper that starts Forge's normal Android
 * initialization path when the real Forge card database is not ready.
 */
public final class ForgeDatabaseBootstrap {

    public static final String EXTRA_HOUSE_BOOTSTRAP =
            "com.housecommander.lab.extra.FORGE_BOOTSTRAP";

    private ForgeDatabaseBootstrap() {
    }

    /**
     * Returns true if the Forge bridge/database is already available.
     * Otherwise launches Forge's normal Main activity so Forge can
     * initialize its assets and card database.
     */
    public static boolean ensureReady(Activity activity) {
        ForgeEngineAdapter engine = new ForgeEngineAdapter(activity);

        if (engine.isAvailable()) {
            return true;
        }

        Intent intent = new Intent(activity, forge.app.Main.class);
        intent.putExtra(EXTRA_HOUSE_BOOTSTRAP, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);

        activity.startActivity(intent);
        activity.overridePendingTransition(0, 0);

        return false;
    }
}
