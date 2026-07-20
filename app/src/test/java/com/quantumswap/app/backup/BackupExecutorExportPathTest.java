package com.quantumswap.app.backup;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Regression coverage for the single-file export verify-by-
 * readback path that lives in {@link BackupExecutor#writeExportToUri},
 * plus the shared SAF cloud-authority detection helper in
 * {@link CloudBackupManager}.
 *
 * <p>The full {@code writeExportToUri} method is intertwined with
 * {@code Fragment} / {@code Activity} surfaces that are not unit-
 * testable from this module (Robolectric is intentionally absent
 * from the test classpath). The invariants we care about —
 *
 * <ol>
 *   <li>verify-by-readback failure suppresses the success path
 *       (driven by {@link BackupExecutor#verifyReadback}), and</li>
 *   <li>the SAF authority detection helper correctly classifies
 *       known cloud vs local/unknown authorities
 *       ({@link CloudBackupManager#isCloudAuthority}).</li>
 * </ol>
 *
 * <p>are exercised via the two narrow seams that production code
 * delegates to. Both seams are package-private/static specifically
 * so this test can call them without the Android {@code Uri} / SAF
 * surface.</p>
 */
public class BackupExecutorExportPathTest {

    // ------------------ verify-by-readback ------------------

    @Test
    public void verifyReadback_passesOnByteIdenticalStream() throws IOException {
        byte[] expected = "encrypted wallet bytes".getBytes(StandardCharsets.UTF_8);
        BackupExecutor.verifyReadback(new ByteArrayInputStream(expected), expected);
    }

    @Test
    public void verifyReadback_passesWhenStreamYieldsExactBytesInChunks() throws IOException {
        // Exercises the partial-read loop in verifyReadback: simulates
        // a SAF provider that hands back one byte per read() call.
        final byte[] expected = new byte[1024];
        for (int i = 0; i < expected.length; i++) expected[i] = (byte) (i & 0xFF);
        java.io.InputStream chunked = new java.io.InputStream() {
            int idx = 0;
            @Override
            public int read() {
                return idx < expected.length ? (expected[idx++] & 0xFF) : -1;
            }
            @Override
            public int read(byte[] buf, int off, int len) {
                if (idx >= expected.length) return -1;
                buf[off] = expected[idx++];
                return 1; // intentionally one byte at a time
            }
        };
        BackupExecutor.verifyReadback(chunked, expected);
    }

    @Test
    public void verifyReadback_failsOnTruncatedStream() {
        byte[] expected = "0123456789".getBytes(StandardCharsets.UTF_8);
        byte[] truncated = "01234".getBytes(StandardCharsets.UTF_8);
        try {
            BackupExecutor.verifyReadback(new ByteArrayInputStream(truncated), expected);
            fail("verifyReadback must throw on truncated readback");
        } catch (IOException e) {
            assertTrue("expected truncation message, got: " + e.getMessage(),
                    e.getMessage().contains("readback truncated"));
        }
    }

    @Test
    public void verifyReadback_failsOnByteMismatch() {
        byte[] expected = "alpha".getBytes(StandardCharsets.UTF_8);
        byte[] mangled = "ALPHA".getBytes(StandardCharsets.UTF_8);
        try {
            BackupExecutor.verifyReadback(new ByteArrayInputStream(mangled), expected);
            fail("verifyReadback must throw on byte mismatch");
        } catch (IOException e) {
            assertTrue("expected mismatch message, got: " + e.getMessage(),
                    e.getMessage().contains("do not match"));
        }
    }

    @Test
    public void verifyReadback_failsOnOverlongStream() {
        byte[] expected = "alpha".getBytes(StandardCharsets.UTF_8);
        byte[] overlong = "alpha-with-extra".getBytes(StandardCharsets.UTF_8);
        try {
            BackupExecutor.verifyReadback(new ByteArrayInputStream(overlong), expected);
            fail("verifyReadback must throw when readback longer than write");
        } catch (IOException e) {
            assertTrue("expected over-long message, got: " + e.getMessage(),
                    e.getMessage().contains("longer than written"));
        }
    }

    @Test
    public void verifyReadback_failsOnNullStream() {
        try {
            BackupExecutor.verifyReadback(null, "x".getBytes(StandardCharsets.UTF_8));
            fail("verifyReadback must throw on null stream");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("returned null"));
        }
    }

    // ------------------ cloud vs local routing ------------------

    @Test
    public void isCloudAuthority_picksCloudForKnownProviderAuthorities() {
        assertTrue("Google Drive must route to cloud modal",
                CloudBackupManager.isCloudAuthority(
                        "com.google.android.apps.docs.storage"));
        assertTrue("Dropbox must route to cloud modal",
                CloudBackupManager.isCloudAuthority(
                        "com.dropbox.android.fileprovider"));
        assertTrue("OneDrive must route to cloud modal",
                CloudBackupManager.isCloudAuthority(
                        "com.microsoft.skydrive.fileprovider"));
        assertTrue("Box must route to cloud modal",
                CloudBackupManager.isCloudAuthority("com.box.android"));
        assertTrue("Nextcloud must route to cloud modal",
                CloudBackupManager.isCloudAuthority(
                        "com.nextcloud.client.provider"));
    }

    @Test
    public void isCloudAuthority_picksLocalForExternalStorageAuthority() {
        assertFalse("Internal storage must route to local toast",
                CloudBackupManager.isCloudAuthority(
                        "com.android.externalstorage.documents"));
    }

    @Test
    public void isCloudAuthority_picksLocalForUnknownAuthority() {
        // Conservative-by-default: an unknown authority is treated as
        // local so we never falsely warn "may take a moment to sync"
        // for a thumb drive or a third-party local file manager.
        assertFalse("Unknown authority must route to local toast",
                CloudBackupManager.isCloudAuthority("com.example.unknownprovider"));
    }

    @Test
    public void isCloudAuthority_handlesNullDefensively() {
        assertFalse(CloudBackupManager.isCloudAuthority(null));
    }
}
