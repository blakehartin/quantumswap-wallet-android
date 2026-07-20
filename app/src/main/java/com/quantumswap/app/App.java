package com.quantumswap.app;

import android.app.Application;
import android.util.Log;

import timber.log.Timber;

public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        if (BuildConfig.DEBUG) {
            // RedactingDebugTree runs heuristic redaction on
            // the message string (0x-addresses, tx hashes, long
            // hex, long base64) before delegating to Timber's
            // DebugTree. This protects developer console output
            // and screenshots from accidental key leakage in
            // debug builds; the contract remains "do not log
            // secrets". See RedactingDebugTree for the regex
            // set and the parity note vs iOS.
            Timber.plant(new RedactingDebugTree());
        } else {
            Timber.plant(new ReleaseTree());
        }

        // (Android, mirrors iOS SnapshotRedactor +
        // ScreenCaptureGuard): install a process-wide branded
        // recents-thumbnail cover and a screen-capture observer so
        // sensitive surfaces (seed / address strip) react to a live
        // recording or cast session.
        try {
            com.quantumswap.app.ux.SnapshotRedactor.install(this);
            com.quantumswap.app.security.ScreenCaptureGuard.install(getApplicationContext());
        } catch (Throwable t) {
            Timber.tag("App").w(t, "snapshot/capture install");
        }

        // (Android, mirrors iOS AppDelegate cleanup pass):
        // sweep stale .wallet.tmp staged exports left behind by a
        // crash mid-rename. Runs off the main thread because the SAF
        // walk performs IPC into the documents provider. There is no
        // legacy-wrap-key-cleanup analogue on Android (we never
        // shipped a Keychain-equivalent wrap key per the iOS
        // KeychainWrapStore.deleteLegacyWrapKeyIfPresent rationale).
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    com.quantumswap.app.backup.CloudBackupManager
                            .cleanupStaleStagedExports(getApplicationContext());
                } catch (Throwable t) {
                    Timber.tag("App").w(t, "stale-tmp sweep");
                }
            }
        }, "qcw-launch-cleanup").start();
    }

    /**
     * Release tree: drops VERBOSE/DEBUG/INFO; forwards WARN/ERROR to the system log
     * without any message payload (so no sensitive data leaks to logcat in release).
     */
    private static final class ReleaseTree extends Timber.Tree {
        @Override
        protected boolean isLoggable(String tag, int priority) {
            return priority >= Log.WARN;
        }

        @Override
        protected void log(int priority, String tag, String message, Throwable t) {
            if (priority < Log.WARN) return;
            Log.println(priority, tag != null ? tag : "QCW", "evt");
        }
    }
}
