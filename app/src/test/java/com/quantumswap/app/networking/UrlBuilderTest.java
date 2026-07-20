package com.quantumswap.app.networking;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link UrlBuilder}. Each test mirrors a row in
 * the iOS {@code UrlBuilderTests.swift} fixture.
 */
public class UrlBuilderTest {

    @Test
    public void isValidAddress_acceptsCanonicalQuantumCoinAddress() {
        // 32 bytes / 64 hex chars + 0x prefix.
        String addr = "0x" + repeat('a', 64);
        assertTrue(UrlBuilder.isValidAddress(addr));
    }

    @Test
    public void isValidAddress_rejectsShortInput() {
        // 20-byte / 40-hex Ethereum address — wrong shape for QuantumCoin.
        String addr = "0x" + repeat('a', 40);
        assertFalse(UrlBuilder.isValidAddress(addr));
    }

    @Test
    public void isValidAddress_rejectsInjection() {
        assertFalse(UrlBuilder.isValidAddress("0xdead/../../malicious?phish="));
        assertFalse(UrlBuilder.isValidAddress("javascript:alert(1)"));
        assertFalse(UrlBuilder.isValidAddress(null));
        assertFalse(UrlBuilder.isValidAddress(""));
    }

    @Test
    public void isValidTxHash_sameShapeAsAddress() {
        String hash = "0x" + repeat('1', 64);
        assertTrue(UrlBuilder.isValidTxHash(hash));
        assertFalse(UrlBuilder.isValidTxHash("0x123"));
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }
}
