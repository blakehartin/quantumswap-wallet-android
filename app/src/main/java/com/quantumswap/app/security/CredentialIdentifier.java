package com.quantumswap.app.security;

import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import timber.log.Timber;

/**
 * Single source of truth for every Android Autofill "username" value
 * this wallet emits next to a password field. Mirrors iOS
 * {@code QuantumSwapWallet/Components/CredentialIdentifier.swift}.
 *
 * <h3>Three isolation guarantees</h3>
 * <ol>
 *   <li><b>CONTEXT ISOLATION</b> (strongbox vs. backup). Strongbox
 *       credentials live under {@code QuantumSwap-<deviceSuffix>};
 *       backup-file credentials live under
 *       {@code QuantumSwap-backup-<address>-<deviceSuffix>}. Distinct
 *       prefixes mean a Save in one context can never overwrite the
 *       other context's slot.</li>
 *   <li><b>PER-WALLET BACKUP ISOLATION</b>. Every backup-file save
 *       includes the wallet {@code <address>} in the username, so
 *       saving the backup password for wallet 0xABC cannot overwrite
 *       the saved backup password for wallet 0xDEF.</li>
 *   <li><b>CROSS-DEVICE ISOLATION</b>. Every username ends in
 *       {@code -<deviceSuffix>}, a UUID stored in the per-app
 *       no-backup files dir. {@code getNoBackupFilesDir()} is excluded
 *       from Android Auto Backup, mirroring iOS's
 *       {@code kSecAttrAccessibleWhenUnlockedThisDeviceOnly} so a
 *       restored second device gets a FRESH UUID and never silently
 *       overwrites the first device's saved password slot.</li>
 * </ol>
 *
 * <h3>iOS parity contract</h3>
 * The iOS counterpart explicitly states: <i>"the Kotlin
 * CredentialIdentifier object in the quantum-coin-wallet-android repo
 * MUST produce byte-identical strings so a user signed into the same
 * Apple ID and Google account sees matching account names in iCloud
 * Keychain and Google Password Manager respectively"</i>. The literal
 * prefixes ({@code "QuantumSwap-"}, {@code "QuantumSwap-backup-"})
 * are validated by
 * {@code CredentialIdentifierUsernameParityTest}.
 *
 * <h3>Autofill framework behavior notes (confirmed on-device)</h3>
 * <ul>
 *   <li><b>The synthetic username field MUST be visible to autofill.</b>
 *       {@link android.view.View#isVisibleToUser()} returns false the
 *       instant {@code getAlpha() <= 0} (regardless of size), which puts
 *       the field in {@code AutofillManager.mInvisibleTrackedIds}.
 *       Google Password Manager then refuses to surface an invisible
 *       field's value as the saved username (anti-honeypot) and its save
 *       sheet shows a blank "Username required" box. Hence
 *       {@link #attachUsernameField} uses a NON-zero (~1%) alpha on a
 *       1dp-tall field: imperceptible, yet classified visible.</li>
 *   <li><b>Finishing the context is host-specific.</b> A fragment-hosted
 *       password screen (never-{@code finish()}ed Activity) calls
 *       {@code AutofillManager.commit()} to trigger the save sheet. A
 *       dialog must NOT call {@code commit()}: dismissing it makes the
 *       savable views invisible and finishes the context via
 *       {@code saveOnAllViewsInvisible}; {@code commit()}-then-dismiss
 *       cancels the pending save UI and no sheet appears.</li>
 *   <li><b>"Suggest strong password" ignores this username.</b> Google's
 *       password-<i>generation</i> sheet ("Save password to Google?")
 *       requires a username and ignores the app-supplied value by
 *       design. Only the normal (manually typed) save sheet pre-fills
 *       the synthetic username below.</li>
 * </ul>
 */
public final class CredentialIdentifier {

    /**
     * Distinct contexts that influence which Autofill entry the user's
     * password manager will suggest.
     */
    public enum Context {
        /** The single strongbox unlock password (drawn at app launch
         *  and at every send / settings change). */
        STRONGBOX_UNLOCK,
        /** Per-wallet backup decrypt password, scoped to a specific
         *  wallet address. The address is used as a discriminator so
         *  the autofill provider keeps separate entries per wallet. */
        BACKUP_DECRYPT,
        /** Per-wallet backup encrypt password (different from the
         *  decrypt context only insofar as the manager may want to
         *  generate a new password instead of suggesting an existing
         *  one). */
        BACKUP_ENCRYPT
    }

    /** Filename for the persisted per-device UUID. v1 reserves room
     *  for a future rotation by bumping the suffix. */
    static final String DEVICE_SUFFIX_FILENAME = "credential-identifier-suffix-v1.uuid";

    /** Tag the invisible username EditText carries so a re-render of
     *  the parent ViewGroup doesn't pile up duplicate fields. The int
     *  value is arbitrary as long as it is unique inside this app —
     *  using the file's hashCode keeps it stable across builds. */
    private static final int USERNAME_FIELD_TAG = "CredentialIdentifier#username".hashCode();

    /** Memoized device suffix to avoid disk I/O per call. */
    private static volatile String cachedDeviceSuffix;

    /**
     * W3C-standard "new-password" hint, mirroring iOS
     * {@code .newPassword}. {@link android.view.View} only exposes
     * {@link android.view.View#AUTOFILL_HINT_PASSWORD} as a constant
     * across the API levels this app targets, so we use the literal
     * {@code "newPassword"} value that Google Password Manager,
     * Samsung Pass, and 1Password all listen for as the trigger for
     * the system "Save Password?" sheet. The literal matches
     * {@code androidx.autofill.HintConstants.AUTOFILL_HINT_NEW_PASSWORD}
     * but does not require pulling in the androidx.autofill artifact.
     */
    public static final String AUTOFILL_HINT_NEW_PASSWORD = "newPassword";

    private CredentialIdentifier() { }

    // -----------------------------------------------------------------
    // Username generation (iOS-parity literals)
    // -----------------------------------------------------------------

    /**
     * Stable per-install UUID. Stored as UTF-8 in
     * {@code context.getNoBackupFilesDir()} which Android Auto Backup
     * skips, mirroring iOS's {@code kSecAttrAccessibleWhenUnlockedThisDeviceOnly}
     * semantics (cross-device isolation: a restored second device
     * generates a fresh UUID rather than inheriting the first
     * device's value). On uninstall the file is purged with the rest
     * of the app's private storage, matching iOS's reset-on-uninstall
     * behaviour.
     */
    @NonNull
    public static String deviceSuffix(@NonNull android.content.Context ctx) {
        String s = cachedDeviceSuffix;
        if (s != null) return s;
        synchronized (CredentialIdentifier.class) {
            s = cachedDeviceSuffix;
            if (s != null) return s;
            s = readOrCreateDeviceSuffix(ctx.getApplicationContext());
            cachedDeviceSuffix = s;
            return s;
        }
    }

    @NonNull
    private static String readOrCreateDeviceSuffix(@NonNull android.content.Context appCtx) {
        File dir = appCtx.getNoBackupFilesDir();
        if (dir == null) {
            // Pre-API 21 / odd OEM fallback: the no-backup dir is
            // guaranteed to exist on every device the app supports
            // (minSdk is well above 21), so this branch is defensive
            // only. Fall back to a random UUID without persistence; a
            // re-install on the same device would re-roll, which is
            // strictly safer than failing.
            String fresh = UUID.randomUUID().toString();
            Timber.w("getNoBackupFilesDir() returned null; using ephemeral deviceSuffix");
            return fresh;
        }
        if (!dir.exists() && !dir.mkdirs()) {
            Timber.w("Could not create noBackupFilesDir at %s", dir.getAbsolutePath());
        }
        File f = new File(dir, DEVICE_SUFFIX_FILENAME);
        if (f.isFile() && f.length() > 0) {
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                byte[] buf = new byte[(int) Math.min(raf.length(), 64L)];
                raf.readFully(buf);
                String s = new String(buf, StandardCharsets.UTF_8).trim();
                if (looksLikeUuid(s)) return s;
                // Corrupted or partially-written file: fall through
                // and overwrite with a fresh UUID.
                Timber.w("deviceSuffix file at %s did not look like a UUID; rewriting",
                        f.getAbsolutePath());
            } catch (IOException ioe) {
                Timber.w(ioe, "deviceSuffix read failed; rewriting");
            }
        }
        String fresh = UUID.randomUUID().toString();
        try {
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            try (RandomAccessFile raf = new RandomAccessFile(tmp, "rw")) {
                byte[] data = fresh.getBytes(StandardCharsets.UTF_8);
                raf.setLength(0);
                raf.write(data);
                raf.getFD().sync();
            }
            if (!tmp.renameTo(f)) {
                // renameTo can fail on some filesystems if the
                // destination exists; delete + retry once.
                if (f.exists() && !f.delete()) {
                    Timber.w("Could not delete stale deviceSuffix at %s", f.getAbsolutePath());
                }
                if (!tmp.renameTo(f)) {
                    Timber.w("Could not rename %s -> %s; using fresh UUID without persistence",
                            tmp.getAbsolutePath(), f.getAbsolutePath());
                }
            }
        } catch (IOException ioe) {
            Timber.w(ioe, "deviceSuffix write failed; returning ephemeral UUID");
        }
        return fresh;
    }

    private static boolean looksLikeUuid(@Nullable String s) {
        if (s == null || s.length() != 36) return false;
        try {
            UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException iae) {
            return false;
        }
    }

    /**
     * Username for the strongbox unlock password. iOS literal:
     * {@code "QuantumSwap-" + deviceSuffix}.
     */
    @NonNull
    public static String strongboxUsername(@NonNull android.content.Context ctx) {
        return "QuantumSwap-" + deviceSuffix(ctx);
    }

    /**
     * Per-wallet backup-file password username. iOS literal:
     * {@code "QuantumSwap-backup-" + address + "-" + deviceSuffix}.
     * The {@code address} is included verbatim (no truncation) so the
     * Android and iOS strings line up byte-for-byte.
     */
    @NonNull
    public static String backupUsername(@NonNull android.content.Context ctx,
                                        @NonNull String address) {
        return "QuantumSwap-backup-" + address + "-" + deviceSuffix(ctx);
    }

    /**
     * Username for the batched-restore flow where the typed password
     * may decrypt one of several wallets and we cannot bind to a
     * specific address until the decrypt succeeds. iOS literal:
     * {@code "QuantumSwap-backup-" + deviceSuffix} (no address
     * segment, distinct from {@link #backupUsername} so a batch-mode
     * autofill never collides with a per-wallet slot).
     */
    @NonNull
    public static String backupBatchUsername(@NonNull android.content.Context ctx) {
        return "QuantumSwap-backup-" + deviceSuffix(ctx);
    }

    /**
     * Canonical address form used when scoping a per-wallet backup
     * credential. The same wallet's address can reach the backup
     * flows in slightly different string forms (checksummed vs
     * lowercase, with or without the {@code 0x} prefix) depending on
     * whether it comes from the in-memory wallet map (export) or the
     * backup file's plaintext {@code address} field (restore). Unless
     * both the SAVE site (export / post-create) and the SUGGEST site
     * (restore) feed {@link #backupUsername} an identical string, the
     * password manager scopes them to different slots and the saved
     * password is never suggested on restore. Normalizing to lowercase
     * + a single {@code 0x} prefix at the call sites guarantees a
     * byte-identical username on this device.
     *
     * <p>This is applied at the call sites (see
     * {@code BackupPasswordDialog}) and deliberately NOT inside
     * {@link #backupUsername}: the iOS-parity literals validated by
     * {@code CredentialIdentifierUsernameParityTest} pass the address
     * verbatim. Per-device isolation (the {@code -<deviceSuffix>}
     * suffix) already keeps backup slots disjoint across devices, so
     * normalizing on Android only affects this device's own
     * export<->restore matching. If iOS later needs to share a backup
     * slot it must adopt the same canonical form.
     *
     * @return the canonical address, or {@code null}/empty unchanged
     *         (callers treat null/empty as "no address" -> batch slot)
     */
    @Nullable
    public static String canonicalBackupAddress(@Nullable String address) {
        if (address == null) return null;
        String a = address.trim();
        if (a.isEmpty()) return a;
        a = a.toLowerCase(java.util.Locale.US);
        if (!a.startsWith("0x")) {
            a = "0x" + a;
        }
        return a;
    }

    // -----------------------------------------------------------------
    // EditText autofill wiring
    // -----------------------------------------------------------------

    /**
     * Backwards-compatible 3-arg overload: existing call sites
     * (unlock, decrypt, etc.) continue to work without source
     * churn. Defaults {@code forCreation} to false (i.e. fill, not
     * generate) which mirrors iOS {@code .password} /
     * {@code .existingPassword}.
     */
    public static void apply(@NonNull EditText field,
                             @NonNull Context context,
                             @Nullable String discriminator) {
        apply(field, context, discriminator, /*forCreation=*/false);
    }

    /**
     * Configures an EditText so the user's autofill provider can
     * deliver the right credential.
     *
     * @param field         the password EditText
     * @param context       one of the {@link Context} values
     * @param discriminator extra context (e.g. wallet address) used to
     *                      keep accessibility hints separated when the
     *                      same surface is invoked for different
     *                      wallets; may be {@code null} or empty for
     *                      non-scoped contexts (e.g.
     *                      {@code STRONGBOX_UNLOCK})
     * @param forCreation   {@code true} when the user is choosing a
     *                      brand-new password (mirrors iOS
     *                      {@code .newPassword}: emits
     *                      {@code AUTOFILL_HINT_NEW_PASSWORD} so the
     *                      system "Save Password?" sheet is offered);
     *                      {@code false} when the user is filling an
     *                      existing password (mirrors iOS
     *                      {@code .password})
     */
    public static void apply(@NonNull EditText field,
                             @NonNull Context context,
                             @Nullable String discriminator,
                             boolean forCreation) {
        // Autofill hint: NEW_PASSWORD asks the system to offer "Save
        // Password?" to the user's password manager. PASSWORD is the
        // fill-only hint used when the user is entering an existing
        // credential.
        String hint = forCreation
                ? AUTOFILL_HINT_NEW_PASSWORD
                : View.AUTOFILL_HINT_PASSWORD;
        field.setAutofillHints(hint);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);

        // Content description: visible to accessibility services AND
        // surfaced via AssistStructure to autofill providers. We
        // include the context so a provider can present a
        // human-readable hint of which password it's about to fill
        // (e.g. "Backup password for 0x1234..."). For STRONGBOX_UNLOCK
        // we omit the discriminator entirely.
        StringBuilder cd = new StringBuilder();
        switch (context) {
            case STRONGBOX_UNLOCK:
                cd.append("QuantumSwap wallet unlock password");
                break;
            case BACKUP_DECRYPT:
                cd.append("QuantumSwap backup decrypt password");
                if (!TextUtils.isEmpty(discriminator)) {
                    cd.append(" for ").append(shortenAddress(discriminator));
                }
                break;
            case BACKUP_ENCRYPT:
                cd.append("QuantumSwap backup encrypt password");
                if (!TextUtils.isEmpty(discriminator)) {
                    cd.append(" for ").append(shortenAddress(discriminator));
                }
                break;
        }
        field.setContentDescription(cd.toString());
    }

    /**
     * Inserts an invisible username {@link EditText} as the first
     * child of {@code parent}, carrying {@code usernameValue} so
     * Android Autofill / Google Password Manager scopes the paired
     * password field to a specific account slot. Mirrors iOS
     * {@code UsernameField.make(_:)} which does the equivalent for
     * iOS QuickType save / autofill.
     *
     * <p>The field is imperceptible (near-zero alpha, focusable false,
     * 1dp tall) yet still counts as "visible to user" for autofill so
     * providers pre-fill its username value in their save sheet, and is
     * tagged with a sentinel so a re-render of the parent (e.g.
     * fragment {@code onCreateView}) re-uses the existing field
     * rather than piling up duplicates.</p>
     *
     * @param parent        any {@link ViewGroup} that also contains
     *                      the password {@link EditText}
     * @param usernameValue from {@link #strongboxUsername},
     *                      {@link #backupUsername}, or
     *                      {@link #backupBatchUsername}
     */
    public static void attachUsernameField(@NonNull ViewGroup parent,
                                           @NonNull String usernameValue) {
        // Defensive: a TextInputLayout intercepts addView(EditText) and
        // reroutes the new view INTO its internal inputFrame, also
        // overwriting inputFrame's LayoutParams with the bare ones we
        // pass in. That clobbers the LinearLayout.LayoutParams the
        // TextInputLayout (which extends LinearLayout) requires for its
        // own children and crashes the next measure pass with a
        // ClassCastException, AND replaces the real password EditText
        // with our 1px invisible username field. Walk past any
        // TextInputLayout the caller hands us to the first non-TIL
        // ancestor before adding.
        ViewGroup safeParent = parent;
        try {
            Class<?> til = Class.forName(
                    "com.google.android.material.textfield.TextInputLayout");
            while (safeParent != null && til.isInstance(safeParent)) {
                android.view.ViewParent vp = safeParent.getParent();
                safeParent = (vp instanceof ViewGroup) ? (ViewGroup) vp : null;
            }
        } catch (ClassNotFoundException ignore) {
            // Material lib not on classpath: nothing to skip.
        }
        if (safeParent == null) {
            Timber.w("attachUsernameField: no non-TextInputLayout ancestor; skipping");
            return;
        }
        if (safeParent != parent) {
            Timber.d("attachUsernameField: caller passed a TextInputLayout; "
                    + "promoted to its first non-TextInputLayout ancestor");
        }
        parent = safeParent;

        // Dedup: if this parent already has a username field
        // attached (re-entry from onResume / onCreateView), update
        // its value in place instead of adding a second one.
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            Object tag = child.getTag(USERNAME_FIELD_TAG);
            if (tag != null && tag instanceof Boolean && (Boolean) tag) {
                if (child instanceof EditText) {
                    ((EditText) child).setText(usernameValue);
                }
                return;
            }
        }

        android.content.Context ctx = parent.getContext();
        EditText username = new EditText(ctx);
        username.setText(usernameValue);
        username.setAutofillHints(View.AUTOFILL_HINT_USERNAME);
        username.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
        // 1dp tall, near-zero (but NON-zero) alpha, non-focusable,
        // non-clickable: present in the AssistStructure AND classified
        // "visible to user" by the framework so autofill providers can
        // both scope to {username, password} AND pre-fill the username
        // in their "Save password?" sheet, while staying imperceptible.
        //
        // CRITICAL: alpha MUST be > 0. View.isVisibleToUser() returns
        // false the instant getAlpha() <= 0 (regardless of size), which
        // lands the field in AutofillManager's mInvisibleTrackedIds.
        // Google Password Manager then refuses to surface an INVISIBLE
        // field's value as the saved username (anti-honeypot behaviour),
        // so its save sheet shows a blank username and asks the user to
        // type one. A 1% alpha on a 1dp-tall field is invisible to the
        // eye but flips the framework's visibility verdict to true.
        int onePx = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 1f,
                ctx.getResources().getDisplayMetrics());
        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, onePx);
        username.setLayoutParams(lp);
        username.setAlpha(0.01f);
        username.setFocusable(false);
        username.setFocusableInTouchMode(false);
        username.setClickable(false);
        username.setLongClickable(false);
        username.setCursorVisible(false);
        // Intentionally left ENABLED. Several autofill providers
        // (incl. Google Password Manager's "Suggest strong password"
        // generation heuristic) skip disabled views when deciding
        // whether a screen is a credential-creation/login form. A
        // disabled username would drop the field from that heuristic
        // and suppress the generate/save offer. The field stays inert
        // for the user via alpha 0 / non-focusable / non-clickable /
        // 1dp height above, so keeping it enabled has no visible or
        // behavioural effect while preserving it in the AssistStructure
        // as a fillable username paired with the password field.
        username.setBackground(null);
        try {
            // Strip the field from accessibility focus order so
            // TalkBack does not announce the synthetic username to
            // the user.
            username.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            username.setContentDescription(null);
        } catch (Throwable ignore) { }
        username.setTag(USERNAME_FIELD_TAG, Boolean.TRUE);

        try {
            parent.addView(username, 0);
        } catch (Throwable t) {
            // Some ViewGroup subclasses (e.g. TextInputLayout) are
            // restricted in their child types and will throw on a
            // bare EditText. In that case append to the GRANDPARENT
            // if it is a ViewGroup, else give up silently — the
            // password field will still work, just without the
            // scoped username.
            ViewGroup gp = parent.getParent() instanceof ViewGroup
                    ? (ViewGroup) parent.getParent() : null;
            if (gp != null) {
                try { gp.addView(username, 0); }
                catch (Throwable ignored) {
                    Timber.w(t, "attachUsernameField: parent rejected child; grandparent also failed");
                }
            } else {
                Timber.w(t, "attachUsernameField: parent rejected child and no grandparent");
            }
        }
    }

    /** Truncate a 0x... address to "0x1234...abcd" so the autofill
     *  hint is human-skim-able without leaking the full address into
     *  every accessibility service's view. */
    private static String shortenAddress(String addr) {
        if (addr == null || addr.length() < 10) return addr == null ? "" : addr;
        return addr.substring(0, 6) + "..." + addr.substring(addr.length() - 4);
    }

    // -----------------------------------------------------------------
    // Test hooks (visible-for-testing only)
    // -----------------------------------------------------------------

    /** Reset the memoized device suffix. Used by JVM unit tests that
     *  need to re-seed the suffix via reflection between cases. */
    static void resetDeviceSuffixCacheForTest() {
        synchronized (CredentialIdentifier.class) {
            cachedDeviceSuffix = null;
        }
    }

    /** Inject a known suffix value. Used by parity tests so the
     *  asserted strings are reproducible. */
    static void overrideDeviceSuffixForTest(@NonNull String suffix) {
        synchronized (CredentialIdentifier.class) {
            cachedDeviceSuffix = suffix;
        }
    }
}
