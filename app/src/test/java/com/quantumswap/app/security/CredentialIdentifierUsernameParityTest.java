package com.quantumswap.app.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Cross-platform invariant test: every username string this Android
 * file emits MUST be byte-identical (modulo the per-device suffix)
 * to the corresponding string produced by the iOS counterpart at
 * {@code QuantumSwapWallet/Components/CredentialIdentifier.swift}.
 *
 * <p>Why this matters: a user signed into the same Apple ID on iOS
 * and the same Google account on Android relies on iCloud Keychain
 * and Google Password Manager respectively to recognize the account
 * slot. If either platform drifts the prefix or shape of the
 * username string, the user's saved password can be invisible to
 * the autofill provider — silent failure that the user only
 * discovers when they cannot unlock or restore.</p>
 *
 * <p>The {@code deviceSuffix} value naturally differs per device,
 * so the test injects a fixed UUID via the package-private
 * {@code overrideDeviceSuffixForTest} hook. The literals below come
 * verbatim from the iOS source — any drift on either side breaks
 * this test, surfacing the parity violation immediately.</p>
 */
public class CredentialIdentifierUsernameParityTest {

    private static final String FIXED_SUFFIX =
            "00000000-0000-0000-0000-000000000001";
    private static final String SAMPLE_ADDRESS = "0xABCDEF0123456789";

    @Before
    public void seedDeviceSuffix() {
        CredentialIdentifier.overrideDeviceSuffixForTest(FIXED_SUFFIX);
    }

    @After
    public void clearDeviceSuffix() {
        CredentialIdentifier.resetDeviceSuffixCacheForTest();
    }

    @Test
    public void strongboxUsername_matchesIosLiteral() {
        // iOS literal: "QuantumSwap-\(deviceSuffix)" from
        // CredentialIdentifier.swift `strongboxUsername`.
        String actual = CredentialIdentifier.strongboxUsername(/*ctx=*/null);
        assertEquals(
                "Strongbox username must match iOS literal byte-for-byte",
                "QuantumSwap-" + FIXED_SUFFIX,
                actual);
    }

    @Test
    public void backupUsername_matchesIosLiteral() {
        // iOS literal: "QuantumSwap-backup-\(address)-\(deviceSuffix)"
        // from CredentialIdentifier.swift `backupUsername(address:)`.
        String actual = CredentialIdentifier.backupUsername(/*ctx=*/null,
                SAMPLE_ADDRESS);
        assertEquals(
                "Per-wallet backup username must match iOS literal byte-for-byte",
                "QuantumSwap-backup-" + SAMPLE_ADDRESS + "-" + FIXED_SUFFIX,
                actual);
    }

    @Test
    public void backupBatchUsername_matchesIosLiteral() {
        // iOS literal: "QuantumSwap-backup-\(deviceSuffix)" from
        // CredentialIdentifier.swift `backupBatchUsername`.
        String actual = CredentialIdentifier.backupBatchUsername(/*ctx=*/null);
        assertEquals(
                "Batch backup username must match iOS literal byte-for-byte",
                "QuantumSwap-backup-" + FIXED_SUFFIX,
                actual);
    }

    @Test
    public void backupBatchUsername_lacksAddressSegment() {
        // Distinct prefix invariant: the batch username MUST NOT
        // contain an address segment, so a batch-mode autofill
        // query never collides with a per-wallet slot. The iOS
        // file header calls this out explicitly.
        String batch = CredentialIdentifier.backupBatchUsername(null);
        String perWallet = CredentialIdentifier.backupUsername(null,
                SAMPLE_ADDRESS);
        assertNotEquals(
                "Batch and per-wallet backup usernames must differ",
                batch, perWallet);
        assertTrue(
                "Per-wallet username must contain the address",
                perWallet.contains(SAMPLE_ADDRESS));
        assertTrue(
                "Batch username must NOT contain any address",
                !batch.contains(SAMPLE_ADDRESS));
    }

    @Test
    public void strongboxAndBackupPrefixesAreDistinct() {
        // CONTEXT ISOLATION (file header): a Save in one context
        // must never overwrite the other context's slot. The
        // distinct prefixes "QuantumSwap-" vs "QuantumSwap-backup-"
        // are what enforces this on both platforms.
        String strongbox = CredentialIdentifier.strongboxUsername(null);
        String backup = CredentialIdentifier.backupUsername(null,
                SAMPLE_ADDRESS);
        assertTrue(
                "Strongbox username starts with QuantumSwap- (not -backup-)",
                strongbox.startsWith("QuantumSwap-"));
        assertTrue(
                "Backup username starts with QuantumSwap-backup-",
                backup.startsWith("QuantumSwap-backup-"));
        assertNotEquals(strongbox, backup);
    }

    @Test
    public void newPasswordHintConstantMatchesW3CLiteral() {
        // The system "Save Password?" sheet on Android is gated on
        // the W3C-standard "newPassword" hint string. iOS ships
        // the equivalent via UITextContentType.newPassword. If
        // this constant ever drifts, Save would silently stop
        // being offered on Android.
        assertEquals("newPassword",
                CredentialIdentifier.AUTOFILL_HINT_NEW_PASSWORD);
    }

    @Test
    public void deviceSuffix_overrideTakesEffect() {
        // Sanity check on the test hook itself.
        assertEquals(FIXED_SUFFIX,
                CredentialIdentifier.deviceSuffix(/*ctx=*/null));
        CredentialIdentifier.overrideDeviceSuffixForTest(
                "00000000-0000-0000-0000-000000000002");
        String overridden = CredentialIdentifier.deviceSuffix(null);
        assertEquals("00000000-0000-0000-0000-000000000002", overridden);
        assertNotNull(overridden);
    }
}
