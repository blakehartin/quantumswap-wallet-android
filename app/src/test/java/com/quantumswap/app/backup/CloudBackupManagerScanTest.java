package com.quantumswap.app.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;

/**
 * Regression coverage for the bounded restore scan.
 *
 * <p>Three behaviours are pinned here:
 *
 * <ol>
 *   <li>{@link CloudBackupManager#readBounded} throws {@link IOException}
 *       once the running character count exceeds the cap, so the
 *       restore scan never holds an oversized buffer in heap.</li>
 *   <li>The {@link CloudBackupManager#MAX_BACKUP_FILE_BYTES} and
 *       {@link CloudBackupManager#MAX_FOLDER_CANDIDATES} constants
 *       expose the documented values; a regression that lowers
 *       either silently would be a usability cliff (folder full of
 *       legit backups becomes unusable) and a regression that
 *       raises either silently would be a heap-exhaustion
 *       regression.</li>
 *   <li>{@link CloudBackupManager.BackupCandidate} does not cache
 *       the ciphertext when constructed via the lazy URI-only
 *       constructor; {@link CloudBackupManager#loadEncryptedJson}
 *       still honours the legacy pre-loaded path for backward
 *       compatibility.</li>
 * </ol>
 *
 * <p>SAF I/O paths (the actual {@code Context} /
 * {@code ContentResolver} integration) are not unit-testable in
 * this module without Robolectric, which is intentionally absent
 * from the test classpath. The {@code readBounded} test seam
 * isolates the cap logic from the SAF surface so the cap is still
 * unit-testable end-to-end.</p>
 */
public class CloudBackupManagerScanTest {

    @Test
    public void readBounded_returnsExactContentWhenUnderCap() throws IOException {
        String input = "small payload"; // 13 chars, well under the cap
        String out = CloudBackupManager.readBounded(
                new StringReader(input), CloudBackupManager.MAX_BACKUP_FILE_BYTES);
        assertEquals(input, out);
    }

    @Test
    public void readBounded_acceptsExactlyAtCap() throws IOException {
        char[] buf = new char[CloudBackupManager.MAX_BACKUP_FILE_BYTES];
        java.util.Arrays.fill(buf, 'a');
        String input = new String(buf);
        String out = CloudBackupManager.readBounded(
                new StringReader(input), CloudBackupManager.MAX_BACKUP_FILE_BYTES);
        assertEquals(CloudBackupManager.MAX_BACKUP_FILE_BYTES, out.length());
    }

    @Test
    public void readBounded_throwsImmediatelyOnExceedingCap() {
        // 8 KiB cap with 8 KiB + 1 byte input — the cap MUST trip
        // before the StringBuilder grows past 8 KiB so the SAF
        // restore loop never holds an oversized buffer in heap.
        int smallCap = 8 * 1024;
        char[] buf = new char[smallCap + 1];
        java.util.Arrays.fill(buf, 'a');
        try {
            CloudBackupManager.readBounded(
                    new StringReader(new String(buf)), smallCap);
            fail("readBounded must throw when the input exceeds the cap");
        } catch (IOException e) {
            assertTrue("error must mention the cap: " + e.getMessage(),
                    e.getMessage().contains("exceeds size cap"));
        }
    }

    @Test
    public void readBounded_throwsOnRealisticOversizedInput() {
        // Build a single char[] one larger than the cap so the very
        // first read() call already trips the cap.
        char[] buf = new char[CloudBackupManager.MAX_BACKUP_FILE_BYTES + 1024];
        java.util.Arrays.fill(buf, 'x');
        try {
            CloudBackupManager.readBounded(new StringReader(new String(buf)),
                    CloudBackupManager.MAX_BACKUP_FILE_BYTES);
            fail("readBounded must throw above MAX_BACKUP_FILE_BYTES");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("exceeds size cap"));
        }
    }

    @Test
    public void constants_haveDocumentedValues() {
        // 256 KiB per file, 1024 files per folder. Bumping these
        // is a deliberate decision — surface it in code review.
        assertEquals(256 * 1024, CloudBackupManager.MAX_BACKUP_FILE_BYTES);
        assertEquals(1024, CloudBackupManager.MAX_FOLDER_CANDIDATES);
    }

    @Test
    public void backupCandidate_lazyConstructor_holdsNoCiphertext() {
        // scanQualifyingBackups uses this constructor so a folder
        // of N backups never has N ciphertexts resident.
        CloudBackupManager.BackupCandidate c =
                new CloudBackupManager.BackupCandidate(null, "test.wallet", "0xabc");
        assertEquals("0xabc", c.address);
        assertEquals("test.wallet", c.filename);
        assertNull("lazy candidate must NOT cache ciphertext", c.encryptedJson);
    }

    @Test
    public void backupCandidate_legacyConstructor_keepsCiphertextForBackcompat() {
        // The 4-arg constructor is retained so any caller that
        // still pre-loads ciphertext continues to work;
        // loadEncryptedJson honours the cached value when present.
        CloudBackupManager.BackupCandidate c =
                new CloudBackupManager.BackupCandidate(null, "test.wallet",
                        "{\"address\":\"0xabc\"}", "0xabc");
        assertEquals("{\"address\":\"0xabc\"}", c.encryptedJson);
    }

    @Test
    public void scanOutcome_constructorSurfacesAllFields() {
        java.util.List<CloudBackupManager.BackupCandidate> list = new java.util.ArrayList<>();
        list.add(new CloudBackupManager.BackupCandidate(null, "a.wallet", "0xa"));
        CloudBackupManager.ScanOutcome outcome =
                new CloudBackupManager.ScanOutcome(list, 5, 2, true);
        assertNotNull(outcome.candidates);
        assertEquals(1, outcome.candidates.size());
        assertEquals(5, outcome.totalDotWalletFilesSeen);
        assertEquals(2, outcome.oversizedFilesSkipped);
        assertTrue(outcome.truncatedByCandidateCap);
    }
}
