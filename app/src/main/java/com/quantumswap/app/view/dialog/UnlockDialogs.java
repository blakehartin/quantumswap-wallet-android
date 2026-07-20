package com.quantumswap.app.view.dialog;

import android.content.DialogInterface;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.quantumswap.app.R;

/**
 * Helper: thin wrapper around the inline unlock
 * AlertDialog construction so the "isMandatory" semantics are
 * encoded in exactly one place across HomeActivity, WalletsFragment,
 * and SendFragment.
 * Why a wrapper instead of a full DialogFragment refactor:
 *   - The existing inline implementations share password handling,
 *     brute-force gating, scrypt KDF invocation, and decryption.
 *     Pulling all of that into a DialogFragment would risk
 *     subtle behavior drift; this helper isolates the only piece
 *     that close-button work keeps flagging — the close-button
 *     visibility and the back-key handling — into a single
 *     reviewable surface.
 * Mandatory semantics:
 *   - close-button hidden
 *   - back-key swallowed
 *   - dialog non-cancelable on touch-outside
 * Optional (non-mandatory) semantics:
 *   - close-button visible (label sourced from JsonViewModel)
 *   - back-key dismisses the dialog
 *   - dialog cancelable on touch-outside
 * The helper does NOT mutate the password field, listeners, or any
 * decryption pathway — callers continue to wire those exactly as
 * they do today.
 */
public final class UnlockDialogs {

    private UnlockDialogs() { }

     /**
     * Apply mandatory/non-mandatory semantics to an already-built
     * unlock AlertDialog. Safe to call BEFORE or AFTER show(); the
     * close button visibility is read from the inflated layout.
     * @param dialog       the AlertDialog created from
     *                     R.layout.unlock_dialog_fragment
     * @param isMandatory  when true the user must successfully
     *                     unlock to proceed (close hidden, back
     *                     swallowed). When false the user may
     *                     dismiss to abort the action.
     */
    public static void applyMandatory(@NonNull final AlertDialog dialog,
                                      final boolean isMandatory) {
        dialog.setCancelable(!isMandatory);
        dialog.setCanceledOnTouchOutside(!isMandatory);

        if (isMandatory) {
            dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
                @Override
                public boolean onKey(DialogInterface d, int keyCode, KeyEvent event) {
                    return keyCode == KeyEvent.KEYCODE_BACK;
                }
            });
        } else {
            dialog.setOnKeyListener(null);
        }

        Button closeButton = (Button) dialog.findViewById(
                R.id.button_unlock_langValues_close);
        if (closeButton != null) {
            closeButton.setVisibility(isMandatory ? View.GONE : View.VISIBLE);
        }
    }

     /**
     * Convenience: apply mandatory semantics AND wire a no-op close
     * handler when non-mandatory so the close button always dismisses
     * the dialog. Callers may pass an additional onClose hook for
     * post-dismiss cleanup (clearing in-memory password state, etc).
     */
    public static void applyMandatory(@NonNull final AlertDialog dialog,
                                      final boolean isMandatory,
                                      @Nullable final Runnable onClose) {
        applyMandatory(dialog, isMandatory);
        if (!isMandatory) {
            Button closeButton = (Button) dialog.findViewById(
                    R.id.button_unlock_langValues_close);
            if (closeButton != null) {
                closeButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try { dialog.dismiss(); } catch (Throwable ignore) { }
                        if (onClose != null) {
                            try { onClose.run(); } catch (Throwable ignore) { }
                        }
                    }
                });
            }
        }
    }
}
