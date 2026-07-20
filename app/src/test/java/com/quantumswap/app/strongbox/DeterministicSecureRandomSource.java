package com.quantumswap.app.strongbox;

import com.quantumswap.app.keystorage.SecureRandomSource;

import java.util.Arrays;

/**
 * Test-only deterministic {@link SecureRandomSource}.
 *
 * <p>Wraps a fixed byte sequence and returns it in order across
 * successive {@code nextBytes(byte[])} calls. Used by the v=3
 * cross-platform vector test suite (see
 * {@code tests/fixtures/strongbox-v3-vectors/}) to inject the
 * pinned scrypt salt + mainKey + AEAD IVs so a re-seal of the
 * golden slot file produces byte-identical output. The Android
 * counterpart of iOS's
 * {@code SecureRandom.withDeterministicSequence(_:)}.</p>
 *
 * <p><b>Why this lives in the test source set:</b> a release
 * build cannot reach this class, so even an accidental
 * production reference would fail at link time. The runtime
 * cost of a guard inside production code (e.g. an {@code if
 * (BuildConfig.DEBUG)} branch) is avoided.</p>
 */
public final class DeterministicSecureRandomSource implements SecureRandomSource {

    private final byte[] sequence;
    private int cursor;

    /** Wrap {@code sequence}; the cursor starts at index 0. */
    public DeterministicSecureRandomSource(byte[] sequence) {
        if (sequence == null) {
            throw new IllegalArgumentException("sequence must not be null");
        }
        this.sequence = sequence.clone();
        this.cursor = 0;
    }

    @Override
    public void nextBytes(byte[] dst) {
        if (dst == null) {
            throw new NullPointerException("dst");
        }
        if (cursor + dst.length > sequence.length) {
            throw new IllegalStateException(
                    "DeterministicSecureRandomSource exhausted: requested "
                            + dst.length + " bytes at cursor=" + cursor
                            + " but only " + (sequence.length - cursor)
                            + " bytes remain. Extend the fixture sequence.");
        }
        System.arraycopy(sequence, cursor, dst, 0, dst.length);
        cursor += dst.length;
    }

    /** Bytes consumed so far. */
    public int cursor() {
        return cursor;
    }

    /** Bytes remaining in the sequence. */
    public int remaining() {
        return sequence.length - cursor;
    }

    /** Reset the cursor to zero so the same sequence can be
     *  replayed from the start. */
    public void reset() {
        this.cursor = 0;
    }

    @Override
    public String toString() {
        return "DeterministicSecureRandomSource{cursor=" + cursor
                + ", remaining=" + remaining()
                + ", head=" + (sequence.length == 0 ? "[]" :
                        Arrays.toString(Arrays.copyOf(sequence, Math.min(8, sequence.length))))
                + "}";
    }
}
