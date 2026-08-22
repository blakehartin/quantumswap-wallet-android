package com.quantumswap.app.view.dialog;

import android.app.Activity;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.quantumswap.app.R;
import com.quantumswap.app.security.WalletUnlock;
import com.quantumswap.app.utils.GlobalMethods;
import com.quantumswap.app.viewmodel.JsonViewModel;

import java.util.Locale;

/**
 * Port of the desktop transaction review dialog (#modalTransactionReview,
 * dialog.ts showTransactionReviewDialog + txflow.ts requestStepCredentials):
 *
 * <pre>
 *  Please review your transaction request to be sent:
 *  [scrollable] Action · Contract address · From token contract ·
 *               To token contract · From Address · To Address ·
 *               Quantity (Q) · Token quantity · Gas limit ·
 *               Estimated gas fee · Network
 *  [pinned]     Type "i agree" to confirm: [   ]
 *               Enter Quantum Wallet Password [      ] (eye)
 *                                         Cancel   Ok
 * </pre>
 *
 * Optional rows are hidden when their value is null. Row labels can be
 * overridden per transaction kind via lang keys (e.g. the swap approve
 * step relabels "From token contract" to "Approval Token Contract").
 * Ok validates the "i agree" literal (case-insensitive, trimmed) and,
 * when a password is required, verifies it through
 * {@link WalletUnlock} and hands back signing {@link WalletUnlock.Credentials};
 * a wrong password keeps the dialog open with the password refocused.
 */
public final class TransactionReviewDialog {

    /** Sentinel an override can use to HIDE a row the base spec set
     *  (desktop: the approve step passes toTokenContractAddress: null). */
    public static final String HIDE = " HIDE";

    private TransactionReviewDialog() { }

    /** Desktop TransactionReview. All strings nullable (= row hidden). */
    public static final class ReviewSpec {
        public String action;
        public String actionLabelKey;
        public String contractAddress;
        public String fromTokenContract;
        public String fromTokenContractLabelKey;
        public String toTokenContract;
        public String fromAddress;
        public String toAddress;
        public String quantityLabelKey;
        public String quantityValue;
        public String tokenQuantityLabelKey;
        public String tokenQuantityValue;
        public String gasLimit;
        public String gasFeeLabel;
        public String networkText;
        public boolean requirePassword = true;
        /** Contract address row links to /token/ (ERC20 / LP token) instead of /account/. */
        public boolean contractIsToken;

        public ReviewSpec action(String v) { action = v; return this; }
        public ReviewSpec actionLabelKey(String v) { actionLabelKey = v; return this; }
        public ReviewSpec contractAddress(String v) { contractAddress = v; return this; }
        public ReviewSpec fromTokenContract(String v) { fromTokenContract = v; return this; }
        public ReviewSpec fromTokenContractLabelKey(String v) { fromTokenContractLabelKey = v; return this; }
        public ReviewSpec toTokenContract(String v) { toTokenContract = v; return this; }
        public ReviewSpec fromAddress(String v) { fromAddress = v; return this; }
        public ReviewSpec toAddress(String v) { toAddress = v; return this; }
        public ReviewSpec quantityLabelKey(String v) { quantityLabelKey = v; return this; }
        public ReviewSpec quantityValue(String v) { quantityValue = v; return this; }
        public ReviewSpec tokenQuantityLabelKey(String v) { tokenQuantityLabelKey = v; return this; }
        public ReviewSpec tokenQuantityValue(String v) { tokenQuantityValue = v; return this; }
        public ReviewSpec gas(long limit, String feeLabel) {
            gasLimit = String.valueOf(limit);
            gasFeeLabel = feeLabel;
            return this;
        }
        public ReviewSpec networkText(String v) { networkText = v; return this; }
        public ReviewSpec requirePassword(boolean v) { requirePassword = v; return this; }
        public ReviewSpec contractIsToken(boolean v) { contractIsToken = v; return this; }

        public ReviewSpec copy() {
            ReviewSpec c = new ReviewSpec();
            c.action = action; c.actionLabelKey = actionLabelKey;
            c.contractAddress = contractAddress;
            c.fromTokenContract = fromTokenContract;
            c.fromTokenContractLabelKey = fromTokenContractLabelKey;
            c.toTokenContract = toTokenContract;
            c.fromAddress = fromAddress; c.toAddress = toAddress;
            c.quantityLabelKey = quantityLabelKey; c.quantityValue = quantityValue;
            c.tokenQuantityLabelKey = tokenQuantityLabelKey;
            c.tokenQuantityValue = tokenQuantityValue;
            c.gasLimit = gasLimit; c.gasFeeLabel = gasFeeLabel;
            c.networkText = networkText; c.requirePassword = requirePassword;
            c.contractIsToken = contractIsToken;
            return c;
        }

        /** Desktop buildStepReview: shallow merge, override wins
         *  key-by-key; {@link #HIDE} clears a base value. */
        public ReviewSpec mergeOver(ReviewSpec override) {
            ReviewSpec m = copy();
            if (override == null) return m;
            m.action = pick(override.action, m.action);
            m.actionLabelKey = pick(override.actionLabelKey, m.actionLabelKey);
            m.contractAddress = pick(override.contractAddress, m.contractAddress);
            if (override.contractAddress != null) m.contractIsToken = override.contractIsToken;
            m.fromTokenContract = pick(override.fromTokenContract, m.fromTokenContract);
            m.fromTokenContractLabelKey = pick(override.fromTokenContractLabelKey, m.fromTokenContractLabelKey);
            m.toTokenContract = pick(override.toTokenContract, m.toTokenContract);
            m.fromAddress = pick(override.fromAddress, m.fromAddress);
            m.toAddress = pick(override.toAddress, m.toAddress);
            m.quantityLabelKey = pick(override.quantityLabelKey, m.quantityLabelKey);
            m.quantityValue = pick(override.quantityValue, m.quantityValue);
            m.tokenQuantityLabelKey = pick(override.tokenQuantityLabelKey, m.tokenQuantityLabelKey);
            m.tokenQuantityValue = pick(override.tokenQuantityValue, m.tokenQuantityValue);
            m.gasLimit = pick(override.gasLimit, m.gasLimit);
            m.gasFeeLabel = pick(override.gasFeeLabel, m.gasFeeLabel);
            m.networkText = pick(override.networkText, m.networkText);
            return m;
        }

        private static String pick(String override, String base) {
            if (override == null) return base;
            return HIDE.equals(override) ? null : override;
        }
    }

    /** Desktop txReviewNetworkText(): "Name (chain 123123)". */
    public static String networkText(JsonViewModel vm) {
        String name = GlobalMethods.BLOCKCHAIN_NAME == null ? "" : GlobalMethods.BLOCKCHAIN_NAME;
        String suffix = vm.lang("chain-id-suffix", "chain");
        String id = GlobalMethods.NETWORK_ID == null ? "" : GlobalMethods.NETWORK_ID;
        return name.isEmpty() ? "(" + suffix + " " + id + ")" : name + " (" + suffix + " " + id + ")";
    }

    public interface OnCredentials {
        void onCredentials(WalletUnlock.Credentials credentials);
    }

    /** Legacy callbacks (review without password). */

    /**
     * Desktop requestStepCredentials: review + "i agree" + password;
     * the callback receives loaded signing credentials.
     */
    public static AlertDialog showForCredentials(final Activity activity, final JsonViewModel vm,
                                                 final String walletAddress, ReviewSpec spec,
                                                 final OnCredentials onCredentials,
                                                 final Runnable onCancel) {
        ReviewSpec s = spec.copy();
        s.requirePassword = true;
        if (s.networkText == null) s.networkText = networkText(vm);
        if (s.fromAddress == null) s.fromAddress = walletAddress;
        return build(activity, vm, walletAddress, s, onCredentials, onCancel);
    }

    // ---------------------------------------------------------------

    private static AlertDialog build(final Activity activity, final JsonViewModel vm,
                                     final String walletAddress, final ReviewSpec spec,
                                     final OnCredentials onCredentials,
                                     final Runnable onCancel) {
        final Context ctx = activity;
        final int pad = dp(ctx, 18);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundResource(R.drawable.center_container);

        // Pinned prompt.
        TextView prompt = new TextView(ctx);
        prompt.setText(vm.lang("review-transaction-prompt",
                "Please review your transaction request to be sent:"));
        prompt.setTypeface(null, Typeface.BOLD);
        prompt.setTextSize(14);
        prompt.setTextColor(0xFFE0E0E6);
        root.addView(prompt);

        // Scrollable rows (desktop: only the middle block scrolls).
        // Desktop: max-height calc(90vh - 50px) on the modal with only
        // the row block scrolling. The rows block is capped at 42% of
        // the screen AND yields first (layout weight) when the IME
        // shrinks the dialog window (SOFT_INPUT_ADJUST_RESIZE), so the
        // pinned "i agree" / password / buttons always stay visible.
        final int maxScrollPx = (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.42f);
        final ScrollView scroll = new ScrollView(ctx) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int mode = View.MeasureSpec.getMode(heightMeasureSpec);
                int size = View.MeasureSpec.getSize(heightMeasureSpec);
                int cap = mode == View.MeasureSpec.UNSPECIFIED ? maxScrollPx : Math.min(size, maxScrollPx);
                super.onMeasure(widthMeasureSpec,
                        View.MeasureSpec.makeMeasureSpec(Math.max(cap, 0), View.MeasureSpec.AT_MOST));
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout rows = new LinearLayout(ctx);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = dp(ctx, 4);
        root.addView(scroll, slp);

        addRow(rows, ctx, vm.lang(key(spec.actionLabelKey, "action"), "Action"), spec.action, false, 0);
        if (spec.contractAddress != null) {
            addRow(rows, ctx, vm.lang("contract-address", "Contract address"), spec.contractAddress, true, 0,
                    spec.contractIsToken ? com.quantumswap.app.networking.UrlBuilder.blockExplorerTokenUrl(spec.contractAddress)
                            : com.quantumswap.app.networking.UrlBuilder.blockExplorerAccountUrl(spec.contractAddress));
        }
        if (spec.fromTokenContract != null) {
            addRow(rows, ctx, vm.lang(key(spec.fromTokenContractLabelKey, "swap-from-token-contract"),
                    "From token contract"), spec.fromTokenContract, true, 0,
                    com.quantumswap.app.networking.UrlBuilder.blockExplorerTokenUrl(spec.fromTokenContract));
        }
        if (spec.toTokenContract != null) {
            addRow(rows, ctx, vm.lang("swap-to-token-contract", "To token contract"),
                    spec.toTokenContract, true, 0, com.quantumswap.app.networking.UrlBuilder.blockExplorerTokenUrl(spec.toTokenContract));
        }
        if (spec.fromAddress != null) {
            addRow(rows, ctx, vm.lang("from-address", "From Address"), spec.fromAddress, true, 0,
                    com.quantumswap.app.networking.UrlBuilder.blockExplorerAccountUrl(spec.fromAddress));
        }
        if (spec.toAddress != null && !spec.toAddress.isEmpty()) {
            addRow(rows, ctx, vm.lang("to-address", "To Address"), spec.toAddress, true, 0,
                    com.quantumswap.app.networking.UrlBuilder.blockExplorerAccountUrl(spec.toAddress));
        }
        addRow(rows, ctx, vm.lang(key(spec.quantityLabelKey, "send-quantity"), "Quantity (Q)"),
                spec.quantityValue == null ? "0" : spec.quantityValue, false, 0);
        if (spec.tokenQuantityValue != null) {
            addRow(rows, ctx, vm.lang(key(spec.tokenQuantityLabelKey, "token-quantity"), "Token quantity"),
                    spec.tokenQuantityValue, false, 0);
        }
        if (spec.gasLimit != null) {
            addRow(rows, ctx, vm.lang("gas-limit", "Gas limit (gas-units)"), spec.gasLimit, false, 0);
            addRow(rows, ctx, vm.lang("gas-fee", "Estimated gas fee (coins)"),
                    spec.gasFeeLabel == null ? "" : spec.gasFeeLabel, false, 0);
        }
        addRow(rows, ctx, vm.lang("network", "Network"),
                spec.networkText == null ? "" : spec.networkText, false, 0xFF34D399);

        // Pinned: "Type i agree to confirm:" + field.
        String prefix = safe(vm.getTypeIAgreeToConfirmPrefixByLangValues(), "Type ");
        String literal = safe(vm.getIAgreeLiteralByLangValues(), "i agree");
        String suffix = safe(vm.getTypeIAgreeToConfirmSuffixByLangValues(), " to confirm:");
        TextView agreeLabel = new TextView(ctx);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(prefix);
        int start = sb.length();
        sb.append(literal);
        sb.setSpan(new ForegroundColorSpan(0xFF8C71FF), start, sb.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(suffix);
        agreeLabel.setText(sb);
        agreeLabel.setTextSize(13);
        agreeLabel.setTextColor(0xFFE0E0E6);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(ctx, 12);
        root.addView(agreeLabel, alp);

        final EditText agreeField = new EditText(ctx);
        agreeField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        agreeField.setSingleLine(true);
        agreeField.setHint(literal);
        agreeField.setTextSize(14);
        agreeField.setTextColor(0xFFE0E0E6);
        agreeField.setHintTextColor(0xFF9A9AA6);
        agreeField.setBackgroundResource(R.drawable.text_input_selector);
        agreeField.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        root.addView(agreeField, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Pinned: password row (desktop rowTxReviewPassword).
        // Pinned footer: Cancel · Ok (desktop .cancel / .proceed).
        LinearLayout buttonRow = new LinearLayout(ctx);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(ctx, 16);
        root.addView(buttonRow, blp);

        int btnH = dp(ctx, 43);
        int btnPad = dp(ctx, 5);
        final Button cancel = new Button(ctx);
        cancel.setText(safe(vm.getCancelByLangValues(), "Cancel"));
        cancel.setAllCaps(false);
        cancel.setTextColor(0xFFFFFFFF);
        cancel.setPadding(btnPad, btnPad, btnPad, btnPad);
        cancel.setBackgroundResource(R.drawable.button_network_cancel_selector);
        final Button ok = new Button(ctx);
        ok.setText(safe(vm.getOkByLangValues(), "Ok"));
        ok.setAllCaps(false);
        ok.setTextColor(0xFFFFFFFF);
        ok.setPadding(btnPad, btnPad, btnPad, btnPad);
        ok.setBackgroundResource(R.drawable.button_green_selector);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, btnH);
        cancelLp.rightMargin = dp(ctx, 15);
        buttonRow.addView(cancel, cancelLp);
        buttonRow.addView(ok, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, btnH));

        final AlertDialog dlg = new AlertDialog.Builder(ctx)
                .setTitle((CharSequence) "")
                .setView(root)
                .create();
        dlg.setCancelable(false);
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        cancel.setOnClickListener(v -> {
            try { dlg.dismiss(); } catch (Throwable ignore) { }
            if (onCancel != null) onCancel.run();
        });

        ok.setOnClickListener(v -> {
            String typed = agreeField.getText() == null
                    ? "" : agreeField.getText().toString().trim().toLowerCase(Locale.ROOT);
            String required = literal.trim().toLowerCase(Locale.ROOT);
            if (typed.isEmpty() || (!typed.equals(required) && !typed.equals("i agree"))) {
                GlobalMethods.ShowErrorDialog(ctx, safe(vm.getErrorTitleByLangValues(), "Error"),
                        vm.lang("must-agree-to-submit", "Please type \"" + literal + "\" to confirm."));
                return;
            }
            try { dlg.dismiss(); } catch (Throwable ignore) { }
            // Password gate: the shared unlock dialog (same one the
            // app uses elsewhere), then load the signing keys.
            DexUnlockPrompt.show(activity, vm, password -> {
                final WaitDialog.MessageHandle wait = WaitDialog.showMessage(ctx,
                        vm.lang("waitWalletOpen",
                                "Please wait while your wallet is being decrypted and opened. This can take upto a minute."));
                final Context appCtx = activity.getApplicationContext();
                new Thread(() -> {
                    try {
                        final WalletUnlock.Credentials c = WalletUnlock.loadCredentials(appCtx, walletAddress);
                        activity.runOnUiThread(() -> {
                            try { wait.dismiss(); } catch (Throwable ignore) { }
                            if (onCredentials != null) onCredentials.onCredentials(c);
                        });
                    } catch (Exception e) {
                        activity.runOnUiThread(() -> {
                            try { wait.dismiss(); } catch (Throwable ignore) { }
                            GlobalMethods.ShowErrorDialog(ctx,
                                    safe(vm.getErrorTitleByLangValues(), "Error"),
                                    e.getMessage() == null ? "" : e.getMessage());
                            if (onCancel != null) onCancel.run();
                        });
                    }
                }).start();
            }, () -> { if (onCancel != null) onCancel.run(); });
        });

        dlg.show();
        agreeField.requestFocus();
        return dlg;
    }

    private static String key(String override, String fallback) {
        return override == null || override.isEmpty() ? fallback : override;
    }

    private static void addRow(LinearLayout parent, Context ctx, String header, String value,
                               boolean mono, int valueColor) {
        addRow(parent, ctx, header, value, mono, valueColor, null);
    }

    private static void addRow(LinearLayout parent, Context ctx, String header, String value,
                               boolean mono, int valueColor, android.net.Uri link) {
        TextView h = new TextView(ctx);
        h.setText(header);
        h.setTypeface(null, Typeface.BOLD);
        h.setTextSize(13);
        h.setTextColor(0xFFE0E0E6);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(ctx, 8);
        parent.addView(h, hlp);

        TextView v = new TextView(ctx);
        v.setText(value == null ? "" : value);
        v.setTextSize(12);
        v.setTextColor(valueColor != 0 ? valueColor : 0xFFE0E0E6);
        if (mono) v.setTypeface(Typeface.MONOSPACE);
        if (link != null) {
            com.quantumswap.app.view.widget.ExplorerLinks.linkValue(v, value, link);
        } else {
            v.setTextIsSelectable(true);
        }
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = dp(ctx, 2);
        parent.addView(v, vlp);
    }

    private static String safe(String s, String fallback) {
        return s == null || s.isEmpty() ? fallback : s;
    }

    private static int dp(Context ctx, int dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }
}
