package com.quantumswap.app.backup;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.quantumswap.app.Logger;
import com.quantumswap.app.keystorage.SecureStorage;
import com.quantumswap.app.utils.GlobalMethods;
import com.quantumswap.app.utils.PrefConnect;
import com.quantumswap.app.viewmodel.JsonViewModel;
import com.quantumswap.app.viewmodel.KeyViewModel;

import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reusable encrypt + Storage-Access-Framework save pipeline for
 * file-export wallet backups. Owns the
 * {@link ActivityResultLauncher} plus the pending-state stash so a
 * fragment that hosts a backup-options surface can fire the flow
 * without leaving the screen.
 *
 * <p><b>Why this exists:</b> previously the backup flow lived inside
 * {@code WalletsFragment} and the {@code BackupOptionsFragment} would
 * tell {@code HomeActivity} to swap to a fresh {@code WalletsFragment}
 * with auto-trigger arguments. The side effect was that a successful
 * backup left the user looking at the wallets list, NOT the backup
 * screen. Mirroring iOS, the backup screen now stays open after a
 * successful backup.
 *
 * <p><b>SAF launchers:</b> activity-result launchers MUST be registered
 * before {@link Fragment#onStart()}. Construct a {@code BackupExecutor}
 * in {@code Fragment.onCreate(...)} and never construct one lazily on
 * a button click.
 *
 * <p><b>iOS counterpart:</b> {@code BackupOptionsViewController.swift}
 * runs the equivalent encrypt + UIDocumentPickerViewController save
 * pipeline inline; the ViewController stays in the navigation stack.
 */
public final class BackupExecutor {

    private static final String TAG = "BackupExecutor";

    private final Fragment host;
    private final JsonViewModel vm;

    private final ActivityResultLauncher<Intent> exportCreateDocumentLauncher;

    private String pendingExportEncryptedJson;
    private String pendingExportWalletAddress;
    private String pendingExportFilename;

    public BackupExecutor(final Fragment host, final JsonViewModel vm) {
        this.host = host;
        this.vm = vm;
        this.exportCreateDocumentLauncher = host.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                new ActivityResultCallback<ActivityResult>() {
                    @Override
                    public void onActivityResult(ActivityResult result) {
                        markActivityUnlocked();
                        final String encryptedJson = pendingExportEncryptedJson;
                        pendingExportEncryptedJson = null;
                        pendingExportWalletAddress = null;
                        pendingExportFilename = null;
                        if (result == null
                                || result.getResultCode() != Activity.RESULT_OK
                                || result.getData() == null
                                || result.getData().getData() == null
                                || encryptedJson == null) {
                            return;
                        }
                        final Uri uri = result.getData().getData();
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                writeExportToUri(uri, encryptedJson);
                            }
                        }).start();
                    }
                });
    }

    /** Begin the file-export backup flow. Prompts the user for the
     *  backup password, encrypts, then launches the create-document
     *  picker. */
    public void startFileExport(final String walletAddress,
                                final String walletPassword) {
        com.quantumswap.app.view.dialog.BackupPasswordDialog.show(
                host.requireContext(), vm, walletAddress,
                new com.quantumswap.app.view.dialog.BackupPasswordDialog
                        .OnBackupPasswordListener() {
                    @Override
                    public void onPasswordSelected(final String backupPassword) {
                        encryptAndPickExportLocation(walletAddress,
                                walletPassword, backupPassword);
                    }
                    @Override
                    public void onCanceled() { }
                });
    }

    // ---------------------------------------------------------------
    // File (create-document) pipeline
    // ---------------------------------------------------------------

    private void encryptAndPickExportLocation(final String walletAddress,
                                              final String unlockPassword,
                                              final String backupPassword) {
        final AlertDialog waitDlg = com.quantumswap.app.view.dialog.WaitDialog
                .show(host.requireContext(), vm.getWaitWalletSaveByLangValues());
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    SecureStorage secureStorage = KeyViewModel.getSecureStorage();
                    String indexKey = PrefConnect.WALLET_ADDRESS_TO_INDEX_MAP.get(walletAddress);
                    if (indexKey == null) throw new IllegalStateException("wallet index missing");
                    String walletJsonStr = secureStorage.loadWallet(host.requireContext(),
                            Integer.parseInt(indexKey));
                    JSONObject walletJson = new JSONObject(walletJsonStr);
                    final CloudBackupManager.EncryptedResult enc =
                            CloudBackupManager.encryptWallet(walletJson, backupPassword);
                    final String filename = CloudBackupManager.buildFilename(enc.address);
                    if (host.getActivity() == null) return;
                    host.getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (waitDlg != null) try { waitDlg.dismiss(); } catch (Throwable ignore) { }
                            launchExportSavePicker(enc.json, enc.address, filename);
                        }
                    });
                } catch (final Exception e) {
                    Logger.e(TAG, "encryptAndPickExportLocation failed", e);
                    if (host.getActivity() == null) return;
                    host.getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (waitDlg != null) try { waitDlg.dismiss(); } catch (Throwable ignore) { }
                            String tmpl = vm.getBackupFailedByLangValues();
                            String msg = tmpl != null
                                    ? tmpl.replace("[ERROR]",
                                            e.getMessage() == null ? "" : e.getMessage())
                                    : ("Export failed: " + e.getMessage());
                            GlobalMethods.ShowErrorDialog(host.requireContext(),
                                    vm.getErrorTitleByLangValues(), msg);
                        }
                    });
                }
            }
        }).start();
    }

    private void launchExportSavePicker(String encryptedJson, String address, String filename) {
        pendingExportEncryptedJson = encryptedJson;
        pendingExportWalletAddress = address;
        pendingExportFilename = filename;
        try {
            setSuppressNextResumeLock(true);
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(CloudBackupManager.BACKUP_MIME_TYPE);
            intent.putExtra(Intent.EXTRA_TITLE, filename);
            String folderUriStr = PrefConnect.readString(host.requireContext(),
                    PrefConnect.CLOUD_BACKUP_FOLDER_URI_KEY, "");
            if (folderUriStr != null && !folderUriStr.isEmpty()) {
                try {
                    intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                            Uri.parse(folderUriStr));
                } catch (Exception ignore) { }
            }
            exportCreateDocumentLauncher.launch(intent);
        } catch (Exception e) {
            setSuppressNextResumeLock(false);
            GlobalMethods.ExceptionError(host.requireContext(), TAG, e);
            pendingExportEncryptedJson = null;
            pendingExportWalletAddress = null;
            pendingExportFilename = null;
        }
    }

    /**
     * Single-file export path with verify-by-readback.
     *
     * <ol>
     *   <li>Write the ciphertext bytes through the SAF
     *       {@link OutputStream}, then re-open the same Uri for
     *       reading and byte-compare against the original. A
     *       mismatch surfaces the existing
     *       {@code getBackupFailedByLangValues()} error dialog —
     *       the user must NEVER see "saved" for a corrupted
     *       readback (that would be a silent data-loss path).</li>
     *   <li>On verify success, show the existing
     *       {@code backup-saved} toast/dialog.</li>
     * </ol>
     */
    private void writeExportToUri(final Uri uri, final String encryptedJson) {
        OutputStream os = null;
        try {
            byte[] payload = encryptedJson.getBytes(StandardCharsets.UTF_8);
            os = host.requireContext().getContentResolver().openOutputStream(uri);
            if (os == null) throw new IllegalStateException("openOutputStream returned null");
            os.write(payload);
            os.flush();
            try { os.close(); } catch (Exception ignore) {}
            os = null;

            // Verify-by-readback. Re-open the same Uri and
            // byte-compare so we never tell the user "saved" if the
            // SAF provider silently dropped or mangled the write.
            verifyReadbackOrThrow(uri, payload);

            if (host.getActivity() == null) return;
            host.getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    String fileName = uri.getLastPathSegment() != null
                            ? uri.getLastPathSegment() : "";
                    String tmpl = vm.getBackupSavedByLangValues();
                    String msg = tmpl != null
                            ? tmpl.replace("[FOLDER]", "")
                                  .replace("[FILENAME]", fileName)
                            : "Wallet exported";
                    GlobalMethods.ShowMessageDialog(host.requireContext(), null, msg, null);
                }
            });
        } catch (final Exception e) {
            Logger.e(TAG, "export failed", e);
            if (host.getActivity() == null) return;
            host.getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    String tmpl = vm.getBackupFailedByLangValues();
                    String msg = tmpl != null
                            ? tmpl.replace("[ERROR]",
                                    e.getMessage() == null ? "" : e.getMessage())
                            : ("Export failed: " + e.getMessage());
                    GlobalMethods.ShowErrorDialog(host.requireContext(),
                            vm.getErrorTitleByLangValues(), msg);
                }
            });
        } finally {
            if (os != null) { try { os.close(); } catch (Exception ignore) {} }
        }
    }

    /**
     * Read the just-written URI back, bounded by the same cap that
     * {@link CloudBackupManager#readSafFile} enforces, and fail
     * loudly on any byte-level mismatch. Centralised here as a
     * helper so unit tests can exercise the verify failure path.
     */
    static void verifyReadback(java.io.InputStream in, byte[] expected) throws java.io.IOException {
        if (in == null) throw new java.io.IOException("readback openInputStream returned null");
        byte[] readBack = new byte[expected.length];
        int off = 0;
        while (off < readBack.length) {
            int r = in.read(readBack, off, readBack.length - off);
            if (r < 0) {
                throw new java.io.IOException("readback truncated: wrote=" + expected.length
                        + " got=" + off);
            }
            off += r;
        }
        // Drain one extra byte to detect over-long files (provider
        // appended garbage).
        int extra = in.read();
        if (extra >= 0) {
            throw new java.io.IOException("readback longer than written: wrote="
                    + expected.length + ", extra byte present");
        }
        if (!java.util.Arrays.equals(readBack, expected)) {
            throw new java.io.IOException("readback bytes do not match write");
        }
    }

    private void verifyReadbackOrThrow(Uri uri, byte[] expected) throws java.io.IOException {
        java.io.InputStream in = null;
        try {
            in = host.requireContext().getContentResolver().openInputStream(uri);
            verifyReadback(in, expected);
        } finally {
            if (in != null) { try { in.close(); } catch (Exception ignore) {} }
        }
    }

    // ---------------------------------------------------------------
    // HomeActivity bridge helpers (idle-lock window suppression).
    // ---------------------------------------------------------------

    private void markActivityUnlocked() {
        try {
            Activity act = host.getActivity();
            if (act instanceof com.quantumswap.app.view.activities.HomeActivity) {
                ((com.quantumswap.app.view.activities.HomeActivity) act).markUnlockedNow();
            }
        } catch (Exception ignore) { }
    }

    private void setSuppressNextResumeLock(boolean suppress) {
        try {
            Activity act = host.getActivity();
            if (act instanceof com.quantumswap.app.view.activities.HomeActivity) {
                ((com.quantumswap.app.view.activities.HomeActivity) act)
                        .setSuppressNextResumeLock(suppress);
            }
        } catch (Exception ignore) { }
    }
}
