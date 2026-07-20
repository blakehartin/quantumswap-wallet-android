package com.quantumswap.app.strongbox;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Sanity coverage of the test-only deterministic RNG used by the
 * v=3 cross-platform vector suite. If this seam is broken, the
 * vector tests will fail with confusing IV-mismatch errors; this
 * file makes the seam itself fail loudly first.
 */
public class DeterministicSecureRandomSourceTest {

    @Test
    public void nextBytes_returnsConsecutiveSlicesOfTheSequence() {
        byte[] seq = new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 };
        DeterministicSecureRandomSource src =
                new DeterministicSecureRandomSource(seq);
        byte[] first = new byte[3];
        src.nextBytes(first);
        assertArrayEquals(new byte[] { 1, 2, 3 }, first);
        byte[] second = new byte[5];
        src.nextBytes(second);
        assertArrayEquals(new byte[] { 4, 5, 6, 7, 8 }, second);
        assertEquals(8, src.cursor());
        assertEquals(0, src.remaining());
    }

    @Test
    public void nextBytes_throwsOnExhaustion() {
        byte[] seq = new byte[] { 1, 2, 3 };
        DeterministicSecureRandomSource src =
                new DeterministicSecureRandomSource(seq);
        try {
            src.nextBytes(new byte[4]);
            fail("expected IllegalStateException on overdraw");
        } catch (IllegalStateException expected) {
            // ok — fixture authors must extend the sequence.
        }
    }

    @Test
    public void reset_replaysSequenceFromZero() {
        byte[] seq = new byte[] { 9, 8, 7, 6 };
        DeterministicSecureRandomSource src =
                new DeterministicSecureRandomSource(seq);
        byte[] first = new byte[4];
        src.nextBytes(first);
        src.reset();
        byte[] second = new byte[4];
        src.nextBytes(second);
        assertArrayEquals(first, second);
    }

    @Test
    public void constructor_clonesSequenceSoCallerMutationsAreIgnored() {
        byte[] seq = new byte[] { 1, 2, 3 };
        DeterministicSecureRandomSource src =
                new DeterministicSecureRandomSource(seq);
        seq[0] = (byte) 0xff;
        byte[] out = new byte[3];
        src.nextBytes(out);
        assertArrayEquals("constructor must defensively copy so a "
                + "later caller mutation cannot retroactively change "
                + "the deterministic stream",
                new byte[] { 1, 2, 3 }, out);
    }
}
