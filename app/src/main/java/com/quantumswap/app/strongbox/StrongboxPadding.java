package com.quantumswap.app.strongbox;

import java.util.Arrays;

/**
 * Fixed-size 4 MiB bucket padding for the encrypted strongbox
 * payload. ISO/IEC 7816-4 padding scheme.
 *
 * <p><b>Why this exists (notes for reviewers):</b>
 * Without padding, the on-disk ciphertext length is
 * approximately the plaintext length. An attacker who can
 * read (but not decrypt) the slot file (the storage-medium
 * exfiltration threat profile) can therefore distinguish:
 * <ul>
 *   <li>0 wallets (~ 200 bytes)</li>
 *   <li>1 wallet (~ 10 KiB)</li>
 *   <li>N wallets (linear in N)</li>
 * </ul>
 * The wallet count is itself sensitive (it tells the attacker
 * how high-value the target is, lets them know when to invest
 * in a brute-force attempt, and reveals "this user has just
 * added their first wallet" timing information from a backup
 * diff). Padding to a fixed bucket means every slot file's
 * strongbox ciphertext is exactly the same length regardless
 * of how many wallets the user owns.</p>
 *
 * <p><b>Format:</b>
 * <pre>padded = plaintext || 0x80 || 0x00 * (BUCKET_SIZE - plaintext.length - 1)</pre>
 * The 0x80 marker byte separates the plaintext from the zero
 * pad. On unpad we walk backwards from the end:
 * <ul>
 *   <li>skip trailing 0x00 bytes</li>
 *   <li>the next byte MUST be 0x80</li>
 *   <li>the bytes before it are the original plaintext</li>
 * </ul>
 * This is the ISO/IEC 7816-4 padding scheme (well-known,
 * simple, and unambiguously reversible). Tolerates plaintext
 * lengths of 0..(BUCKET_SIZE-1) inclusive (one byte reserved
 * for the 0x80 marker).</p>
 *
 * <p><b>Why 4 MiB:</b>
 * QuantumCoin uses post-quantum signatures whose raw key bytes
 * dominate the per-wallet payload (Dilithium-class keys are
 * ~7.5 KiB raw; ~10 KiB once base64-wrapped at the JSON
 * boundary). The product requirement is to support &gt;= 256
 * wallets per install, which yields a worst-case payload of
 * ~2.6 MiB before networks/metadata overhead. 4 MiB
 * (4_194_304 bytes) gives ~40% headroom over the 256-wallet
 * worst case, fits comfortably in modern Android internal
 * storage, and rounds to a single power-of-two so the
 * AtomicSlotWriter rotation is sector-friendly.</p>
 *
 * <p><b>Tradeoffs:</b>
 * <ul>
 *   <li>Every write rewrites a full 4 MiB slot file even for
 *       a single-bool toggle. With AtomicSlotWriter's two-slot
 *       rotation that is 8 MiB physical writes per change.
 *       On a modern Android device with sequential write
 *       throughput &gt;= 200 MiB/s, that is &lt;~50 ms.
 *       User-perceptible only if the user toggles a setting
 *       at &gt;20 Hz, which never happens.</li>
 *   <li>The bucket size is deliberately not parameterised:
 *       EVERY Android install must produce identical
 *       strongbox ciphertext lengths so a multi-device backup
 *       pair (the same user with two devices) cannot be
 *       distinguished by length alone. A device-specific
 *       bucket size would defeat that property.</li>
 * </ul></p>
 *
 * <p><b>(android-ios parity):</b>
 * iOS uses the same 4 MiB bucket size and the same ISO/IEC
 * 7816-4 {@code 0x80 || 0x00*} scheme as part of the v=3
 * cross-platform portable schema. Both platforms emit
 * identical {@code strongbox.ct} byte lengths for any wallet
 * count, which means a slot file written on one platform can
 * be unlocked on the other with the same wallet password.
 * iOS source-of-truth: {@code QuantumSwapWallet/Schema/StrongboxPadding.swift}
 * {@code bucketSize}. The portability contract is enforced by
 * the seeded vector suite (see
 * {@code tests/fixtures/strongbox-v3-vectors/INDEX.md} and
 * {@code StrongboxPortabilityVectorTest}).</p>
 */
public final class StrongboxPadding {

    /** Fixed plaintext bucket size in bytes. The matching
     *  ciphertext length after AES-GCM seal is exactly this
     *  value (AES-GCM is a stream cipher; ciphertext length
     *  equals plaintext length, the AEAD tag is stored
     *  separately).
     *
     *  <p>4 MiB sized to fit &gt;= 256 wallets with raw
     *  post-quantum keys (~10 KiB/wallet base64 in JSON) plus
     *  networks + metadata + headroom. See class header for
     *  the full sizing argument.</p> */
    public static final int BUCKET_SIZE = 4_194_304;

    private static final byte MARKER = (byte) 0x80;

    public static final class PlaintextTooLargeException extends Exception {
        public final int actualBytes;
        public final int bucketBytes;

        PlaintextTooLargeException(int actualBytes, int bucketBytes) {
            super("StrongboxPadding: plaintext " + actualBytes + " bytes exceeds bucket "
                    + bucketBytes + " bytes");
            this.actualBytes = actualBytes;
            this.bucketBytes = bucketBytes;
        }
    }

    public static final class MalformedPaddingException extends Exception {
        MalformedPaddingException(String message) {
            super("StrongboxPadding: " + message);
        }
    }

    private StrongboxPadding() {}

    /**
     * Pad {@code plaintext} to exactly {@link #BUCKET_SIZE}
     * bytes using the {@code 0x80 || 0x00*} scheme. Throws if
     * {@code plaintext.length >= BUCKET_SIZE} because the
     * marker byte itself needs at least one trailing position.
     */
    public static byte[] pad(byte[] plaintext) throws PlaintextTooLargeException {
        if (plaintext.length >= BUCKET_SIZE) {
            throw new PlaintextTooLargeException(plaintext.length, BUCKET_SIZE);
        }
        byte[] padded = new byte[BUCKET_SIZE];
        // Bytes after the marker are already 0x00 from
        // new byte[BUCKET_SIZE]; no additional fill needed.
        System.arraycopy(plaintext, 0, padded, 0, plaintext.length);
        padded[plaintext.length] = MARKER;
        return padded;
    }

    /**
     * Reverse of {@link #pad}. Walks from the tail, skipping
     * zeros, expects 0x80, returns the prefix.
     */
    public static byte[] unpad(byte[] padded) throws MalformedPaddingException {
        if (padded.length != BUCKET_SIZE) {
            throw new MalformedPaddingException("padded length " + padded.length
                    + " != " + BUCKET_SIZE);
        }
        int i = padded.length - 1;
        while (i >= 0 && padded[i] == 0x00) {
            i--;
        }
        if (i < 0 || padded[i] != MARKER) {
            throw new MalformedPaddingException("padding marker missing or malformed");
        }
        return Arrays.copyOfRange(padded, 0, i);
    }
}
