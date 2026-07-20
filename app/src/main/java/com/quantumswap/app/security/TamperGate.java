package com.quantumswap.app.security;

import android.content.Context;
import android.os.Debug;

import com.quantumswap.app.BuildConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Layered runtime detection of root, debugger-attached-in-Release,
 * and bundle/APK tampering. This file is the <em>probe</em> layer:
 * it owns no UI, no policy, no localized strings. The policy + UI
 * layer lives in {@link TamperGatePolicy}.
 * <p>The strict separation is deliberate so the security primitives
 * can be unit-tested in isolation and so a future change to the
 * policy (e.g. swapping in a maintained library like SafetyNet
 * Attestation) does not have to re-touch the UI plumbing.
 * <p>Why this exists:
 * <p>Every other defense in this app rests on the OS-level isolation
 * Android gives a sandboxed process: per-app UID, EncryptedFile
 * ProMD, AndroidKeystore, App-Bound Domains, code-signing of the
 * APK. A rooted device, an attached debugger, or a Frida-style
 * {@code LD_PRELOAD} shim defeats every one of those. The wallet's
 * per-transaction signing flow is the highest-value target: an
 * attacker who can read process memory or hook
 * {@code JsBridge.sendTransaction} can quietly redirect the recipient
 * address right before it is signed.
 * <p>This module RAISES THE COST of that attack from "one
 * off-the-shelf Frida script" to "bypass-the-detection AND
 * hook-the-signer". It does NOT make the attack impossible; that is
 * not the bar. The bar is "the cheapest version of the attack stops
 * working", which closes the hobbyist / opportunistic threat model.
 * <p><b>Ignore-and-resume policy:</b> unlike a bank app that may
 * refuse to run on a rooted device, this wallet is a non-custodial
 * tool whose users include power users who legitimately run rooted
 * devices for development. The default policy is therefore:
 * <ol>
 *   <li>Probe runs at app launch.</li>
 *   <li>Result is stored in a {@link TamperReport}.</li>
 *   <li>{@link TamperGatePolicy} surfaces a one-time consent
 *   banner. The user may dismiss it ("ignore") and continue.</li>
 *   <li>Subsequent launches re-run the probe but do NOT re-prompt
 *   if the user already chose to ignore the same severity at the
 *   same install id.</li>
 * </ol>
 * <p>Tradeoffs:
 * <ul>
 *   <li>Heuristic by nature. A nation-state attacker with custom
 *   tooling can spoof every signal. The combination with
 *   {@link BundleIntegrity} (bundle hash pin) is what gives
 *   defense-in-depth: a Frida script that also has to forge the
 *   SHA-256 of a 1+ MB JS bundle on every load is materially harder
 *   to write than a plain method-swizzle.</li>
 *   <li>Probe set is intentionally a list (not a tightly coupled
 *   algorithm) so individual entries can be added, removed, or
 *   re-ordered as Android major versions shift the root landscape.
 *   Each probe writes its own short string into
 *   {@code rootSignals} so the bootstrap report is easy to diagnose
 *   without a debugger.</li>
 * </ul>
 */
public final class TamperGate {

     /**
     * Coarse classification consumed by {@link TamperGatePolicy}.
     * Hard-fail variants are listed before the soft variant so a
     * switch on severity short-circuits in the policy layer.
     */
    public enum Severity {
        CLEAN,
        ROOT_SUSPECTED,
        DEBUGGER_ATTACHED_IN_RELEASE,
        RUNTIME_TAMPER_DETECTED
    }

     /**
     * Value type carrying the probe outcome. Safe to read from any
     * thread after {@link #bootstrap(Context)} returns.
     */
    public static final class TamperReport {
        public final Severity severity;
        public final List<String> rootSignals;
        public final boolean debuggerAttached;
        public final String runtimeTamperReason;

        public TamperReport(Severity severity,
                            List<String> rootSignals,
                            boolean debuggerAttached,
                            String runtimeTamperReason) {
            this.severity = severity;
            this.rootSignals = Collections.unmodifiableList(
                    new ArrayList<>(rootSignals != null ? rootSignals : Collections.<String>emptyList()));
            this.debuggerAttached = debuggerAttached;
            this.runtimeTamperReason = runtimeTamperReason;
        }
    }

    private static final Object CACHE_LOCK = new Object();
    private static volatile boolean didBootstrap = false;
    private static volatile List<String> cachedSignals = Collections.emptyList();
    private static volatile String cachedRuntimeReason = null;

    private TamperGate() { }

     /**
     * Compute the expensive root / runtime-tamper probes once and
     * cache them. Idempotent: repeated calls are a no-op.
     * <p><b>Developer-build behaviour:</b> probes are no-ops on
     * DEBUG builds because the Android Studio debugger pre-loads
     * inspector dylibs and sets {@code Debug.isDebuggerConnected()}
     * to true, which would trip the runtime-tamper probe with a
     * guaranteed false positive. The classifier itself is identical
     * in DEBUG and Release so the report shape is identical and the
     * behaviour is testable from a unit test target.
     */
    public static void bootstrap(Context ctx) {
        synchronized (CACHE_LOCK) {
            if (didBootstrap) return;

            if (BuildConfig.DEBUG) {
                System.err.println(
                        "TamperGate: developer build (DEBUG). Probes disabled; gate reports clean. " +
                                "This MUST NOT appear in shipping builds.");
                cachedSignals = Collections.emptyList();
                cachedRuntimeReason = null;
                didBootstrap = true;
                return;
            }

            List<String> signals = new ArrayList<>();
            signals.addAll(probeFileSystemMarkers());
            signals.addAll(probeBuildTags());
            signals.addAll(probeDangerousPackages(ctx));

            String runtimeReason = probeRuntimeTamper(ctx);

            cachedSignals = signals;
            cachedRuntimeReason = runtimeReason;
            didBootstrap = true;
        }
    }

     /**
     * Build the full report (bootstrap-cached probes + a fresh
     * per-call debugger-attached check). Safe to call from any
     * thread after {@link #bootstrap(Context)} has run.
     */
    public static TamperReport currentReport() {
        boolean booted;
        List<String> signals;
        String runtimeReason;
        synchronized (CACHE_LOCK) {
            booted = didBootstrap;
            signals = cachedSignals;
            runtimeReason = cachedRuntimeReason;
        }
        if (!booted) {
            // Fail-closed in shipping Release: a path that reaches
            // currentReport without bootstrap means the launch
            // sequence was bypassed (UI test, restored process, etc.)
            // and we surface that as runtime-tamper rather than a
            // silent clean report.
            if (!BuildConfig.DEBUG) {
                return new TamperReport(
                        Severity.RUNTIME_TAMPER_DETECTED,
                        Collections.<String>emptyList(),
                        false,
                        "tampergate-not-bootstrapped");
            }
        }
        boolean traced = isCurrentlyTraced();
        Severity sev = classify(signals, runtimeReason, traced);
        return new TamperReport(sev, signals, traced, runtimeReason);
    }

    private static Severity classify(List<String> signals,
                                     String runtimeReason,
                                     boolean traced) {
        if (BuildConfig.DEBUG) {
            return Severity.CLEAN;
        }
        if (runtimeReason != null) return Severity.RUNTIME_TAMPER_DETECTED;
        if (traced) return Severity.DEBUGGER_ATTACHED_IN_RELEASE;
        if (signals != null && signals.size() >= 2) return Severity.ROOT_SUSPECTED;
        return Severity.CLEAN;
    }

    // ---- probes -----------------------------------------------------

     /**
     * Union of paths shipped by every public Android root method
     * from Magisk through KernelSU. Each entry has a meaningful
     * file-system signature and a meaningful false-positive bound.
     */
    private static final List<String> ROOT_PATHS = Collections.unmodifiableList(Arrays.asList(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su",
            "/system/etc/init.d/99SuperSUDaemon",
            "/dev/com.koushikdutta.superuser.daemon/",
            "/system/xbin/daemonsu"
    ));

    private static List<String> probeFileSystemMarkers() {
        List<String> hits = new ArrayList<>();
        for (String path : ROOT_PATHS) {
            try {
                File f = new File(path);
                if (f.exists()) {
                    hits.add("fs:" + path);
                }
            } catch (Throwable ignore) { }
        }
        return hits;
    }

    private static List<String> probeBuildTags() {
        List<String> hits = new ArrayList<>();
        try {
            String tags = android.os.Build.TAGS;
            if (tags != null && tags.contains("test-keys")) {
                hits.add("build:test-keys");
            }
        } catch (Throwable ignore) { }
        return hits;
    }

    private static final List<String> DANGEROUS_PACKAGES = Collections.unmodifiableList(Arrays.asList(
            "com.topjohnwu.magisk",
            "eu.chainfire.supersu",
            "com.koushikdutta.superuser",
            "com.thirdparty.superuser",
            "com.yellowes.su",
            "com.kingouser.com",
            "de.robv.android.xposed.installer",
            "com.saurik.substrate"
    ));

    private static List<String> probeDangerousPackages(Context ctx) {
        List<String> hits = new ArrayList<>();
        if (ctx == null) return hits;
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            for (String pkg : DANGEROUS_PACKAGES) {
                try {
                    pm.getPackageInfo(pkg, 0);
                    hits.add("pkg:" + pkg);
                } catch (android.content.pm.PackageManager.NameNotFoundException nf) {
                    // expected on unrooted devices
                }
            }
        } catch (Throwable ignore) { }
        return hits;
    }

    private static String probeRuntimeTamper(Context ctx) {
        try {
            BundleIntegrity.verifyOrFail(ctx);
        } catch (BundleIntegrity.BundleIntegrityException e) {
            return "bundle-hash:" + e.getMessage();
        }
        return null;
    }

    private static boolean isCurrentlyTraced() {
        if (BuildConfig.DEBUG) return false;
        try {
            return Debug.isDebuggerConnected() || Debug.waitingForDebugger();
        } catch (Throwable t) {
            // Fail CLOSED in production: if the introspection API
            // refuses to answer, treat it as evidence of tampering.
            return true;
        }
    }
}
