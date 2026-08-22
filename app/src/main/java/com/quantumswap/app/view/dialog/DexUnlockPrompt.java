package com.quantumswap.app.view.dialog;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.quantumswap.app.R;
import com.quantumswap.app.security.UnlockAttemptLimiter;
import com.quantumswap.app.utils.GlobalMethods;
import com.quantumswap.app.viewmodel.JsonViewModel;


/**
 * Shared password gate for the DEX flows (Swap / Liquidity / Pools /
 * Releases). Mirrors the Send flow's unlock dialog exactly:
 *
 * <ul>
 *   <li>Same {@code unlock_dialog_fragment} layout and autofill
 *       identity as SendFragment's unlock.</li>
 *   <li>Same brute-force gate: {@link UnlockAttemptLimiter} on the
 *       STRONGBOX_UNLOCK channel, so DEX submits cannot be used to
 *       double the attacker's guess budget.</li>
 *   <li>Same verify-vs-unlock split: when the strongbox is already
 *       unlocked the password is verified as a second factor
 *       (verifyPassword, no scrypt re-derive perturbation); when
 *       locked, a full unlock runs.</li>
 * </ul>
 *
 * On success the verified password is handed to the callback on the
 * UI thread (callers use it for signing-key loads and for
 * password-gated release persists) and the dialog is dismissed. On
 * failure the dialog stays up for a retry.
 */
public final class DexUnlockPrompt {

    private DexUnlockPrompt() { }

    public interface OnUnlocked {
        void run(String password);
    }

    public static void show(final Activity activity,
                            final JsonViewModel jsonViewModel,
                            final OnUnlocked onUnlocked) {
        show(activity, jsonViewModel, onUnlocked, null);
    }

    /** Variant with a close/dismiss callback so step-driven flows
     *  (TxStepsDialog) can re-enable their footer button when the
     *  user backs out of the password gate. */
    public static void show(final Activity activity,
                            final JsonViewModel jsonViewModel,
                            final OnUnlocked onUnlocked,
                            final Runnable onClosed) {
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("")
                .setView(R.layout.unlock_dialog_fragment)
                .create();
        dialog.setCancelable(false);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialog.show();

        TextView title = dialog.findViewById(R.id.textView_unlock_langValues_unlock_wallet);
        title.setText(jsonViewModel.getUnlockWalletByLangValues());
        TextView subtitle = dialog.findViewById(R.id.textView_unlock_langValues_enter_wallet_password);
        subtitle.setText(jsonViewModel.getEnterQuantumWalletPasswordByLangValues());

        final EditText passwordEditText = dialog.findViewById(R.id.editText_unlock_langValues_enter_a_password);
        passwordEditText.setHint(jsonViewModel.getEnterApasswordByLangValues());
        com.quantumswap.app.security.CredentialIdentifier.apply(
                passwordEditText,
                com.quantumswap.app.security.CredentialIdentifier.Context.STRONGBOX_UNLOCK,
                null);
        android.view.ViewGroup unlockRoot = dialog.findViewById(R.id.linear_layout_unlock_content);
        if (unlockRoot != null) {
            com.quantumswap.app.security.CredentialIdentifier.attachUsernameField(
                    unlockRoot,
                    com.quantumswap.app.security.CredentialIdentifier
                            .strongboxUsername(activity));
        }
        GlobalMethods.focusAndShowKeyboard(passwordEditText, dialog);

        final Button unlockButton = dialog.findViewById(R.id.button_unlock_langValues_unlock);
        unlockButton.setText(jsonViewModel.getUnlockByLangValues());
        final Button closeButton = dialog.findViewById(R.id.button_unlock_langValues_close);
        closeButton.setText(jsonViewModel.getCloseByLangValues());
        UnlockDialogs.applyMandatory(dialog, false);

        closeButton.setOnClickListener(v -> {
            dialog.dismiss();
            if (onClosed != null) onClosed.run();
        });

        unlockButton.setOnClickListener(v -> {
            final String password = passwordEditText.getText() == null
                    ? "" : passwordEditText.getText().toString();
            if (password.isEmpty()) {
                GlobalMethods.ShowErrorDialog(activity,
                        jsonViewModel.getErrorTitleByLangValues(),
                        jsonViewModel.getEnterApasswordByLangValues());
                return;
            }
            unlockButton.setEnabled(false);
            closeButton.setEnabled(false);
            passwordEditText.setEnabled(false);
            final WaitDialog.MessageHandle waitHandle =
                    WaitDialog.showMessage(activity, jsonViewModel.getWaitUnlockByLangValues());
            com.quantumswap.app.security.WalletUnlock.verify(activity, jsonViewModel, password,
                    (unlocked, errorMessage) -> {
                        try { waitHandle.dismiss(); } catch (Throwable ignore) { }
                        if (!unlocked) {
                            unlockButton.setEnabled(true);
                            closeButton.setEnabled(true);
                            passwordEditText.setEnabled(true);
                            passwordEditText.requestFocus();
                            GlobalMethods.ShowErrorDialog(activity,
                                    jsonViewModel.getErrorTitleByLangValues(), errorMessage);
                            return;
                        }
                        dialog.dismiss();
                        onUnlocked.run(password.trim());
                    });
        });
    }
}
