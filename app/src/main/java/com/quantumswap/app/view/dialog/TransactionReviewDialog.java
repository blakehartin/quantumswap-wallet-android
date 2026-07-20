package com.quantumswap.app.view.dialog;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.quantumswap.app.utils.GlobalMethods;
import com.quantumswap.app.viewmodel.JsonViewModel;

import java.util.Locale;

/**
 * Read-only review of a pending Send transaction. Presented after the
 * user taps Send and the destination address has passed bridge address
 * validation, BEFORE the unlock-password dialog, so the user can
 * sanity-check the From / To / amount / network / contract pairing
 * before committing to a strongbox decrypt.
 * <p>Design notes</p>
 * <ul>
 *   <li>This dialog is the user's last user-comprehensible chance to
 *       abort a transaction before scrypt unlocks the strongbox and a
 *       key binds the signature.</li>
 *   <li>Mixed-case checksum capitalization for both From and To
 *       addresses is computed by the caller (the bridge layer);
 *       this dialog renders the strings as-passed in monospace so a
 *       single-character typo is visually obvious.</li>
 *   <li>Network name + chain-id label so the user can see exactly
 *       which chain they are signing for. The chain-id is the same
 *       value the wallet binds at submit time.</li>
 *   <li>"I agree" gate: OK is honoured only when the trimmed lower-case
 *       contents of the agreement field equal the localized "I agree"
 *       literal (with English fallback). Otherwise the dialog presents
 *       a non-blocking inline warning and stays on screen so the user
 *       can either type correctly or press Cancel.</li>
 *   <li>Contract address row appears ONLY for token sends (caller
 *       passes a non-null, non-empty {@code contractAddress}). For
 *       native QC sends the row is suppressed entirely.</li>
 * </ul>
 * <p>Why a programmatic layout instead of XML</p>
 * Keeps the dialog co-located with its logic so a reviewer reading
 * this file sees both the row composition and the agree-gate check
 * without jumping to a layout XML; matches the approach used by
 * {@link WaitDialog}.
 * <p>iOS counterpart:
 * {@code QuantumSwapWallet/Dialogs/TransactionReviewDialogViewController.swift}.
 */
public final class TransactionReviewDialog {

    public interface OnConfirm {
        void onConfirm();
    }

    public interface OnCancel {
        void onCancel();
    }

    private TransactionReviewDialog() { }

     /**
     * @param ctx              host activity context
     * @param vm               localization view model used to look up
     *                         all human-readable labels
     * @param assetText        primary asset row value
     *                         (e.g. "QuantumCoin" or "HSN - Heisen")
     * @param contractAddress  contract address; pass {@code null} or
     *                         empty for native sends to suppress the
     *                         contract row
     * @param fromAddress      sender 0x... (already mixed-case
     *                         checksummed by caller)
     * @param toAddress        recipient 0x... (already mixed-case
     *                         checksummed by caller)
     * @param amountText       human-readable amount (e.g. "1.5")
     * @param networkName      human network name (may be empty)
     * @param chainId          chain id integer
     */
    public static AlertDialog show(final Context ctx,
                                    final JsonViewModel vm,
                                    String assetText,
                                    String contractAddress,
                                    String fromAddress,
                                    String toAddress,
                                    String amountText,
                                    String networkName,
                                    int chainId,
                                    final OnConfirm onConfirm,
                                    final OnCancel onCancel) {
        ScrollView scroll = new ScrollView(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 18);
        root.setPadding(pad, pad, pad, pad);
        // Opaque rounded card. Without this the dialog window is set
        // transparent below and the AlertDialogLayout behind us is
        // hollow, leaving the form rendered directly over whatever
        // sat behind the dialog. center_container is the same shape
        // used by the unlock + network-select dialogs so the visual
        // language stays consistent and theme-portable (light/dark).
        root.setBackgroundResource(
                com.quantumswap.app.R.drawable.center_container);
        scroll.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        addBody(root, ctx, safe(vm.getReviewTransactionPromptByLangValues(),
                "Please review your transaction request to be sent:"),
                /*bold=*/ true);
        addRow(root, ctx,
                safe(vm.getWhatIsBeingSentByLangValues(), "What is being sent?"),
                assetText,
                /*mono=*/ false,
                /*valueColor=*/ 0);

        if (contractAddress != null && !contractAddress.isEmpty()) {
            addRow(root, ctx,
                    safe(vm.getContractAddressByLangValues(), "Contract address:"),
                    contractAddress,
                    /*mono=*/ true,
                    0);
        }

        addRow(root, ctx,
                safe(vm.getFromAddressByLangValues(), "From Address") + ":",
                fromAddress,
                /*mono=*/ true,
                0);
        addRow(root, ctx,
                safe(vm.getToAddressByLangValues(), "To Address") + ":",
                toAddress,
                /*mono=*/ true,
                0);
        addRow(root, ctx,
                safe(vm.getSendQuantityByLangValues(), "Send quantity") + ":",
                amountText,
                /*mono=*/ false,
                0);

        // Chain-id is concatenated to the human-readable network name
        // so a user with two networks that happen to share a display
        // name (or a typo'd custom network) sees the chain-id their
        // transaction will be bound to.
        String chainSuffix = safe(vm.getChainIdSuffixByLangValues(), "chain");
        String networkValue = (networkName == null || networkName.isEmpty())
                ? "(" + chainSuffix + " " + chainId + ")"
                : networkName + " (" + chainSuffix + " " + chainId + ")";
        addRow(root, ctx,
                safe(vm.getNetworkByLangValues(), "Network") + ":",
                networkValue,
                false,
                0xFF1B7A1B);

        // Agreement row: blue "I agree" literal inside the prompt.
        TextView agreeHeader = new TextView(ctx);
        agreeHeader.setTextSize(13f);
        agreeHeader.setTypeface(Typeface.DEFAULT_BOLD);
        String prefix = safe(vm.getTypeIAgreeToConfirmPrefixByLangValues(), "Type ");
        String literal = safe(vm.getIAgreeLiteralByLangValues(), "I agree");
        String suffix = safe(vm.getTypeIAgreeToConfirmSuffixByLangValues(), " to confirm:");
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(prefix);
        int start = sb.length();
        sb.append(literal);
        int end = sb.length();
        sb.setSpan(new ForegroundColorSpan(0xFF1F6FEB), start, end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(suffix);
        agreeHeader.setText(sb);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 12);
        root.addView(agreeHeader, lp);

        final EditText agreeField = new EditText(ctx);
        agreeField.setSingleLine(true);
        agreeField.setHint(literal);
        agreeField.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        agreeField.setTextSize(15f);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(ctx, 6);
        root.addView(agreeField, alp);

        // Buttons row: Cancel + OK (right-aligned).
        // Styling mirrors the "Next" + "Cancel" pattern used across
        // the app's XML dialogs (e.g. home_start_fragment Next at
        // button_green_selector / colorCommon7, and the network-
        // select dialog Cancel at button_network_cancel_selector /
        // colorCommon7 - see blockchain_network_dialog_fragment.xml
        // lines 92-115 for the dark/light rationale). The OK button
        // gets the same green pill as Next; Cancel gets the constant
        // gray selector that stays #807d7d in both themes with
        // colorCommon7 text giving white-on-gray in light and
        // black-on-gray in dark. Height 43dp matches the XML pattern.
        LinearLayout buttonRow = new LinearLayout(ctx);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(ctx, 14);
        root.addView(buttonRow, blp);

        int btnH = dp(ctx, 43);
        int btnPad = dp(ctx, 5);
        int textColor = androidx.core.content.ContextCompat.getColor(
                ctx, com.quantumswap.app.R.color.colorCommon7);

        Button cancel = new Button(ctx);
        cancel.setText(safe(vm.getCancelByLangValues(), "Cancel"));
        cancel.setAllCaps(false);
        cancel.setTextColor(textColor);
        cancel.setPadding(btnPad, btnPad, btnPad, btnPad);
        cancel.setBackgroundResource(
                com.quantumswap.app.R.drawable.button_network_cancel_selector);

        Button ok = new Button(ctx);
        ok.setText(safe(vm.getOkByLangValues(), "OK"));
        ok.setAllCaps(false);
        ok.setTextColor(textColor);
        ok.setPadding(btnPad, btnPad, btnPad, btnPad);
        ok.setBackgroundResource(
                com.quantumswap.app.R.drawable.button_green_selector);

        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, btnH);
        cancelLp.rightMargin = dp(ctx, 8);
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, btnH);
        buttonRow.addView(cancel, cancelLp);
        buttonRow.addView(ok, okLp);

        final AlertDialog dlg = new AlertDialog.Builder(ctx)
                .setTitle((CharSequence) "")
                .setView(scroll)
                .create();
        dlg.setCancelable(false);
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dlg.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                            | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try { dlg.dismiss(); } catch (Throwable ignore) { }
                if (onCancel != null) onCancel.onCancel();
            }
        });
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String typed = agreeField.getText() == null
                        ? "" : agreeField.getText().toString().trim().toLowerCase(Locale.ROOT);
                String required = literal.trim().toLowerCase(Locale.ROOT);
                String requiredEn = "i agree";
                // Accept localized literal OR the English fallback so a
                // partially-translated bundle never permanently blocks
                // the user.
                if (typed.isEmpty() || (!typed.equals(required) && !typed.equals(requiredEn))) {
                    GlobalMethods.ShowErrorDialog(ctx,
                            safe(vm.getErrorTitleByLangValues(), "Error"),
                            safe(vm.getTypeIAgreeWarningByLangValues(),
                                    "Please type \"" + literal + "\" to confirm."));
                    return;
                }
                try { dlg.dismiss(); } catch (Throwable ignore) { }
                if (onConfirm != null) onConfirm.onConfirm();
            }
        });

        dlg.show();
        agreeField.requestFocus();
        return dlg;
    }

    private static void addBody(LinearLayout parent, Context ctx, String text, boolean bold) {
        TextView tv = new TextView(ctx);
        tv.setTextSize(14f);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setText(text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(ctx, 12);
        parent.addView(tv, lp);
    }

    private static void addRow(LinearLayout parent, Context ctx,
                                String header, String value,
                                boolean mono, int valueColor) {
        TextView h = new TextView(ctx);
        h.setText(header);
        h.setTextSize(13f);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(ctx, 8);
        parent.addView(h, hlp);

        TextView v = new TextView(ctx);
        v.setText(value);
        v.setTextSize(12f);
        if (mono) {
            v.setTypeface(Typeface.MONOSPACE);
            v.setHorizontallyScrolling(false);
        }
        if (valueColor != 0) v.setTextColor(valueColor);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = dp(ctx, 2);
        parent.addView(v, vlp);
    }

    private static int dp(Context ctx, int v) {
        float d = ctx.getResources().getDisplayMetrics().density;
        return Math.round(v * d);
    }

    private static String safe(String s, String fallback) {
        return (s == null || s.isEmpty()) ? fallback : s;
    }
}
