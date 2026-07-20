package com.quantumswap.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for {@link RedactingDebugTree#redact(String)}. Ensures
 * the heuristic redaction matches every leak shape we expect
 * to see in debug-build logs.
 */
public class RedactingDebugTreeTest {

    @Test
    public void redact_eraseAddress() {
        String input = "tx from 0x" + repeat("a", 40) + " ok";
        String out = RedactingDebugTree.redact(input);
        assertTrue(out.contains("0x<addr-redacted>"));
        assertFalse(out.contains(repeat("a", 40)));
    }

    @Test
    public void redact_eraseTxHash() {
        String input = "broadcast 0x" + repeat("b", 64) + " accepted";
        String out = RedactingDebugTree.redact(input);
        assertTrue(out.contains("0x<txhash-redacted>"));
        assertFalse(out.contains(repeat("b", 64)));
    }

    @Test
    public void redact_eraseLongBareHex() {
        String input = "private key " + repeat("c", 32) + " sealed";
        String out = RedactingDebugTree.redact(input);
        assertTrue(out.contains("<hex-redacted>"));
    }

    @Test
    public void redact_eraseBase64Blob() {
        String input = "ciphertext aGVsbG93b3JsZGFiY2RlZmdoaWprbG1ub3BxcnN0dXZ3eHl6YWJjZA== end";
        String out = RedactingDebugTree.redact(input);
        assertTrue(out.contains("<b64-redacted>"));
    }

    @Test
    public void redact_passesThroughShortStrings() {
        // No 32+ hex sequences, no addresses, no base64.
        String input = "user tapped Send";
        assertEquals(input, RedactingDebugTree.redact(input));
    }

    @Test
    public void redact_handlesNullAndEmpty() {
        assertEquals(null, RedactingDebugTree.redact(null));
        assertEquals("", RedactingDebugTree.redact(""));
    }

    @Test
    public void redact_doesNotRedactShortHex() {
        String input = "chainId 0x539 (decimal 1337)";
        String out = RedactingDebugTree.redact(input);
        // 0x539 is 3 hex chars - too short to match address (40)
        // or txhash (64), and 539 itself is 3 chars too short
        // for LONG_HEX (32). Should pass through unchanged.
        assertEquals(input, out);
    }

    @Test
    public void redact_txHashMatchesBeforeAddress() {
        // 64-hex-char string MUST be labelled txhash, not address.
        String input = "0x" + repeat("d", 64);
        String out = RedactingDebugTree.redact(input);
        assertTrue(out.contains("<txhash-redacted>"));
        assertFalse(out.contains("<addr-redacted>"));
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }
}
