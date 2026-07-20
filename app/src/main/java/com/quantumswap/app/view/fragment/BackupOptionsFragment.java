package com.quantumswap.app.view.fragment;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.quantumswap.app.R;
import com.quantumswap.app.viewmodel.JsonViewModel;

/**
 * Backup-options surface shown when the user taps the per-row backup
 * icon on the {@link WalletsFragment}. Mirrors iOS
 * {@code QuantumSwapWallet/Screens/BackupOptionsViewController.swift}:
 * a dedicated full-area screen (NOT a dialog) with a back arrow at
 * the top, title, body, hairline divider, and the Cloud + File
 * buttons.
 *
 * <p>The actual cloud/file export logic runs INSIDE this fragment via
 * {@link com.quantumswap.app.backup.BackupExecutor} so a successful
 * backup leaves the user on this screen — they can immediately tap the
 * second option (cloud after file or vice versa) without having to
 * re-navigate. Mirrors iOS {@code BackupOptionsViewController.swift},
 * which runs the equivalent UIDocumentPicker flow inline and stays in
 * the navigation stack.</p>
 */
public class BackupOptionsFragment extends Fragment {

    /** HomeActivity-only callback — back-arrow restores the wallets
     *  list. Cloud/file flows complete inside this fragment via
     *  {@link com.quantumswap.app.backup.BackupExecutor}; they
     *  intentionally do NOT bubble through this listener so the user
     *  stays on the backup screen after a successful backup. */
    public interface OnBackupOptionsCompleteListener {
        void onBackupOptionsBack();
    }

    /** Bundle keys read from {@link #getArguments()}. HomeActivity
     *  packs these into the activity-level bundle before calling
     *  {@code beginTransactionNow(BackupOptionsFragment.newInstance(), bundle)},
     *  which then overwrites the fragment's arguments — so we MUST
     *  pull the values out of the shared bundle rather than out of
     *  a fragment-private args object (mirrors how
     *  {@link RevealWalletFragment} reads {@code walletAddress}). */
    public static final String ARG_ADDRESS = "backup_options_wallet_address";
    public static final String ARG_PASSWORD = "backup_options_wallet_password";

    private OnBackupOptionsCompleteListener listener;
    private JsonViewModel jsonViewModel;
    private String walletAddress;
    private String walletPassword;
    private com.quantumswap.app.backup.BackupExecutor backupExecutor;

    public BackupOptionsFragment() { }

    @NonNull
    public static BackupOptionsFragment newInstance() {
        return new BackupOptionsFragment();
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        try {
            this.listener = (OnBackupOptionsCompleteListener) context;
        } catch (ClassCastException cce) {
            throw new ClassCastException(context.toString()
                    + " must implement BackupOptionsFragment.OnBackupOptionsCompleteListener");
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        if (args != null) {
            walletAddress = args.getString(ARG_ADDRESS);
            walletPassword = args.getString(ARG_PASSWORD);
        }
        // Match WalletsFragment's construction: pass the activity
        // Context and (optional) languageKey forwarded through the
        // shared activity bundle by HomeActivity.
        String languageKey = args != null ? args.getString("languageKey") : null;
        jsonViewModel = new JsonViewModel(requireContext(), languageKey);
        // Activity-result launchers MUST be registered before
        // onStart(); construct here in onCreate so both the cloud
        // folder picker and the file create-document picker are wired
        // by the time the user can tap the buttons.
        backupExecutor = new com.quantumswap.app.backup.BackupExecutor(
                this, jsonViewModel);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.backup_options_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        TextView title = (TextView) view.findViewById(R.id.textView_backup_options_title);
        TextView body = (TextView) view.findViewById(R.id.textView_backup_options_body);
        Button file = (Button) view.findViewById(R.id.button_backup_options_file);
        ImageView back = (ImageView) view.findViewById(R.id.imageView_backup_options_back);

        title.setText(safe(jsonViewModel.getBackupOptionsTitleByLangValues(),
                "Backup your wallet"));
        body.setText(safe(jsonViewModel.getBackupOptionsDescriptionByLangValues(),
                ""));
        file.setText(safe(jsonViewModel.getBackupToFileByLangValues(),
                "Backup to a file"));
        back.setContentDescription(safe(jsonViewModel.getBackByLangValues(), "Back"));

        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onBackupOptionsBack();
            }
        });
        file.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (walletAddress == null || walletPassword == null
                        || backupExecutor == null) return;
                backupExecutor.startFileExport(walletAddress, walletPassword);
            }
        });
    }

    @Override
    public void onDestroyView() {
        // Wipe the stash before the GC roots get cleared so we don't
        // hold onto the wallet password longer than the screen is on.
        walletPassword = null;
        super.onDestroyView();
    }

    private static String safe(String value, String fallback) {
        return (value == null || value.isEmpty()) ? fallback : value;
    }
}
