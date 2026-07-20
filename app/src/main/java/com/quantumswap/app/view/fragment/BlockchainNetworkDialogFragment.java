package com.quantumswap.app.view.fragment;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.fragment.app.DialogFragment;

import com.quantumswap.app.R;
import com.quantumswap.app.model.BlockchainNetwork;
import com.quantumswap.app.utils.GlobalMethods;
import com.quantumswap.app.utils.PrefConnect;
import com.quantumswap.app.viewmodel.JsonViewModel;

import org.json.JSONException;

import java.util.List;

import timber.log.Timber;

public class BlockchainNetworkDialogFragment extends DialogFragment {

    private static final String TAG = "BlockchainNetworkDialogFragment";

    private int blockchainRadio = 0;

    private  OnBlockchainNetworkDialogCompleteListener mBlockchainNetworkDialogListener;

    public static BlockchainNetworkDialogFragment newInstance() {
        BlockchainNetworkDialogFragment fragment = new BlockchainNetworkDialogFragment();
        return fragment;
    }

    public BlockchainNetworkDialogFragment() {

    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.blockchain_network_dialog_fragment, container, false);
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        JsonViewModel jsonViewModel = new JsonViewModel(getContext(), getArguments().getString("languageKey"));

        String blockchainNetworkIdCurrentIndex = getArguments().getString("blockchainNetworkIdIndex");
        assert blockchainNetworkIdCurrentIndex != null;
        int blockchainNetworkIdIndex = Integer.parseInt(blockchainNetworkIdCurrentIndex);

        List<BlockchainNetwork> blockchainNetworkList = null;
        try {
            blockchainNetworkList = GlobalMethods.BlockChainNetworkRead(getContext());
        } catch (Exception e) {
            GlobalMethods.ExceptionError(getContext(), TAG, e);
            return;
        }

        //BlockchainNetworkViewModel blockchainNetworkViewModel = new BlockchainNetworkViewModel(getContext());

        TextView blockchainNetworkDialogTitleTextView = (TextView) getView().findViewById(R.id.textView_blockchain_network_dialog_title);
        RadioGroup blockchainNetworkRadioGroup = (RadioGroup) getView().findViewById(R.id.radioGroup_blockchain_network_dialog);
        blockchainNetworkRadioGroup.clearCheck();
        blockchainNetworkRadioGroup.removeAllViews();

        Button blockchaintNetworkDialogCancelButton = (Button) getView().findViewById(R.id.button_blockchain_network_dialog_cancel);
        Button blockchaintNetworkDialogOkButton = (Button) getView().findViewById(R.id.button_blockchain_network_dialog_ok);

        blockchainNetworkDialogTitleTextView.setText(jsonViewModel.getSelectNetworkByLangValues());
        blockchaintNetworkDialogCancelButton.setText(jsonViewModel.getCancelByLangValues());
        blockchaintNetworkDialogOkButton.setText(jsonViewModel.getOkByLangValues());

        blockchainNetworkRadioGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                RadioButton radioButton = (RadioButton) group.findViewById(checkedId);
                blockchainRadio = (int) radioButton.getTag();
            }
        });

        blockchaintNetworkDialogCancelButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    getDialog().dismiss();
                    mBlockchainNetworkDialogListener.onBlockchainNetworkDialogCancel();
                } catch (Exception e) {
                    Timber.w(e, "cancel click");
                }
            }
        });

        blockchaintNetworkDialogOkButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    if (blockchainNetworkIdIndex == blockchainRadio) {
                        getDialog().dismiss();
                        mBlockchainNetworkDialogListener.onBlockchainNetworkDialogCancel();
                        return;
                    }
                    // The active-network choice lives inside the
                    // encrypted strongbox payload (mirrors iOS
                    // BlockchainNetworkManager.setActive(_:password:)).
                    // The cached mainKey field was removed from
                    // UnlockCoordinator, so writeActiveIndex must
                    // re-derive mainKey via scrypt on every call,
                    // which requires the user's password. Prompt for
                    // it here exactly the way the add-network and
                    // unlock dialogs do.
                    promptUnlockAndPersistActiveIndex(blockchainRadio, jsonViewModel);
                } catch (Exception e) {
                    Timber.w(e, "ok click");
                }
            }
        });

        int currentPosition = 0;
        // Card uses the standard themed background (white in light
        // mode, black in dark mode) so the radio labels follow the
        // standard primary text color (colorCommon6: black in light,
        // white in dark) and stay readable in both themes without
        // any extra scoping.
        int radioTextColor = androidx.core.content.ContextCompat.getColor(
                getContext(), R.color.colorCommon6);
        for(BlockchainNetwork blockchainNetwork : blockchainNetworkList){
            RadioButton blockNetworkRadio = new RadioButton(getContext());
            blockNetworkRadio.setText(blockchainNetwork.getBlockchainName() + " ( Network Id " + blockchainNetwork.getNetworkId() + ")"); // +
            blockNetworkRadio.setTag(currentPosition);
            blockNetworkRadio.setTextColor(radioTextColor);
            blockchainNetworkRadioGroup.addView(blockNetworkRadio, currentPosition);
            if(blockchainNetworkIdIndex==currentPosition) {
                blockNetworkRadio.setChecked(true);
            }
            currentPosition++;
        }

        getDialog().getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);

    }

    @Override
    public void onStart() {
        super.onStart();
        // Default DialogFragment paints an opaque light surface behind the
        // card. With our 10dp horizontal margins on the inner card, that
        // surface shows through as a "white rectangular border" in dark
        // mode. Make the dialog window itself transparent so only our
        // rounded card is visible (matches the unlock dialog's window
        // setup in SendFragment etc.).
        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().setBackgroundDrawableResource(
                    android.R.color.transparent);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onStop(){
        super.onStop();
    }


    /**
     * Prompt the user for their wallet password, then persist the
     * new active-network index into the strongbox via
     * {@link com.quantumswap.app.utils.NetworkPersistence#writeActiveIndex}.
     * The persist runs on a background thread (scrypt is on the hot
     * path); UI dispatches happen back on the main thread. Mirrors
     * iOS {@code promptUnlockThenSetActive} behaviour where the
     * user MUST authenticate before the strongbox is re-encrypted
     * with the new active-index.
     */
    private void promptUnlockAndPersistActiveIndex(final int targetIndex,
                                                   final com.quantumswap.app.viewmodel.JsonViewModel vm) {
        final com.quantumswap.app.keystorage.SecureStorage secureStorage =
                com.quantumswap.app.viewmodel.KeyViewModel.getSecureStorage();
        if (secureStorage == null) {
            // No strongbox available — fall back to legacy prefs so
            // the user's selection is at least remembered locally.
            com.quantumswap.app.utils.PrefConnect.writeInteger(getContext(),
                    com.quantumswap.app.utils.PrefConnect.BLOCKCHAIN_NETWORK_ID_INDEX_KEY,
                    targetIndex);
            try { getDialog().dismiss(); } catch (Throwable ignore) { }
            com.quantumswap.app.events.NetworkChangeBroadcaster
                    .broadcastActiveNetworkChanged(getContext());
            mBlockchainNetworkDialogListener.onBlockchainNetworkDialogOk();
            return;
        }
        final String errorTitle = vm.getErrorTitleByLangValues();
        final androidx.appcompat.app.AlertDialog dialog =
                new androidx.appcompat.app.AlertDialog.Builder(getContext())
                        .setTitle((CharSequence) "")
                        .setView(R.layout.unlock_dialog_fragment)
                        .create();
        dialog.setCancelable(false);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(
                            android.graphics.Color.TRANSPARENT));
        }
        dialog.show();

        TextView unlockTitle = (TextView) dialog.findViewById(
                R.id.textView_unlock_langValues_unlock_wallet);
        TextView unlockBody = (TextView) dialog.findViewById(
                R.id.textView_unlock_langValues_enter_wallet_password);
        final android.widget.EditText pwd = (android.widget.EditText) dialog.findViewById(
                R.id.editText_unlock_langValues_enter_a_password);
        final Button unlockBtn = (Button) dialog.findViewById(
                R.id.button_unlock_langValues_unlock);
        final Button closeBtn = (Button) dialog.findViewById(
                R.id.button_unlock_langValues_close);

        unlockTitle.setText(vm.getUnlockWalletByLangValues());
        unlockBody.setText(vm.getEnterQuantumWalletPasswordByLangValues());
        pwd.setHint(vm.getEnterApasswordByLangValues());
        unlockBtn.setText(vm.getUnlockByLangValues());
        closeBtn.setText(vm.getCloseByLangValues());

        com.quantumswap.app.security.CredentialIdentifier.apply(
                pwd,
                com.quantumswap.app.security.CredentialIdentifier.Context.STRONGBOX_UNLOCK,
                null);
        ViewGroup unlockRoot = (ViewGroup)
                dialog.findViewById(R.id.linear_layout_unlock_content);
        if (unlockRoot != null) {
            com.quantumswap.app.security.CredentialIdentifier.attachUsernameField(
                    unlockRoot,
                    com.quantumswap.app.security.CredentialIdentifier
                            .strongboxUsername(getContext()));
        }
        com.quantumswap.app.utils.GlobalMethods.focusAndShowKeyboard(pwd, dialog);
        try {
            com.quantumswap.app.view.dialog.UnlockDialogs
                    .applyMandatory(dialog, false);
        } catch (Throwable ignore) { }

        unlockBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String password = pwd.getText() == null ? "" : pwd.getText().toString();
                if (password.isEmpty()) {
                    com.quantumswap.app.utils.GlobalMethods
                            .ShowErrorDialog(getContext(), errorTitle,
                                    vm.getEnterApasswordByLangValues());
                    return;
                }
                unlockBtn.setEnabled(false);
                closeBtn.setEnabled(false);
                pwd.setEnabled(false);
                // (Android, mirrors iOS network-switch flow):
                // surface a foreground "Please wait..." spinner the
                // INSTANT the user taps Unlock, before the scrypt
                // verify/unlock and the persist run on the worker
                // thread. Without this the unlock dialog freezes for
                // ~scrypt seconds and the user thinks the app is
                // hung. The same handle is reused across both
                // verify-password and writeActiveIndex (both of which
                // re-derive mainKey via scrypt) so there is exactly
                // one continuous spinner from tap to completion.
                final androidx.appcompat.app.AlertDialog waitDlg =
                        com.quantumswap.app.view.dialog.WaitDialog
                                .show(getContext(), vm.getWaitUnlockByLangValues());
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final boolean ok;
                        if (secureStorage.isUnlocked()) {
                            ok = secureStorage.getCoordinator()
                                    .verifyPassword(getContext(), password);
                        } else {
                            ok = secureStorage.unlock(getContext(), password);
                        }
                        if (!ok) {
                            if (getActivity() == null) return;
                            getActivity().runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    try { waitDlg.dismiss(); } catch (Throwable ignore) { }
                                    unlockBtn.setEnabled(true);
                                    closeBtn.setEnabled(true);
                                    pwd.setEnabled(true);
                                    // Preserve typed password on
                                    // failure so a one-character typo
                                    // is easy to fix. Mirrors SendFragment.
                                    pwd.requestFocus();
                                    com.quantumswap.app.utils.GlobalMethods
                                            .ShowErrorDialog(getContext(), errorTitle,
                                                    vm.getWalletPasswordMismatchByErrors());
                                }
                            });
                            return;
                        }
                        // Password verified. Persist on the same
                        // background thread (scrypt re-runs once
                        // inside writeActiveIndex -> persist). The
                        // WaitDialog stays visible across both phases
                        // so the user sees one continuous spinner.
                        Exception failure = null;
                        try {
                            com.quantumswap.app.utils.NetworkPersistence
                                    .writeActiveIndex(getContext(), secureStorage,
                                            targetIndex, password);
                        } catch (Exception e) {
                            failure = e;
                        }
                        final Exception finalFailure = failure;
                        if (getActivity() == null) return;
                        getActivity().runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                try { waitDlg.dismiss(); } catch (Throwable ignore) { }
                                if (finalFailure != null) {
                                    unlockBtn.setEnabled(true);
                                    closeBtn.setEnabled(true);
                                    pwd.setEnabled(true);
                                    com.quantumswap.app.utils.GlobalMethods
                                            .ExceptionError(getContext(), TAG, finalFailure);
                                    return;
                                }
                                try { dialog.dismiss(); } catch (Throwable ignore) { }
                                try { getDialog().dismiss(); } catch (Throwable ignore) { }
                                com.quantumswap.app.events.NetworkChangeBroadcaster
                                        .broadcastActiveNetworkChanged(getContext());
                                mBlockchainNetworkDialogListener.onBlockchainNetworkDialogOk();
                            }
                        });
                    }
                }).start();
            }
        });
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try { dialog.dismiss(); } catch (Throwable ignore) { }
                // User aborted the password prompt — treat as cancel
                // so the underlying network-select dialog also closes
                // without persisting the new index.
                try { getDialog().dismiss(); } catch (Throwable ignore) { }
                mBlockchainNetworkDialogListener.onBlockchainNetworkDialogCancel();
            }
        });
    }

    public static interface OnBlockchainNetworkDialogCompleteListener {
        public abstract void onBlockchainNetworkDialogCancel();
        public abstract void onBlockchainNetworkDialogOk();

    }

    public void onAttach(Context context) {
        super.onAttach(context);
        try {
            this.mBlockchainNetworkDialogListener = (OnBlockchainNetworkDialogCompleteListener) context;
        } catch (final ClassCastException e) {
            throw new ClassCastException(context.toString() + " ");
        }
    }

}
