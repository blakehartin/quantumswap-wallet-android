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
import com.quantumswap.app.keystorage.SecureStorage;
import com.quantumswap.app.security.UnlockAttemptLimiter;
import com.quantumswap.app.utils.GlobalMethods;
import com.quantumswap.app.utils.PrefConnect;
import com.quantumswap.app.viewmodel.JsonViewModel;
import com.quantumswap.app.viewmodel.KeyViewModel;

import org.json.JSONObject;

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

        closeButton.setOnClickListener(v -> dialog.dismiss());

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
            new Thread(() -> {
                boolean ok = false;
                String lockoutMessage = null;
                try {
                    UnlockAttemptLimiter.Decision lim =
                            UnlockAttemptLimiter.currentDecision(activity);
                    if (lim.kind == UnlockAttemptLimiter.DecisionKind.LOCKED) {
                        lockoutMessage = UnlockAttemptLimiter
                                .userFacingLockoutMessage(lim.remainingSeconds, jsonViewModel);
                    } else {
                        SecureStorage secureStorage = KeyViewModel.getSecureStorage();
                        if (secureStorage.isUnlocked()) {
                            com.quantumswap.app.keystorage.UnlockCoordinator uc =
                                    secureStorage.getCoordinator();
                            ok = uc != null && uc.verifyPassword(activity, password.trim());
                        } else {
                            ok = secureStorage.unlock(activity, password.trim());
                        }
                        if (ok) {
                            UnlockAttemptLimiter.recordSuccess(activity,
                                    UnlockAttemptLimiter.Channel.STRONGBOX_UNLOCK);
                        } else {
                            UnlockAttemptLimiter.recordFailure(activity,
                                    UnlockAttemptLimiter.Channel.STRONGBOX_UNLOCK);
                        }
                    }
                } catch (Exception e) {
                    timber.log.Timber.e(e, "DEX flow unlock failed");
                }
                final boolean unlocked = ok;
                final String lockoutMessageFinal = lockoutMessage;
                activity.runOnUiThread(() -> {
                    try { waitHandle.dismiss(); } catch (Throwable ignore) { }
                    if (!unlocked) {
                        unlockButton.setEnabled(true);
                        closeButton.setEnabled(true);
                        passwordEditText.setEnabled(true);
                        passwordEditText.requestFocus();
                        String errorMessage = lockoutMessageFinal != null
                                ? lockoutMessageFinal
                                : jsonViewModel.getWalletPasswordMismatchByErrors();
                        GlobalMethods.ShowErrorDialog(activity,
                                jsonViewModel.getErrorTitleByLangValues(), errorMessage);
                        return;
                    }
                    dialog.dismiss();
                    onUnlocked.run(password.trim());
                });
            }).start();
        });
    }

    /**
     * Load the signing keys for {@code walletAddress} from the (already
     * unlocked) strongbox. Background-thread only, mirrors
     * SendFragment.sendTransaction's key load.
     *
     * @return {privateKeyBase64, publicKeyBase64}
     */
    public static String[] loadWalletKeys(Context ctx, String walletAddress) throws Exception {
        SecureStorage secureStorage = KeyViewModel.getSecureStorage();
        String indexStr = PrefConnect.WALLET_ADDRESS_TO_INDEX_MAP.get(walletAddress);
        if (indexStr == null) {
            throw new Exception("Wallet not found for address");
        }
        String walletJsonStr = secureStorage.loadWallet(ctx, Integer.parseInt(indexStr));
        JSONObject walletData = new JSONObject(walletJsonStr);
        return new String[]{
                walletData.getString("privateKey"),
                walletData.getString("publicKey")
        };
    }
}
