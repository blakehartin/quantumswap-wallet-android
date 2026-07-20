package com.quantumswap.app.strongbox;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Pure-JVM tests for {@link StrongboxPadding}.
 *
 * <p><b>(notes for reviewers):</b> the 0x80 marker + zero
 * pad scheme is ISO/IEC 7816-4 and is unambiguously
 * reversible. The bucket size is 4 MiB on both Android AND
 * iOS under v=3 (both platforms now use the same 4 MiB bucket
 * to fit &gt;= 256 wallets with raw post-quantum keys), so
 * the same plaintext produces identical-length ciphertext on
 * every install of either platform — a cross-platform backup
 * pair cannot be distinguished by length alone.</p>
 */
public class StrongboxPaddingTest {

    @Test
    public void padThenUnpad_roundTrips_emptyInput() throws Exception {
        byte[] padded = StrongboxPadding.pad(new byte[0]);
        assertEquals(StrongboxPadding.BUCKET_SIZE, padded.length);
        assertEquals((byte) 0x80, padded[0]);
        // Every byte after the marker is zero.
        for (int i = 1; i < padded.length; i++) {
            assertEquals("byte " + i + " not zero", 0, padded[i]);
        }
        byte[] unpadded = StrongboxPadding.unpad(padded);
        assertArrayEquals(new byte[0], unpadded);
    }

    @Test
    public void padThenUnpad_roundTrips_typicalPayload() throws Exception {
        byte[] plain = "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8);
        byte[] padded = StrongboxPadding.pad(plain);
        assertEquals(StrongboxPadding.BUCKET_SIZE, padded.length);
        // Marker right after the plaintext.
        assertEquals((byte) 0x80, padded[plain.length]);
        byte[] unpadded = StrongboxPadding.unpad(padded);
        assertArrayEquals(plain, unpadded);
    }

    @Test
    public void padThenUnpad_roundTrips_largePayload() throws Exception {
        // Right at the upper edge: BUCKET_SIZE - 1 bytes is the
        // largest legal plaintext (1 byte for the marker).
        byte[] plain = new byte[StrongboxPadding.BUCKET_SIZE - 1];
        for (int i = 0; i < plain.length; i++) {
            plain[i] = (byte) (i & 0xff);
        }
        byte[] padded = StrongboxPadding.pad(plain);
        assertEquals(StrongboxPadding.BUCKET_SIZE, padded.length);
        assertEquals((byte) 0x80, padded[padded.length - 1]);
        byte[] unpadded = StrongboxPadding.unpad(padded);
        assertArrayEquals(plain, unpadded);
    }

    @Test
    public void pad_throwsWhenPlaintextTooLarge() {
        try {
            StrongboxPadding.pad(new byte[StrongboxPadding.BUCKET_SIZE]);
            fail("expected PlaintextTooLargeException");
        } catch (StrongboxPadding.PlaintextTooLargeException e) {
            assertEquals(StrongboxPadding.BUCKET_SIZE, e.actualBytes);
            assertEquals(StrongboxPadding.BUCKET_SIZE, e.bucketBytes);
        }
    }

    @Test
    public void unpad_throwsOnWrongLength() {
        try {
            StrongboxPadding.unpad(new byte[StrongboxPadding.BUCKET_SIZE - 1]);
            fail("expected MalformedPaddingException");
        } catch (StrongboxPadding.MalformedPaddingException e) {
            assertTrue(e.getMessage().contains("padded length"));
        }
    }

    @Test
    public void unpad_throwsWhenAllZeros() {
        // All-zero buffer has no 0x80 marker.
        byte[] padded = new byte[StrongboxPadding.BUCKET_SIZE];
        try {
            StrongboxPadding.unpad(padded);
            fail("expected MalformedPaddingException");
        } catch (StrongboxPadding.MalformedPaddingException e) {
            assertTrue(e.getMessage().contains("padding marker missing"));
        }
    }

    @Test
    public void unpad_throwsWhenWrongMarker() {
        byte[] padded = new byte[StrongboxPadding.BUCKET_SIZE];
        Arrays.fill(padded, (byte) 0);
        padded[0] = 0x42; // wrong marker
        try {
            StrongboxPadding.unpad(padded);
            fail("expected MalformedPaddingException");
        } catch (StrongboxPadding.MalformedPaddingException e) {
            assertTrue(e.getMessage().contains("padding marker missing"));
        }
    }

    @Test
    public void bucketSize_isExactly4MiB_andMatchesIos() {
        // CRITICAL: changing this constant breaks cross-device AND
        // cross-platform padding length stability. Same plaintext
        // produces identical-length ciphertext on every install of
        // either platform (iOS v=3 + Android v=3), so a backup pair
        // cannot be distinguished by length alone. 4 MiB is sized
        // to fit >= 256 wallets with raw post-quantum key bytes
        // (~10 KiB/wallet after base64-wrapping at the JSON
        // boundary) plus networks/metadata + headroom. The iOS
        // counterpart pins the same constant; the cross-platform
        // vector suite under tests/fixtures/strongbox-v3-vectors/
        // padding/ enforces byte-identity.
        assertEquals(4_194_304, StrongboxPadding.BUCKET_SIZE);
    }
}
