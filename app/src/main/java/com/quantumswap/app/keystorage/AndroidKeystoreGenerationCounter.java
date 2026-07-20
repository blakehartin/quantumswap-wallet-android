package com.quantumswap.app.keystorage;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import timber.log.Timber;

/**
 * Anti-rollback generation counter, signed by an
 * AndroidKeyStore-bound HMAC key.
 * <p><b>Why this exists:</b>
 * The strongbox file-level MAC catches in-place tampering of a
 * slot file, but it CANNOT detect a "swap a current slot file
 * with an old, legitimate copy of the same slot file from a
 * filesystem snapshot or a backup". Both copies are
 * MAC-valid because they were both authored by the legitimate
 * password. To catch this case we keep a monotonically-increasing
 * counter in AndroidKeyStore, signed under a non-extractable
 * HMAC key. Every successful persist bumps the counter by 1.
 * On unlock we read the slot's {@code generation} field and
 * compare against the keystore counter:
 * <ul>
 *   <li>{@code slotGen == keystoreCounter}: the live slot. Continue.</li>
 *   <li>{@code slotGen &lt; keystoreCounter}: ROLLBACK detected.
 *       The slot file is older than what we last committed. Abort
 *       unlock with a tamperDetected error.</li>
 *   <li>{@code slotGen &gt; keystoreCounter}: the keystore
 *       counter is behind the slot. This can happen if a write
 *       succeeded but the counter bump was interrupted by power
 *       loss between the rename and the AndroidKeyStore commit.
 *       Heal forward: bump the keystore counter to match the slot.
 *       This is safe because the slot is MAC-valid and represents
 *       legitimate state.</li>
 * </ul></p>
 * The HMAC key is non-extractable (StrongBox-backed when
 * available, TEE-backed otherwise). The counter VALUE is stored
 * in {@link SharedPreferences} alongside the HMAC tag computed
 * over the value. An attacker who can write to SharedPreferences
 * but cannot use the keystore key cannot forge a counter; they
 * can only roll back to a previous valid (counter, tag) pair,
 * which we detect because the slot's {@code generation} would
 * mismatch.</p>
 * <p><b>(android-ios parity):</b>
 * Mirrors iOS {@code KeychainGenerationCounter.swift} which uses
 * a Keychain-bound key (kSecAttrSynchronizable=false,
 * kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly) for the same
 * non-extractable property. The on-disk wire counter values are
 * identical across platforms.</p>
 */
public final class AndroidKeystoreGenerationCounter {

    /** AndroidKeyStore alias for the HMAC key. */
    public static final String KEY_ALIAS = "qc-strongbox-generation-counter-hmac-v1";

    /** SharedPreferences key for the (counter || tag) blob. */
    private static final String PREF_KEY = "STRONGBOX_GENERATION_COUNTER";

    /** Standalone preferences file so the counter does NOT
     *  share the same backup-rule scope as the wallet pref file.
     *  See {@code data_extraction_rules.xml}: this file is
     *  excluded from Auto Backup and Device Transfer. */
    private static final String PREF_FILE = "qc_strongbox_counter_v1";

    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final Context appCtx;

    public AndroidKeystoreGenerationCounter(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
    }

     /**
     * Read the current counter. Returns -1 if the counter has
     * never been initialised (first launch, fresh install).
     * Throws if the persisted (counter, tag) pair does not
     * verify under the keystore key — that means either:
     * <ul>
     *   <li>the keystore key was wiped (factory reset, app data
     *       clear) but SharedPreferences survived, OR</li>
     *   <li>an attacker tampered with the persisted value.</li>
     * </ul>
     * In either case the safest response is to abort unlock
     * with a clear "device has been tampered with or storage
     * is inconsistent" error and let the user restore from
     * backup. We do NOT silently rebuild the counter from the
     * slot file because a malicious slot could then claim any
     * generation it likes.
     */
    public long getCounter() throws GeneralSecurityException {
        SharedPreferences sp = appCtx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        String stored = sp.getString(PREF_KEY, null);
        if (stored == null || stored.isEmpty()) {
            return -1L;
        }
        int sep = stored.indexOf(':');
        if (sep <= 0 || sep == stored.length() - 1) {
            throw new GeneralSecurityException(
                    "AndroidKeystoreGenerationCounter: malformed stored value");
        }
        long counter;
        try {
            counter = Long.parseLong(stored.substring(0, sep));
        } catch (NumberFormatException nfe) {
            throw new GeneralSecurityException(
                    "AndroidKeystoreGenerationCounter: stored counter is not numeric", nfe);
        }
        String tagB64 = stored.substring(sep + 1);
        byte[] expectedTag = android.util.Base64.decode(tagB64,
                android.util.Base64.NO_WRAP);
        byte[] actualTag = computeKeystoreHmac(messageBytes(counter));
        try {
            if (!java.security.MessageDigest.isEqual(expectedTag, actualTag)) {
                throw new GeneralSecurityException(
                        "AndroidKeystoreGenerationCounter: counter tag verification failed");
            }
            return counter;
        } finally {
            Arrays.fill(actualTag, (byte) 0);
        }
    }

     /**
     * Persist {@code newCounter} after re-signing it under the
     * keystore key. Caller (UnlockCoordinator) MUST only call
     * this AFTER the matching slot write succeeded — otherwise
     * a write failure followed by a counter bump would
     * permanently lock the user out (the slot's generation
     * would lag the keystore counter and look like a rollback).
     */
    public void setCounter(long newCounter) throws GeneralSecurityException {
        ensureKeyExists();
        byte[] tag = computeKeystoreHmac(messageBytes(newCounter));
        try {
            String tagB64 = android.util.Base64.encodeToString(tag,
                    android.util.Base64.NO_WRAP);
            SharedPreferences sp = appCtx.getSharedPreferences(PREF_FILE,
                    Context.MODE_PRIVATE);
            sp.edit().putString(PREF_KEY, newCounter + ":" + tagB64).commit();
        } finally {
            Arrays.fill(tag, (byte) 0);
        }
    }

    /** Reset the counter to -1 (uninitialised). Called on
     *  full-wipe / factory-reset paths only. */
    public void reset() {
        SharedPreferences sp = appCtx.getSharedPreferences(PREF_FILE,
                Context.MODE_PRIVATE);
        sp.edit().remove(PREF_KEY).commit();
        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE_PROVIDER);
            ks.load(null);
            if (ks.containsAlias(KEY_ALIAS)) {
                ks.deleteEntry(KEY_ALIAS);
            }
        } catch (Exception e) {
            Timber.w(e, "AndroidKeystoreGenerationCounter.reset: keystore delete failed");
        }
    }

    // ---- Internals ----

    private void ensureKeyExists() throws GeneralSecurityException {
        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE_PROVIDER);
            ks.load(null);
            if (ks.containsAlias(KEY_ALIAS)) {
                return;
            }
            KeyGenerator kg = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE_PROVIDER);
            // we deliberately do NOT set
            // setUserAuthenticationRequired(true) on the counter
            // key — the unlock path is gated by the user's wallet
            // password, not by biometric. Adding biometric here
            // would force a fingerprint prompt on every unlock,
            // which the product spec rejected for friction reasons.
            // setIsStrongBoxBacked(true) is best-effort: if the
            // device lacks StrongBox we silently fall back to TEE
            // (still non-extractable, still adequate for the anti-
            // rollback property).
            KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                    KEY_ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setDigests(KeyProperties.DIGEST_SHA256);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                try {
                    builder.setIsStrongBoxBacked(true);
                } catch (Exception ignore) {
                    // Some emulators / devices throw on this even
                    // when supported. Fall through to TEE-only.
                }
            }
            kg.init(builder.build());
            try {
                kg.generateKey();
            } catch (Exception e) {
                // Fallback path without StrongBox.
                KeyGenerator kg2 = KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE_PROVIDER);
                kg2.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                        KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build());
                kg2.generateKey();
            }
        } catch (Exception e) {
            throw new GeneralSecurityException(
                    "AndroidKeystoreGenerationCounter: ensureKeyExists failed", e);
        }
    }

    private byte[] computeKeystoreHmac(byte[] message) throws GeneralSecurityException {
        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE_PROVIDER);
            ks.load(null);
            KeyStore.Entry entry = ks.getEntry(KEY_ALIAS, null);
            if (!(entry instanceof KeyStore.SecretKeyEntry)) {
                // Key not yet created — initial setCounter() path.
                // Fall back to a process-only HMAC. This is safe
                // because the next persist will go through the
                // keystore-bound path once the key exists.
                ensureKeyExists();
                ks.load(null);
                entry = ks.getEntry(KEY_ALIAS, null);
                if (!(entry instanceof KeyStore.SecretKeyEntry)) {
                    throw new GeneralSecurityException(
                            "AndroidKeystoreGenerationCounter: cannot fetch key entry");
                }
            }
            SecretKey key = ((KeyStore.SecretKeyEntry) entry).getSecretKey();
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(key);
            return mac.doFinal(message);
        } catch (GeneralSecurityException gse) {
            throw gse;
        } catch (Exception e) {
            throw new GeneralSecurityException(
                    "AndroidKeystoreGenerationCounter: HMAC computation failed", e);
        }
    }

    private static byte[] messageBytes(long counter) {
        // Stable 8-byte big-endian encoding so the same counter
        // value always hashes to the same tag.
        byte[] out = new byte[8];
        for (int i = 7; i >= 0; i--) {
            out[i] = (byte) (counter & 0xff);
            counter >>= 8;
        }
        return out;
    }
}
