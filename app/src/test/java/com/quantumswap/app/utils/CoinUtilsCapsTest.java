package com.quantumswap.app.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

/**
 * DoS-cap tests for {@link CoinUtils}. Every assertion mirrors the
 * iOS {@code CoinUtilsCapsTests.swift} fixture so the two ports cannot
 * silently drift on hostile-input handling.
 */
public class CoinUtilsCapsTest {

    @Test
    public void formatUnits_oversizedDecimalsReturnsSentinel() {
        // Hostile token contract claims 2 billion decimals.
        String out = CoinUtils.formatUnits("12345", Integer.MAX_VALUE);
        assertEquals(CoinUtils.OVERFLOW_SENTINEL, out);
    }

    @Test
    public void formatUnits_decimalsRightAtCapStillWorks() {
        // 64 is the cap; an input AT the cap must still format normally.
        String out = CoinUtils.formatUnits("1", CoinUtils.MAX_TOKEN_DECIMALS);
        // The exact value is 1 / 10^64; not interested in the decimal
        // expansion, just that we did not return the sentinel.
        assertNotEquals(CoinUtils.OVERFLOW_SENTINEL, out);
        assertNotEquals("0", out);
    }

    @Test
    public void formatUnits_oversizedHexInputReturnsSentinel() {
        StringBuilder huge = new StringBuilder("0x");
        for (int i = 0; i < CoinUtils.MAX_HEX_INPUT_CHARS + 1; i++) {
            huge.append("a");
        }
        assertEquals(CoinUtils.OVERFLOW_SENTINEL, CoinUtils.formatUnits(huge.toString(), 18));
    }

    @Test
    public void parseUnits_oversizedDecimalsClampsToZero() {
        // Caller-side typo on a custom token. We return "0" rather than
        // the sentinel because the result is consumed as a wei integer
        // by the JS bridge; "-" would not parse.
        assertEquals("0", CoinUtils.parseUnits("1.5", -1));
        assertEquals("0", CoinUtils.parseUnits("1.5", Integer.MAX_VALUE));
    }

    @Test
    public void hexToDecimalString_oversizedInputReturnsSentinel() {
        StringBuilder huge = new StringBuilder("0x");
        for (int i = 0; i < CoinUtils.MAX_HEX_INPUT_CHARS + 1; i++) {
            huge.append("f");
        }
        assertEquals(CoinUtils.OVERFLOW_SENTINEL, CoinUtils.hexToDecimalString(huge.toString()));
    }

    @Test
    public void hexToDecimalString_smallInputDecodes() {
        assertEquals("255", CoinUtils.hexToDecimalString("0xff"));
        assertEquals("255", CoinUtils.hexToDecimalString("ff"));
        assertEquals("0", CoinUtils.hexToDecimalString(""));
    }
}
