package com.quantumswap.app.strongbox;

import androidx.annotation.NonNull;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-wide redundancy state for the A/B strongbox slot pair.
 * <p>Java port of iOS {@code Strongbox/StrongboxRedundancyState.swift}.
 * <p>Why this exists: the strongbox writer
 * tries to write to BOTH slots on every persist. If exactly one of
 * the writes succeeds (e.g. transient ENOSPC on the other) the data
 * is safe but the redundancy guarantee is gone -- a single subsequent
 * disk error would render the wallet unrecoverable. We surface this
 * to the user via a banner on Unlock + Home so they can take action
 * (back up to file/cloud, or just hit a "rewrite both slots" button)
 * before both copies are lost.
 * <p>This class is intentionally tiny: it is only a state holder.
 * The writer flips it via {@link #set(RedundancyLevel)} after every
 * write attempt; the UI consumes via {@link #current()} on resume.
 * <p>Threading: {@link AtomicReference} guarantees safe publication
 * across the writer thread and the main thread.
 */
public final class StrongboxRedundancyState {

    public enum RedundancyLevel {
        /** Both slots are current; redundancy is intact. */
        DUAL,
        /** Only one of the two slots was rewritten on the last
         *  persist; the user should re-back-up before both copies
         *  drift further. */
        SINGLE_SLOT
    }

    private static final AtomicReference<RedundancyLevel> CURRENT =
            new AtomicReference<>(RedundancyLevel.DUAL);

    private StrongboxRedundancyState() { }

    /** Snapshot the current redundancy level. Non-blocking. */
    @NonNull
    public static RedundancyLevel current() {
        RedundancyLevel l = CURRENT.get();
        return l == null ? RedundancyLevel.DUAL : l;
    }

     /**
     * Update the redundancy level. Called from the writer's
     * post-write callback. Idempotent across re-entrant calls.
     */
    public static void set(@NonNull RedundancyLevel level) {
        CURRENT.set(level);
    }

    /** True iff the most recent persist landed on only one slot. */
    public static boolean isDegraded() {
        return current() == RedundancyLevel.SINGLE_SLOT;
    }

    // ---------------------------------------------------------------
    // iOS-style facade. The strongbox writer was ported from Swift
    // first and uses a `shared().markRedundant()` / `markSingleSlot()`
    // call shape. Keep that surface area so the Java writer reads
    // identically to the Swift writer; the static methods above are
    // the canonical Java API and remain the source of truth.
    // ---------------------------------------------------------------

    private static final StrongboxRedundancyState SHARED = new StrongboxRedundancyState();

    /** Singleton accessor mirroring the iOS facade. */
    @NonNull
    public static StrongboxRedundancyState shared() {
        return SHARED;
    }

    /** Equivalent of {@code set(DUAL)}; preferred name for writer code. */
    public void markRedundant() {
        set(RedundancyLevel.DUAL);
    }

    /** Equivalent of {@code set(SINGLE_SLOT)}; preferred name for writer code. */
    public void markSingleSlot() {
        set(RedundancyLevel.SINGLE_SLOT);
    }
}
