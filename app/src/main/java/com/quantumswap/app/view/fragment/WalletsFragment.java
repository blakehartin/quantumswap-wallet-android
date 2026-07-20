package com.quantumswap.app.view.fragment;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.RecyclerView;

import com.quantumswap.app.R;
import com.quantumswap.app.keystorage.SecureStorage;
import com.quantumswap.app.utils.GlobalMethods;
import com.quantumswap.app.utils.PrefConnect;
import com.quantumswap.app.view.adapter.WalletAdapter;
import com.quantumswap.app.viewmodel.JsonViewModel;
import com.quantumswap.app.viewmodel.KeyViewModel;

import java.util.Map;
import java.util.Objects;

public class WalletsFragment extends Fragment  {
    private static final String TAG = "WalletsFragment";
    private WalletAdapter walletAdapter;
    RecyclerView recycler;
    private JsonViewModel jsonViewModel;
    private KeyViewModel keyViewModel;
    private OnWalletsCompleteListener mWalletsListener;

    public static WalletsFragment newInstance() {
        WalletsFragment fragment = new WalletsFragment();
        return fragment;
    }

    public WalletsFragment() {

    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.wallet_fragment, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        this.recycler = view.findViewById(R.id.recycler_wallets);

        String languageKey = getArguments().getString("languageKey");

        keyViewModel = new KeyViewModel();

        jsonViewModel = new JsonViewModel(getContext(), languageKey);

        ImageButton backArrowImageButton = (ImageButton) getView().findViewById(R.id.imageButton_wallets_back_arrow);
        TextView walletTitleTextView = (TextView) getView().findViewById(R.id.textview_wallets_langValues_wallets);

        TextView walletHeaderAddressTextView = (TextView) getView().findViewById(R.id.textView_wallet_header_langValues_address);
        TextView walletHeaderScanTextView = (TextView) getView().findViewById(R.id.textView_wallet_header_langValues_scan);
        TextView walletHeaderSeedTextView = (TextView) getView().findViewById(R.id.textView_wallet_header_langValues_reveal_seed);
        TextView walletHeaderBackupTextView = (TextView) getView().findViewById(R.id.textView_wallet_header_langValues_backup);

        TextView walletCreateOrRestoreTextView = (TextView) getView().findViewById(R.id.textview_wallet_langValues_create_or_restore);

        ProgressBar progressBar = (ProgressBar) getView().findViewById(R.id.progress_wallets);

        walletTitleTextView.setText(jsonViewModel.getWalletsByLangValues());

        walletHeaderAddressTextView.setText(jsonViewModel.getAddressByLangValues());
        walletHeaderScanTextView.setText(jsonViewModel.getDpscanByLangValues());
        walletHeaderSeedTextView.setText(jsonViewModel.getRevealSeedByLangValues());
        walletHeaderBackupTextView.setText(jsonViewModel.getBackupByLangValues());

        walletCreateOrRestoreTextView.setText(jsonViewModel.getCreateRestoreWalletByLangValues());

        progressBar.setVisibility(View.VISIBLE);

        this.recycler.removeAllViewsInLayout();

        // Each wallet is a single full-width row whose four cells line up
        // with the four columns of wallet_header (Address / Block Explorer
        // / Backup / Reveal Seed). The previous GridAutoFitLayoutManager
        // call passed R.id.recycler_wallets (a resource id like
        // ~2_131_297_012) as the column-width-in-dp argument, which made
        // calculateNoOfColumns return 0 and the layout manager fall back
        // to its 200dp default. On phones that still produced one column
        // (~360dp wide), but on tablets (~700-900dp wide) it produced
        // 3-4 columns, so each wallet item shrank to a fraction of the
        // recycler width and its inner weight=1 cells no longer aligned
        // with the header columns above. A plain vertical
        // LinearLayoutManager guarantees one wallet per row at every
        // form factor.
        androidx.recyclerview.widget.LinearLayoutManager mLayoutManager =
                new androidx.recyclerview.widget.LinearLayoutManager(getContext());

        this.recycler.setLayoutManager(mLayoutManager);

        this.walletAdapter = new WalletAdapter(getContext(), PrefConnect.WALLET_INDEX_TO_ADDRESS_MAP);

        this.recycler.setAdapter(walletAdapter);

        this.walletAdapter.notifyDataSetChanged();

        progressBar.setVisibility(View.GONE);

        backArrowImageButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mWalletsListener.onWalletsCompleteByBackArrow();
            }
        });

        walletCreateOrRestoreTextView.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mWalletsListener.onWalletsCompleteByCreateOrRestore();
            }
        });

        walletAdapter.SetOnWalletItemClickListener(new WalletAdapter.OnWalletItemClickListener() {
            @Override
            public void onWalletItemClick(View view, int position) {
                   mWalletsListener.onWalletsCompleteBySwitchAddress(String.valueOf(position));
            }
            @Override
            public void onWalletRevealClick(View view, int position) {
                String indexKey =   String.valueOf(position);
                if(PrefConnect.WALLET_ADDRESS_TO_INDEX_MAP != null) {
                    for (Map.Entry<String, String> entry : PrefConnect.WALLET_INDEX_TO_ADDRESS_MAP.entrySet()) {
                        if (Objects.equals(indexKey, entry.getKey())) {
                            PrefConnect.writeString(getContext(), PrefConnect.WALLET_CURRENT_ADDRESS_INDEX_KEY, indexKey);
                            String walletAddress = entry.getValue();
                            unlockDialogFragment(progressBar, 1, walletAddress, languageKey);
                            break;
                        }
                    }
                }
            }
            @Override
            public void onWalletExportClick(View view, int position) {
                String indexKey = String.valueOf(position);
                String walletAddress = PrefConnect.WALLET_INDEX_TO_ADDRESS_MAP.get(indexKey);
                if (walletAddress == null) return;
                unlockDialogFragment(progressBar, 2, walletAddress, languageKey);
            }
        });

    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onStop(){
        super.onStop();
    }

    public static interface OnWalletsCompleteListener {
        //public abstract void onWalletsComplete(int status, String password, String indexKey);
        public abstract void onWalletsCompleteByBackArrow();
        public abstract void onWalletsCompleteByCreateOrRestore();
        public abstract void onWalletsCompleteBySwitchAddress(String walletAddress);
        public abstract void onWalletsCompleteByReveal(String walletAddress);
        /**
         * Tap-on-backup-icon → unlock-succeeded → push the dedicated
         * BackupOptionsFragment (mirrors iOS WalletsViewController
         * → BackupOptionsViewController via beginTransactionNow).
         * Replaces the old in-place AlertDialog backup chooser.
         */
        public abstract void onWalletsCompleteByBackup(String walletAddress,
                                                       String walletPassword);
    }

    public void onAttach(Context context) {
        super.onAttach(context);
        try {
            this.mWalletsListener = (OnWalletsCompleteListener)context;
        }
        catch (final ClassCastException e) {
            throw new ClassCastException(context.toString() + " ");
        }
    }

    private void unlockDialogFragment(ProgressBar progressBar, int listenerStatus, String walletAddress, String languageKey) {
        try {
            //Alert unlock dialog
            AlertDialog dialog = new AlertDialog.Builder(getContext())
                    .setTitle((CharSequence) "").setView((int)
                            R.layout.unlock_dialog_fragment).create();
            dialog.dismiss();
            dialog.setCancelable(false);
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(
                        new android.graphics.drawable.ColorDrawable(
                                android.graphics.Color.TRANSPARENT));
            }
            dialog.show();

            TextView unlockWalletTextView = (TextView) dialog.findViewById(R.id.textView_unlock_langValues_unlock_wallet);
            TextView unlockPasswordTextView = (TextView) dialog.findViewById(R.id.textView_unlock_langValues_enter_wallet_password);

            EditText passwordEditText = (EditText) dialog.findViewById(R.id.editText_unlock_langValues_enter_a_password);
            // Per-context autofill identity for the strongbox unlock
            // password. Same context as Send / HomeActivity unlock so
            // a password manager surfaces the SAME entry across all
            // three surfaces.
            com.quantumswap.app.security.CredentialIdentifier.apply(
                    passwordEditText,
                    com.quantumswap.app.security.CredentialIdentifier.Context.STRONGBOX_UNLOCK,
                    null);
            // Inject the invisible username field so the autofill
            // provider scopes its suggestion / save to the strongbox
            // slot. iOS counterpart is
            // `UsernameField.make(CredentialIdentifier.strongboxUsername)`.
            // Container is looked up by stable ID; walking up from the
            // EditText would land on the TextInputLayout, whose
            // addView(EditText) override would clobber the input
            // field's LayoutParams and crash on next measure.
            android.view.ViewGroup unlockRoot = (android.view.ViewGroup)
                    dialog.findViewById(R.id.linear_layout_unlock_content);
            if (unlockRoot != null) {
                com.quantumswap.app.security.CredentialIdentifier.attachUsernameField(
                        unlockRoot,
                        com.quantumswap.app.security.CredentialIdentifier
                                .strongboxUsername(getContext()));
            }
            Button unlockButton = (Button) dialog.findViewById(R.id.button_unlock_langValues_unlock);
            Button closeButton = (Button) dialog.findViewById(R.id.button_unlock_langValues_close);

            unlockWalletTextView.setText(jsonViewModel.getUnlockWalletByLangValues());
            unlockPasswordTextView.setText(jsonViewModel.getEnterQuantumWalletPasswordByLangValues());
            passwordEditText.setHint(jsonViewModel.getEnterApasswordByLangValues());
            GlobalMethods.focusAndShowKeyboard(passwordEditText, dialog);
            unlockButton.setText(jsonViewModel.getUnlockByLangValues());
            closeButton.setText(jsonViewModel.getCloseByLangValues());
            // WalletsFragment exposes a NON-mandatory
            // unlock — the user invoked it from the wallet-list
            // overflow menu (e.g. to copy a private key) and may
            // legitimately back out without performing the action.
            // The wrapper exposes the close button and re-enables the
            // back key.
            com.quantumswap.app.view.dialog.UnlockDialogs.applyMandatory(dialog, false);
            unlockButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    String walletPassword = passwordEditText.getText().toString();
                    if (walletPassword==null || walletPassword.isEmpty()) {
                        messageDialogFragment(languageKey, jsonViewModel.getEnterApasswordByLangValues());
                        return;
                    }
                    unlockButton.setEnabled(false);
                    closeButton.setEnabled(false);
                    passwordEditText.setEnabled(false);
                    VerifyPassword(dialog, unlockButton, closeButton, passwordEditText,
                            walletPassword, listenerStatus, walletAddress, languageKey);
                }
            });
            closeButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    dialog.dismiss();
                }
            });

        } catch (Exception e) {
            GlobalMethods.ExceptionError(getContext(), TAG, e);
        }
    }

    private void messageDialogFragment(String languageKey, String message) {
        try {
            Bundle bundleRoute = new Bundle();
            bundleRoute.putString("languageKey",languageKey);
            bundleRoute.putString("message", message);

            FragmentManager fragmentManager  = getFragmentManager();
            MessageInformationDialogFragment messageDialogFragment = MessageInformationDialogFragment.newInstance();
            messageDialogFragment.setCancelable(false);
            messageDialogFragment.setArguments(bundleRoute);
            messageDialogFragment.show(fragmentManager, "");
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getContext(), TAG, e);
        }
    }

    private void VerifyPassword(final AlertDialog dialog,
                                final Button unlockButton,
                                final Button closeButton,
                                final EditText passwordEditText,
                                final String walletPassword,
                                final int listenerStatus,
                                final String walletAddress,
                                final String languageKey) {
        final AlertDialog waitDlg = com.quantumswap.app.view.dialog.WaitDialog
                .show(getContext(), jsonViewModel.getWaitUnlockByLangValues());
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean unlocked = false;
                String lockoutMessage = null;
                try {
                    // Brute-force gate: refuse to even pay
                    // scrypt cost when the limiter has us
                    // locked out. See iOS UnlockAttemptLimiter
                    // for the matching pre-scrypt check on the
                    // strongbox-unlock channel.
                    com.quantumswap.app.security.UnlockAttemptLimiter.Decision lim =
                            com.quantumswap.app.security.UnlockAttemptLimiter
                                    .currentDecision(getContext());
                    if (lim.kind == com.quantumswap.app.security.UnlockAttemptLimiter.DecisionKind.LOCKED) {
                        lockoutMessage = com.quantumswap.app.security
                                .UnlockAttemptLimiter.userFacingLockoutMessage(lim.remainingSeconds, jsonViewModel);
                    } else {
                        SecureStorage secureStorage = KeyViewModel.getSecureStorage();
                        unlocked = secureStorage.unlock(getContext(), walletPassword);
                        if (unlocked) {
                            com.quantumswap.app.security.UnlockAttemptLimiter
                                    .recordSuccess(getContext(),
                                            com.quantumswap.app.security
                                                    .UnlockAttemptLimiter.Channel.STRONGBOX_UNLOCK);
                        } else {
                            com.quantumswap.app.security.UnlockAttemptLimiter
                                    .recordFailure(getContext(),
                                            com.quantumswap.app.security
                                                    .UnlockAttemptLimiter.Channel.STRONGBOX_UNLOCK);
                        }
                    }
                } catch (Exception e) {
                    com.quantumswap.app.Logger.e(TAG, "unlock failed", e);
                }
                final boolean ok = unlocked;
                final String lockoutMessageFinal = lockoutMessage;
                if (getActivity() == null) return;
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try { if (waitDlg != null) waitDlg.dismiss(); } catch (Throwable ignore) { }
                        if (!ok) {
                            if (unlockButton != null) unlockButton.setEnabled(true);
                            if (closeButton != null) closeButton.setEnabled(true);
                            if (passwordEditText != null) {
                                passwordEditText.setEnabled(true);
                                // Preserve typed password on failure
                                // so a one-character typo is easy to
                                // fix. Mirrors SendFragment.
                                passwordEditText.requestFocus();
                            }
                            android.content.Context ctx = getContext();
                            if (ctx != null) {
                                String errorMessage = lockoutMessageFinal != null
                                        ? lockoutMessageFinal
                                        : jsonViewModel.getWalletPasswordMismatchByErrors();
                                GlobalMethods.ShowErrorDialog(ctx,
                                        jsonViewModel.getErrorTitleByLangValues(),
                                        errorMessage);
                            }
                            return;
                        }
                        if (dialog != null) dialog.dismiss();
                        switch (listenerStatus) {
                            case 1:
                                mWalletsListener.onWalletsCompleteByReveal(walletAddress);
                                break;
                            case 2:
                                // Hand off to HomeActivity which pushes
                                // BackupOptionsFragment (replaces the
                                // pre-existing AlertDialog backup chooser
                                // for iOS BackupOptionsViewController parity).
                                if (mWalletsListener != null) {
                                    mWalletsListener.onWalletsCompleteByBackup(
                                            walletAddress, walletPassword);
                                }
                                break;
                        }
                    }
                });
            }
        }).start();
    }

}
