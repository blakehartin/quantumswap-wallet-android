package com.quantumswap.app.utils;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.quantumswap.app.keystorage.SecureStorage;
import com.quantumswap.app.keystorage.UnlockCoordinator;
import com.quantumswap.app.strongbox.StrongboxPayload;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import timber.log.Timber;

/**
 * Source of truth for QuantumSwap "releases" (contract sets: wrapped-Q,
 * factory, router). Mirrors the desktop app's src/lib/release.ts:
 * a read-only built-in list (Beta2) plus user-defined releases.
 *
 * <p>User-defined releases and the active-release selection are
 * security-critical (a scam release can drain the wallet through
 * approvals), so both live inside the strongbox's generic
 * {@link StrongboxPayload#secureItems} map (encrypted at rest,
 * checksummed) rather than plaintext SharedPreferences. Mutations
 * require the wallet password because
 * {@link UnlockCoordinator#persist} re-derives the seal key — this
 * matches the desktop flow where adding or switching a release
 * prompts for the wallet password.</p>
 */
public final class ReleaseStore {

    private ReleaseStore() { }

    private static final String TAG = "ReleaseStore";

    /** secureItems key: JSON array of user-defined releases. */
    public static final String ITEM_RELEASES = "dexCustomReleases";
    /** secureItems key: name of the currently active release. */
    public static final String ITEM_ACTIVE = "dexActiveRelease";

    public static final class Release {
        public final String name;
        public final String wq;
        public final String factory;
        public final String router;
        public final boolean builtin;

        public Release(String name, String wq, String factory, String router, boolean builtin) {
            this.name = name;
            this.wq = wq;
            this.factory = factory;
            this.router = router;
            this.builtin = builtin;
        }
    }

    /** Desktop BUILTIN_SWAP_RELEASES "Beta2" (src/lib/release.ts). */
    public static final Release BUILTIN = new Release(
            "Beta2",
            "0x45BD01BE5EF8509D9dA183689eA7Faf647331c54c7C9801dE54c9EDE9Ac44D92",
            "0x95085766E20fCBf0106dC7037020Ca069e22080DBEF2615551Bab65D59a99754",
            "0xC3666584A70A707E5e929Ba9871083ED8f9528eCe7a56FdbA485272a645D861e",
            true);

    // ---------------------------------------------------------------
    // Validation (desktop release.ts constraints)
    // ---------------------------------------------------------------

    /** 0x followed by 64 hex chars (post-quantum 32-byte addresses). */
    public static boolean isValidAddress(@Nullable String s) {
        return s != null && s.trim().matches("0x[0-9a-fA-F]{64}");
    }

    /** Max 60 plain-text characters, non-empty, no control chars. */
    public static boolean isValidName(@Nullable String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.isEmpty() || t.length() > 60) return false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c < 0x20 || c == 0x7f) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------
    // Read path (requires unlocked strongbox; DEX screens are only
    // reachable post-unlock)
    // ---------------------------------------------------------------

    @NonNull
    public static List<Release> readAll(@Nullable SecureStorage secureStorage) {
        List<Release> out = new ArrayList<>();
        out.add(BUILTIN);
        StrongboxPayload payload = livePayload(secureStorage);
        if (payload == null || payload.secureItems == null) return out;
        String json = payload.secureItems.get(ITEM_RELEASES);
        if (json == null || json.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Release r = new Release(
                        o.optString("name", ""),
                        o.optString("wq", ""),
                        o.optString("factory", ""),
                        o.optString("router", ""),
                        false);
                if (isValidName(r.name) && isValidAddress(r.wq)
                        && isValidAddress(r.factory) && isValidAddress(r.router)) {
                    out.add(r);
                }
            }
        } catch (Exception e) {
            Timber.w(e, "%s: stored release list unreadable; using builtin only", TAG);
        }
        return out;
    }

    /** Active release; falls back to the builtin when the stored name
     *  no longer resolves (e.g. list corrupted or entry removed). */
    @NonNull
    public static Release readActive(@Nullable SecureStorage secureStorage) {
        StrongboxPayload payload = livePayload(secureStorage);
        String activeName = null;
        if (payload != null && payload.secureItems != null) {
            activeName = payload.secureItems.get(ITEM_ACTIVE);
        }
        if (activeName == null || activeName.isEmpty()) return BUILTIN;
        for (Release r : readAll(secureStorage)) {
            if (r.name.equals(activeName)) return r;
        }
        return BUILTIN;
    }

    /**
     * Add the active release's contract overrides to a DEX bridge
     * payload. Built-in release adds nothing (the bridge carries the
     * same built-in addresses).
     */
    public static void applyActiveRelease(@Nullable SecureStorage secureStorage,
                                          @NonNull JSONObject payload) {
        Release active = readActive(secureStorage);
        if (active.builtin) return;
        try {
            payload.put("releaseWq", active.wq);
            payload.put("releaseFactory", active.factory);
            payload.put("releaseRouter", active.router);
        } catch (Exception e) {
            Timber.w(e, "%s: applyActiveRelease failed", TAG);
        }
    }

    // ---------------------------------------------------------------
    // Write path (password required — persist re-seals the strongbox)
    // ---------------------------------------------------------------

    public static void persistAddRelease(@NonNull Context ctx,
                                         @NonNull SecureStorage secureStorage,
                                         @NonNull Release release,
                                         @NonNull String password) throws Exception {
        if (!isValidName(release.name) || !isValidAddress(release.wq)
                || !isValidAddress(release.factory) || !isValidAddress(release.router)) {
            throw new IllegalArgumentException("invalid release");
        }
        for (Release existing : readAll(secureStorage)) {
            if (existing.name.equalsIgnoreCase(release.name.trim())) {
                throw new IllegalArgumentException("duplicate release name");
            }
        }
        UnlockCoordinator coord = requireUnlocked(secureStorage);
        StrongboxPayload payload = coord.getLivePayload();
        if (payload == null) throw new IllegalStateException("live payload is null");
        String previous = payload.secureItems.get(ITEM_RELEASES);
        JSONArray arr = (previous == null || previous.isEmpty())
                ? new JSONArray() : new JSONArray(previous);
        JSONObject o = new JSONObject();
        o.put("name", release.name.trim());
        o.put("wq", release.wq.trim());
        o.put("factory", release.factory.trim());
        o.put("router", release.router.trim());
        arr.put(o);
        payload.secureItems.put(ITEM_RELEASES, arr.toString());
        try {
            coord.persist(ctx, payload, password, /*uiPhase=*/null);
        } catch (Exception e) {
            // Roll back the in-memory mutation on failed persist.
            if (previous == null) payload.secureItems.remove(ITEM_RELEASES);
            else payload.secureItems.put(ITEM_RELEASES, previous);
            throw e;
        }
    }

    public static void persistActiveRelease(@NonNull Context ctx,
                                            @NonNull SecureStorage secureStorage,
                                            @NonNull String releaseName,
                                            @NonNull String password) throws Exception {
        UnlockCoordinator coord = requireUnlocked(secureStorage);
        StrongboxPayload payload = coord.getLivePayload();
        if (payload == null) throw new IllegalStateException("live payload is null");
        String previous = payload.secureItems.get(ITEM_ACTIVE);
        payload.secureItems.put(ITEM_ACTIVE, releaseName);
        try {
            coord.persist(ctx, payload, password, /*uiPhase=*/null);
        } catch (Exception e) {
            if (previous == null) payload.secureItems.remove(ITEM_ACTIVE);
            else payload.secureItems.put(ITEM_ACTIVE, previous);
            throw e;
        }
    }

    // ---------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------

    @Nullable
    private static StrongboxPayload livePayload(@Nullable SecureStorage secureStorage) {
        if (secureStorage == null) return null;
        UnlockCoordinator coord = secureStorage.getCoordinator();
        if (coord == null || !coord.isUnlocked()) return null;
        return coord.getLivePayload();
    }

    @NonNull
    private static UnlockCoordinator requireUnlocked(@NonNull SecureStorage secureStorage) {
        UnlockCoordinator coord = secureStorage.getCoordinator();
        if (coord == null || !coord.isUnlocked()) {
            throw new IllegalStateException("strongbox is locked");
        }
        return coord;
    }
}
