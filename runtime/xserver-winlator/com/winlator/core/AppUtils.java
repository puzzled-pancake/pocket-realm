/*
 * com.winlator.core.AppUtils — TRIMMED for the O06 S-3 spike.
 *
 * Source: brunodev85/winlator-app ca3d735 (LGPL-2.1). The full upstream file
 * pulls in AppCompatActivity/PreferenceManager/TabLayout/R/SettingsFragment
 * (Winlator app-shell deps Pocket Realm does not have). Only `runDelayed` is
 * referenced by the vendored X-server (Keyboard.java). The rest of the upstream
 * methods are dropped for the spike. See docs/patches/wine-provider-provenance.md.
 */
package com.winlator.core;

import java.util.Timer;
import java.util.TimerTask;

public abstract class AppUtils {
    public static void runDelayed(final Runnable callback, long delay) {
        if (callback == null) return;
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                callback.run();
            }
        }, delay);
    }
}
