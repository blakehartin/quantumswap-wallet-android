package com.quantumswap.app.backup;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import com.quantumswap.app.Logger;

import androidx.documentfile.provider.DocumentFile;

import com.quantumswap.app.bridge.QuantumSwapJSBridge;
import com.quantumswap.app.utils.PrefConnect;
import com.quantumswap.app.viewmodel.KeyViewModel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Centralized logic for cloud backup, export, and restore of wallets. All I/O happens via
 * Android Storage Access Framework (SAF). Must be called from a background thread.
 */
public class CloudBackupManager {

    private static final String TAG = "CloudBackupManager";

    public static final String BACKUP_FILE_EXTENSION = ".wallet";
    public static final String BACKUP_MIME_TYPE = "application/octet-stream";

    /**
     * Hard cap on a single {@code .wallet} file before the scan
     * loads it into memory. Real {@code .wallet} exports are
     * ~kilobytes (one Web3 Secret Storage v3 envelope); 256 KiB
     * leaves a generous margin for future schema growth and for
     * any extra metadata a third-party export tool might attach,
     * while still preventing a folder full of multi-megabyte
     * stragglers from OOM-killing the restore process before any
     * wallet is recovered.
     */
    public static final int MAX_BACKUP_FILE_BYTES = 256 * 1024;

    /**
     * Hard cap on the number of candidate backup files surfaced by
     * a single folder scan. Real users rarely have more than a few
     * dozen wallets; the limit exists so a SAF folder pointing at
     * someone's full Drive root cannot stall the restore UI for
     * minutes while the scan reads thousands of candidates.
     * Newer-first ordering means the cap always preserves the
     * most-recent backups (the most likely targets for a real
     * recovery flow).
     */
    public static final int MAX_FOLDER_CANDIDATES = 1024;

    /**
     * Outcome of a {@link #scanQualifyingBackups} pass. Lets the
     * caller surface "showing first N of M backups" when the
     * per-folder cap fires, and "ignored K oversized files" when
     * the per-file cap fires. The {@code candidates} list itself
     * holds metadata only (address, URI, filename); the encrypted
     * JSON is loaded lazily one at a time via
     * {@link #loadEncryptedJson(Context, BackupCandidate)}.
     */
    public static final class ScanOutcome {
        public final List<BackupCandidate> candidates;
        public final int totalDotWalletFilesSeen;
        public final int oversizedFilesSkipped;
        public final boolean truncatedByCandidateCap;
        public ScanOutcome(List<BackupCandidate> candidates,
                           int totalDotWalletFilesSeen,
                           int oversizedFilesSkipped,
                           boolean truncatedByCandidateCap) {
            this.candidates = candidates;
            this.totalDotWalletFilesSeen = totalDotWalletFilesSeen;
            this.oversizedFilesSkipped = oversizedFilesSkipped;
            this.truncatedByCandidateCap = truncatedByCandidateCap;
        }
    }

    public static class DecryptedWallet {
        public String address;
        public String privateKey;
        public String publicKey;
        public String seed;
        public String[] seedWords;
    }

    /**
     * One qualifying backup file discovered in a folder scan: has
     * a {@code .wallet} extension, parses as JSON, and carries a
     * non-empty address.
     *
     * <p>{@code encryptedJson} is not loaded by
     * {@link #scanQualifyingBackups}. The scan only reads enough
     * of each file to validate its shape and extract the wallet
     * address; the candidate then holds metadata only (URI,
     * filename, address). The full ciphertext is loaded lazily
     * one at a time via
     * {@link #loadEncryptedJson(Context, BackupCandidate)} so a
     * folder containing many backups never has all ciphertexts
     * resident at once.</p>
     *
     * <p>The {@code encryptedJson} field is retained as a
     * read-only fallback that legacy callers can still set via
     * the four-arg constructor. New code should use the URI-only
     * constructor and call {@code loadEncryptedJson} inside the
     * per-candidate decrypt loop.</p>
     */
    public static class BackupCandidate {
        public final Uri uri;
        public final String filename;
        /**
         * @deprecated May be {@code null} when the candidate came
         * from {@link #scanQualifyingBackups}. New code MUST call
         * {@link #loadEncryptedJson(Context, BackupCandidate)} to
         * obtain the ciphertext bytes for the per-candidate
         * decrypt step, and discard the result immediately after
         * the decrypt attempt completes.
         */
        @Deprecated
        public final String encryptedJson;
        public final String address;
        public BackupCandidate(Uri uri, String filename, String encryptedJson, String address) {
            this.uri = uri;
            this.filename = filename;
            this.encryptedJson = encryptedJson;
            this.address = address;
        }
        /** Lazy-load constructor; ciphertext is fetched on demand. */
        public BackupCandidate(Uri uri, String filename, String address) {
            this(uri, filename, null, address);
        }
    }

    public static class EncryptedResult {
        public final String json;
        public final String address;
        public EncryptedResult(String json, String address) {
            this.json = json;
            this.address = address;
        }
    }

    /**
     * Result of a {@link #writeToSafFolderVerified} call. Surfaces both
     * the destination kind (so the caller can show the right modal:
     * "saved" vs "submitted to cloud, this may take a moment to sync")
     * AND the verify-by-readback outcome so the caller never tells the
     * user "saved" if the file isn't really there.
     *
     * <p>Why a typed enum and not a boolean</p>
     * Cloud destinations (Google Drive, Dropbox, OneDrive, etc.)
     * confirm a successful HTTP/SAF write the moment the bytes leave
     * the local device, but the file may not be visible from another
     * device until the cloud provider syncs. Local destinations
     * (internal storage, USB, removable media) are fully durable as
     * soon as the writeback completes. The user-facing wording must
     * differ - hence {@code SAVED_LOCAL} vs {@code SUBMITTED_CLOUD}.
     */
    public enum BackupWriteKind {
        /** Destination is local storage; the file is fully durable. */
        SAVED_LOCAL,
        /** Destination is a cloud provider; the local SAF write
         *  succeeded but the file may not yet be visible from other
         *  devices. */
        SUBMITTED_CLOUD,
        /** The verify-by-readback step failed; do NOT tell the user
         *  the backup was saved. */
        VERIFY_FAILED
    }

    public static final class BackupWriteOutcome {
        public final BackupWriteKind kind;
        public final DocumentFile file;
        public final String detail;
        public BackupWriteOutcome(BackupWriteKind kind, DocumentFile file, String detail) {
            this.kind = kind;
            this.file = file;
            this.detail = detail;
        }
    }

    /** Wallet input expected: { address, privateKey, publicKey, seed } where seed is a comma-
     *  separated seed phrase (as stored by SecureStorage). If seed is empty/missing, the
     *  private-key-only encryption path is used. */
    public static EncryptedResult encryptWallet(JSONObject walletJson, String password) throws Exception {
        JSONObject input = new JSONObject();
        String seed = walletJson.optString("seed", "");
        if (seed != null && !seed.isEmpty()) {
            String[] parts = seed.split(",");
            JSONArray arr = new JSONArray();
            for (String p : parts) {
                String trimmed = p.trim();
                if (!trimmed.isEmpty()) arr.put(trimmed);
            }
            input.put("seedWords", arr);
        } else {
            input.put("privateKey", walletJson.getString("privateKey"));
            input.put("publicKey", walletJson.getString("publicKey"));
        }
        String resultJson = KeyViewModel.getBridge().encryptWalletJson(input.toString(), password);
        JSONObject root = new JSONObject(resultJson);
        JSONObject data = root.getJSONObject("data");
        return new EncryptedResult(data.getString("json"), data.getString("address"));
    }

    public static DecryptedWallet decryptWallet(String encryptedJson, String password) throws Exception {
        String resultJson = KeyViewModel.getBridge().decryptWalletJson(encryptedJson, password);
        JSONObject root = new JSONObject(resultJson);
        JSONObject data = root.getJSONObject("data");
        DecryptedWallet w = new DecryptedWallet();
        w.address = data.optString("address", null);
        w.privateKey = data.optString("privateKey", null);
        w.publicKey = data.optString("publicKey", null);
        w.seed = data.isNull("seed") ? null : data.optString("seed", null);
        if (!data.isNull("seedWords")) {
            JSONArray words = data.optJSONArray("seedWords");
            if (words != null) {
                w.seedWords = new String[words.length()];
                for (int i = 0; i < words.length(); i++) {
                    w.seedWords[i] = words.getString(i);
                }
            }
        }
        return w;
    }

    public static String buildFilename(String address) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss.SSS'Z'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        String ts = fmt.format(new Date());
        String addr = address == null ? "unknown" : address.toLowerCase(Locale.US);
        if (addr.startsWith("0x")) addr = addr.substring(2);
        return "UTC--" + ts + "--" + addr + BACKUP_FILE_EXTENSION;
    }

    /** Writes contents to a new file under the given persisted SAF folder. Returns the DocumentFile
     *  that was created (or null on failure).
     *  Prefer {@link #writeToSafFolderVerified} for new code so the caller can show the right
     *  user-facing message based on whether the destination is local or cloud and whether the
     *  bytes survived a verify-by-readback. */
    public static DocumentFile writeToSafFolder(Context ctx, Uri folderUri,
                                                String filename, String contents) throws IOException {
        DocumentFile folder = DocumentFile.fromTreeUri(ctx, folderUri);
        if (folder == null || !folder.exists() || !folder.isDirectory()) {
            throw new IOException("Backup folder is not accessible");
        }
        DocumentFile file = folder.createFile(BACKUP_MIME_TYPE, filename);
        if (file == null) {
            throw new IOException("Failed to create backup file");
        }
        ContentResolver cr = ctx.getContentResolver();
        OutputStream os = null;
        try {
            os = cr.openOutputStream(file.getUri());
            if (os == null) throw new IOException("openOutputStream returned null");
            os.write(contents.getBytes(StandardCharsets.UTF_8));
            os.flush();
        } finally {
            if (os != null) {
                try { os.close(); } catch (IOException ignored) {}
            }
        }
        return file;
    }

    /**
     * Writes a backup file and verifies it by reading the bytes back
     * uncached. Returns a {@link BackupWriteOutcome} that tells the
     * caller (a) whether the destination is a local folder or a
     * cloud-provider folder, so the right user-facing modal can be
     * shown ("saved" vs "submitted to cloud, this may take a moment
     * to sync"); and (b) whether the readback equaled the source bytes.
     *
     * <p>Why verify by readback</p>
     * SAF cloud providers can return success on createFile + write
     * before the file is fully persisted. We immediately re-open the
     * file by URI and compare bytes; if the comparison fails we mark
     * VERIFY_FAILED and the caller MUST NOT tell the user the backup
     * was saved.
     *
     * <p>Cloud detection</p>
     * Heuristic by Uri authority. Conservative: anything we don't
     * recognize as known cloud is treated as local. The user-facing
     * difference is the modal wording, not the actual verify behaviour
     * (we always read back regardless).
     *
     * <p>iOS counterpart: {@code CloudBackupManager.writeBackupVerified}
     * with the same {@code SUBMITTED_CLOUD} / {@code SAVED_LOCAL}
     * dispositions and the same uncached read-back compare.</p>
     */
    public static BackupWriteOutcome writeToSafFolderVerified(Context ctx, Uri folderUri,
                                                              String filename, String contents)
            throws IOException {
        DocumentFile created = writeToSafFolder(ctx, folderUri, filename, contents);
        if (created == null) {
            return new BackupWriteOutcome(BackupWriteKind.VERIFY_FAILED, null,
                    "writeToSafFolder returned null");
        }
        // Uncached read-back. We re-open by URI so any in-process buffer
        // does not satisfy the read.
        String readBack;
        try {
            readBack = readSafFile(ctx, created.getUri());
        } catch (IOException ioe) {
            return new BackupWriteOutcome(BackupWriteKind.VERIFY_FAILED, created,
                    "read-back IO failed: " + ioe.getMessage());
        }
        if (readBack == null || !readBack.equals(contents)) {
            int got = readBack == null ? -1 : readBack.length();
            return new BackupWriteOutcome(BackupWriteKind.VERIFY_FAILED, created,
                    "read-back length mismatch: wrote=" + contents.length() + " got=" + got);
        }
        BackupWriteKind kind = isCloudFolder(folderUri)
                ? BackupWriteKind.SUBMITTED_CLOUD : BackupWriteKind.SAVED_LOCAL;
        return new BackupWriteOutcome(kind, created, null);
    }

    /**
     * Heuristic: classify a SAF folder Uri as cloud or local based on
     * its authority. Known cloud providers route through their own
     * DocumentsProvider authority; anything else (internal storage,
     * removable media, USB, etc.) is treated as local. Conservative
     * by design: an unknown authority is treated as local so we do
     * not falsely warn "may take a moment to sync" for a thumb drive.
     */
    public static boolean isCloudFolder(Uri folderUri) {
        if (folderUri == null) return false;
        return isCloudAuthority(folderUri.getAuthority());
    }

    /**
     * Test seam: pure-string authority check that the unit test
     * can call without spinning up Robolectric just to parse a
     * {@link Uri}. Production code reaches this via
     * {@link #isCloudFolder(Uri)}.
     */
    static boolean isCloudAuthority(String authority) {
        if (authority == null) return false;
        String a = authority.toLowerCase(Locale.US);
        // Known cloud-provider DocumentsProvider authorities. New
        // providers may need to be added here; the impact of a miss is
        // a less-precise modal message, never data loss.
        return a.contains("com.google.android.apps.docs")
                || a.contains("com.dropbox.android")
                || a.contains("com.microsoft.skydrive")
                || a.contains("com.box.android")
                || a.contains("mega.privacy.android")
                || a.contains("com.pcloud.pcloud")
                || a.contains("nextcloud")
                || a.contains("owncloud")
                || a.contains("onedrive")
                || a.contains("icloud");
    }

    /**
     * Walks the SAF folder, keeps only files that end in
     * {@link #BACKUP_FILE_EXTENSION}, can be read, parse as a JSON
     * object, and expose a non-empty address field. Anything else
     * is silently excluded (non-.wallet files, unreadable files,
     * non-JSON content, JSON without an address). Sorted
     * newest-first by mtime.
     *
     * <p>The returned {@link BackupCandidate} objects do not cache
     * the ciphertext. Call
     * {@link #loadEncryptedJson(Context, BackupCandidate)} to load
     * the bytes lazily one at a time during the decrypt loop.
     * Callers that still read {@code candidate.encryptedJson}
     * directly will see {@code null}; migrate them to
     * {@code loadEncryptedJson} or
     * {@link #scanQualifyingBackupsWithOutcome} which also surfaces
     * the per-folder size/count caps.</p>
     */
    public static List<BackupCandidate> scanQualifyingBackups(Context ctx, Uri folderUri) {
        return scanQualifyingBackupsWithOutcome(ctx, folderUri).candidates;
    }

    /**
     * Same scan as {@link #scanQualifyingBackups} but returns a
     * {@link ScanOutcome} that captures whether the per-file size
     * cap or the per-folder candidate cap fired so the caller can
     * tell the user "showing first N of M backups" or "ignored K
     * oversized files" instead of silently dropping them.
     */
    public static ScanOutcome scanQualifyingBackupsWithOutcome(Context ctx, Uri folderUri) {
        List<BackupCandidate> out = new ArrayList<>();
        DocumentFile folder = DocumentFile.fromTreeUri(ctx, folderUri);
        if (folder == null || !folder.exists() || !folder.isDirectory()) {
            return new ScanOutcome(out, 0, 0, false);
        }

        List<DocumentFile> dotWalletFiles = new ArrayList<>();
        for (DocumentFile f : folder.listFiles()) {
            if (!f.isFile()) continue;
            String name = f.getName();
            if (name == null) continue;
            if (!name.toLowerCase(Locale.US).endsWith(BACKUP_FILE_EXTENSION)) continue;
            dotWalletFiles.add(f);
        }
        int totalDotWallet = dotWalletFiles.size();

        Collections.sort(dotWalletFiles, new Comparator<DocumentFile>() {
            @Override
            public int compare(DocumentFile a, DocumentFile b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });

        int oversizedSkipped = 0;
        boolean truncated = false;
        for (DocumentFile f : dotWalletFiles) {
            if (out.size() >= MAX_FOLDER_CANDIDATES) {
                truncated = true;
                Logger.w(TAG, "scanQualifyingBackups: folder candidate cap reached ("
                        + MAX_FOLDER_CANDIDATES + "); ignoring older backups. "
                        + "Tell the user to narrow the folder selection if "
                        + "older entries are needed.");
                break;
            }
            String name = f.getName();
            Uri uri = f.getUri();
            // Per-file size cap. SAF reports the file length via
            // DocumentFile.length(); -1 means "unknown" (rare in
            // practice for cloud DocumentsProviders), in which case
            // we still try to read because readSafFile enforces the
            // same cap on the byte stream.
            long advertisedSize = -1L;
            try {
                advertisedSize = f.length();
            } catch (Throwable ignore) {
                // Fall through: the readSafFile call below will still cap.
            }
            if (advertisedSize > MAX_BACKUP_FILE_BYTES) {
                oversizedSkipped++;
                Logger.w(TAG, "scanQualifyingBackups: skipping oversized file "
                        + name + " (" + advertisedSize + " > "
                        + MAX_BACKUP_FILE_BYTES + " bytes)");
                continue;
            }
            String content;
            try {
                content = readSafFile(ctx, uri);
            } catch (IOException ioe) {
                // Includes the "exceeds size cap" path inside readSafFile.
                Logger.w(TAG, "scanQualifyingBackups: failed to read " + name
                        + ": " + ioe.getMessage());
                continue;
            }
            if (content == null || content.isEmpty()) continue;
            try {
                new JSONObject(content);
            } catch (Exception parseErr) {
                continue;
            }
            String address = extractAddressFromEncryptedJson(content);
            if (address == null || address.isEmpty()) continue;
            // Discard the ciphertext now; the per-candidate decrypt
            // loop will re-fetch via loadEncryptedJson. Memory at
            // any moment: 1 ciphertext + N tiny metadata entries
            // instead of N ciphertexts.
            out.add(new BackupCandidate(uri, name, address));
        }
        return new ScanOutcome(out, totalDotWallet, oversizedSkipped, truncated);
    }

    /**
     * Lazily load the ciphertext for a single candidate. Callers
     * MUST discard the returned string immediately after the
     * decrypt attempt completes so that a folder containing many
     * backups never has more than one ciphertext resident at a
     * time. Subject to {@link #MAX_BACKUP_FILE_BYTES}.
     */
    public static String loadEncryptedJson(Context ctx, BackupCandidate candidate)
            throws IOException {
        if (candidate == null) throw new IOException("null candidate");
        if (candidate.encryptedJson != null && !candidate.encryptedJson.isEmpty()) {
            // Legacy code-path: candidate was built by an old caller
            // that pre-loaded the ciphertext. Honour it.
            return candidate.encryptedJson;
        }
        return readSafFile(ctx, candidate.uri);
    }

    /**
     * Hard-cap the read at {@link #MAX_BACKUP_FILE_BYTES} so a
     * malformed or hostile SAF file can never exhaust heap.
     */
    public static String readSafFile(Context ctx, Uri fileUri) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        InputStream is = null;
        BufferedReader reader = null;
        try {
            is = cr.openInputStream(fileUri);
            if (is == null) throw new IOException("openInputStream returned null");
            reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            return readBounded(reader, MAX_BACKUP_FILE_BYTES);
        } finally {
            if (reader != null) { try { reader.close(); } catch (IOException ignored) {} }
            if (is != null) { try { is.close(); } catch (IOException ignored) {} }
        }
    }

    /**
     * Test seam: read characters from {@code reader} into a
     * string, throwing {@link IOException} if the running total
     * exceeds {@code maxChars}. Package-private so the pure-JVM
     * unit test in {@code CloudBackupManagerScanTest} can exercise
     * the cap without faking Android's {@code Context} /
     * {@code ContentResolver}. Production code reaches this
     * through {@link #readSafFile}.
     */
    static String readBounded(java.io.Reader reader, int maxChars) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        int n;
        int total = 0;
        while ((n = reader.read(buf)) > 0) {
            total += n;
            if (total > maxChars) {
                throw new IOException("backup file exceeds size cap of "
                        + maxChars + " bytes");
            }
            sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    /**
     * (Android, mirrors iOS CloudBackupManager.cleanupStaleStagedExports):
     *
     * <p>SAF backups always go through a {@code .wallet.tmp} staging
     * file before the atomic rename. A crash mid-rename can leave a
     * dangling tmp; the next launch should sweep stale copies so the
     * user's backup folder doesn't accumulate them.
     *
     * <p>Strategy on Android: we don't have a stable "backup folder"
     * URI permission across launches unless the user persisted it.
     * If they did, we walk the folder via {@link DocumentFile} and
     * delete any {@code *.wallet.tmp} entry whose
     * {@code lastModified()} is more than 24h old. The 24h grace
     * window protects an in-progress backup if the user happens to
     * relaunch the app concurrently.
     *
     * <p>Safe to call from a background thread; tolerates a missing
     * folder URI (returns 0 silently).
     *
     * @return number of stale tmp files deleted.
     */
    public static int cleanupStaleStagedExports(Context ctx) {
        if (ctx == null) return 0;
        try {
            String saved = PrefConnect.readString(ctx, PrefConnect.CLOUD_BACKUP_FOLDER_URI_KEY, "");
            if (saved == null || saved.isEmpty()) return 0;
            Uri folderUri = Uri.parse(saved);
            DocumentFile folder = DocumentFile.fromTreeUri(ctx, folderUri);
            if (folder == null || !folder.isDirectory()) return 0;
            long cutoff = System.currentTimeMillis() - (24L * 60L * 60L * 1000L);
            int deleted = 0;
            DocumentFile[] children = folder.listFiles();
            if (children == null) return 0;
            for (DocumentFile f : children) {
                String name = f.getName();
                if (name == null || !name.endsWith(BACKUP_FILE_EXTENSION + ".tmp")) continue;
                long mtime = f.lastModified();
                if (mtime > 0 && mtime < cutoff) {
                    try {
                        if (f.delete()) deleted++;
                    } catch (Throwable ignore) { }
                }
            }
            if (deleted > 0) {
                Logger.i(TAG, "cleanupStaleStagedExports: deleted " + deleted + " stale .wallet.tmp file(s)");
            }
            return deleted;
        } catch (Throwable t) {
            Logger.w(TAG, "cleanupStaleStagedExports failed (ignored)", t);
            return 0;
        }
    }

    public static String extractAddressFromEncryptedJson(String encryptedJson) {
        try {
            JSONObject obj = new JSONObject(encryptedJson);
            String address = obj.optString("address", null);
            if (address == null || address.isEmpty()) return null;
            if (!address.startsWith("0x")) {
                address = "0x" + address;
            }
            return address;
        } catch (Exception e) {
            return null;
        }
    }

}
