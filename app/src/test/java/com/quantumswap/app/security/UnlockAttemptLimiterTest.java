package com.quantumswap.app.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pure-JVM tests for {@link UnlockAttemptLimiter#backoffSeconds(int)}.
 * The Context-dependent paths (currentDecision, recordSuccess,
 * recordFailure) are covered by androidTest fixtures.
 */
public class UnlockAttemptLimiterTest {

    @Test
    public void backoff_zeroForFirstFourAttempts() {
        for (int n = 0; n < 5; n++) {
            assertEquals("count " + n, 0, UnlockAttemptLimiter.backoffSeconds(n));
        }
    }

    @Test
    public void backoff_thirtySecondsAtFive() {
        assertEquals(30, UnlockAttemptLimiter.backoffSeconds(5));
    }

    @Test
    public void backoff_oneMinuteAtSix() {
        assertEquals(60, UnlockAttemptLimiter.backoffSeconds(6));
    }

    @Test
    public void backoff_twoMinutesAtSeven() {
        assertEquals(120, UnlockAttemptLimiter.backoffSeconds(7));
    }

    @Test
    public void backoff_capsAtFiveMinutesForEightAndAbove() {
        // CRITICAL: cap was reduced from 1 hour to 5 minutes;
        // these assertions guard against a regression that
        // re-introduces the 1-hour worst-case for a legitimate
        // user with severe typo storms.
        assertEquals(300, UnlockAttemptLimiter.backoffSeconds(8));
        assertEquals(300, UnlockAttemptLimiter.backoffSeconds(9));
        assertEquals(300, UnlockAttemptLimiter.backoffSeconds(10));
        assertEquals(300, UnlockAttemptLimiter.backoffSeconds(100));
        assertEquals(300, UnlockAttemptLimiter.backoffSeconds(Integer.MAX_VALUE));
    }

    @Test
    public void backoff_isMonotonicallyNonDecreasing() {
        long previous = 0;
        for (int n = 0; n <= 50; n++) {
            long current = UnlockAttemptLimiter.backoffSeconds(n);
            assertTrue("backoffSeconds is not monotonic at n=" + n
                            + " (previous=" + previous + " current=" + current + ")",
                    current >= previous);
            previous = current;
        }
    }

    // The pure-JVM tests below exercise the null-vm branch so the
    // English fallback path stays accurate even when the catalog is
    // unreachable (early-boot dialogs, unmocked JsonViewModel under
    // test, etc.). Catalog-driven substitution is covered by
    // androidTest fixtures that own a JsonViewModel.
    @Test
    public void userFacingLockoutMessage_secondsWording() {
        String s = UnlockAttemptLimiter.userFacingLockoutMessage(30, null);
        assertTrue(s.contains("30 seconds"));
    }

    @Test
    public void userFacingLockoutMessage_oneMinuteWording() {
        String s = UnlockAttemptLimiter.userFacingLockoutMessage(60, null);
        assertTrue(s.contains("1 minute"));
    }

    @Test
    public void userFacingLockoutMessage_pluralMinutesWording() {
        String s = UnlockAttemptLimiter.userFacingLockoutMessage(300, null);
        assertTrue(s.contains("5 minutes"));
    }
}
