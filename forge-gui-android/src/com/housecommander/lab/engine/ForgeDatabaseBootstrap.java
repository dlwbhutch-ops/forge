package com.housecommander.lab.engine;

import android.app.Activity;
import android.content.Intent;

/**
 * Initializes Forge's normal Android runtime/card-database path for
 * HOUSE Commander Lab.
 *
 * Forge and HOUSE are compiled into the SAME APK.
 *
 * The installed Android application package does not need to be
 * "forge.app". forge.app.Main is a Java/activity class contained inside
 * the current APK.
 */
public final class ForgeDatabaseBootstrap {

    public static final String EXTRA_HOUSE_BOOTSTRAP =
            "com.housecommander.lab.extra.FORGE_BOOTSTRAP";

    private ForgeDatabaseBootstrap() {
        // Utility class.
    }

    /**
     * Ensure Forge's real card database is ready.
     *
     * @return true when Forge is already fully ready.
     *         false when Forge startup was launched and HOUSE must wait.
     */
    public static boolean ensureReady(Activity activity) {
        if (activity == null) {
            throw new IllegalArgumentException(
                    "Activity must not be null"
            );
        }

        ForgeEngineAdapter engine =
                new ForgeEngineAdapter(activity);

        /*
         * Already initialized. Do not launch Forge again.
         */
        if (engine.isAvailable()) {
            return true;
        }

        /*
         * IMPORTANT:
         *
         * This explicitly launches the forge.app.Main CLASS inside the
         * CURRENT HOUSE APK.
         *
         * new Intent(activity, SomeClass.class) automatically uses the
         * current application's runtime package/component identity.
         *
         * There is NO lookup of a separately installed package called
         * "forge.app".
         */
        Intent intent =
                new Intent(
                        activity,
                        forge.app.Main.class
                );

        intent.putExtra(
                EXTRA_HOUSE_BOOTSTRAP,
                true
        );

        /*
         * Reuse the existing Forge activity if Android already created it.
         * SINGLE_TOP also allows Forge Main's onNewIntent() bootstrap logic
         * to receive the HOUSE request.
         */
        intent.addFlags(
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
        );

        activity.startActivity(intent);

        /*
         * HOUSE and Forge switch without an unnecessary animation.
         */
        activity.overridePendingTransition(0, 0);

        return false;
    }
}