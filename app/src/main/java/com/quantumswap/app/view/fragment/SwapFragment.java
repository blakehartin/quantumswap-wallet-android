package com.quantumswap.app.view.fragment;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

import com.quantumswap.app.R;
import com.quantumswap.app.bridge.BridgeCallback;
import com.quantumswap.app.utils.DexPayloads;
import com.quantumswap.app.utils.GlobalMethods;
import com.quantumswap.app.utils.ReleaseStore;
import com.quantumswap.app.gas.GasKind;
import com.quantumswap.app.view.dialog.TransactionReviewDialog;
import com.quantumswap.app.view.dialog.TxStepsDialog;
import com.quantumswap.app.view.widget.TokenPickerController;
import com.quantumswap.app.viewmodel.JsonViewModel;
import com.quantumswap.app.viewmodel.KeyViewModel;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Swap screen — port of the desktop app's Swap page. Quotes run
 * through the bridge's swapGetAmountsOut / swapCheckPairExists
 * (multi-hop route search); execution is the desktop's two-step
 * approve-then-swap flow with allowance polling, all through the
 * pull-model JS bridge (keys staged, never in the script string).
 */
public class SwapFragment extends Fragment {

    private static final String TAG = "SwapFragment";

    private OnSwapCompleteListener mListener;

    private JsonViewModel jsonViewModel;
    private String walletAddress;

    private static final long QUOTE_DEBOUNCE_MS = 400;

    private TokenPickerController fromPicker;
    private TokenPickerController toPicker;
    private EditText amountInEditText;
    private EditText amountOutEditText;
    private EditText slippageEditText;
    private TextView routeTextView;
    private TextView statusTextView;
    private TextView fromBalanceTextView;
    private TextView toBalanceTextView;
    private View fromBoxView;
    private View toBoxView;
    private View flipRowView;
    private Button nextButton;
    private ProgressBar progress;

    private String lastQuotedAmountOut;
    private boolean flowInFlight;
    // Guards the bidirectional quote wiring (desktop swapQuantityUpdating):
    // programmatic writes to one amount field must not re-trigger the
    // opposite quote.
    private boolean syncingAmounts;
    private Runnable pendingQuote;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static SwapFragment newInstance() {
        return new SwapFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.swap_fragment, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        String languageKey = getArguments().getString("languageKey");
        walletAddress = getArguments().getString("walletAddress");
        jsonViewModel = new JsonViewModel(getContext(), languageKey);

        ImageButton backArrow = view.findViewById(R.id.imageButton_swap_back_arrow);
        TextView title = view.findViewById(R.id.textView_swap_title);
        TextView releaseBanner = view.findViewById(R.id.textView_swap_release_banner);
        TextView fromLabel = view.findViewById(R.id.textView_swap_from_label);
        TextView toLabel = view.findViewById(R.id.textView_swap_to_label);
        TextView slippageLabel = view.findViewById(R.id.textView_swap_slippage_label);
        TextView fromBalanceLabel = view.findViewById(R.id.textView_swap_from_balance_label);
        TextView toBalanceLabel = view.findViewById(R.id.textView_swap_to_balance_label);
        fromBalanceTextView = view.findViewById(R.id.textView_swap_from_balance);
        toBalanceTextView = view.findViewById(R.id.textView_swap_to_balance);
        fromBoxView = view.findViewById(R.id.linearLayout_swap_from_box);
        toBoxView = view.findViewById(R.id.linearLayout_swap_to_box);
        flipRowView = view.findViewById(R.id.linearLayout_swap_flip);
        ImageButton flipButton = view.findViewById(R.id.imageButton_swap_flip);
        amountInEditText = view.findViewById(R.id.editText_swap_amount_in);
        amountOutEditText = view.findViewById(R.id.editText_swap_amount_out);
        slippageEditText = view.findViewById(R.id.editText_swap_slippage);
        routeTextView = view.findViewById(R.id.textView_swap_route);
        statusTextView = view.findViewById(R.id.textView_swap_status);
        nextButton = view.findViewById(R.id.button_swap_next);
        progress = view.findViewById(R.id.progress_swap);

        title.setText(jsonViewModel.lang("swap", "Swap"));
        fromLabel.setText(jsonViewModel.lang("swap-from-token", "From token"));
        toLabel.setText(jsonViewModel.lang("swap-to-token", "To token"));
        slippageLabel.setText(jsonViewModel.lang("slippage", "Slippage"));
        String balanceLabel = jsonViewModel.lang("balance", "Balance") + ":";
        fromBalanceLabel.setText(balanceLabel);
        toBalanceLabel.setText(balanceLabel);
        // Desktop: no labels over the amount fields, placeholders only.
        amountInEditText.setHint(jsonViewModel.lang(
                "swap-from-token-quantity", "From token quantity"));
        amountOutEditText.setHint(jsonViewModel.lang(
                "swap-to-token-quantity", "To token quantity"));
        nextButton.setText(jsonViewModel.lang("next", "Next"));

        // Custom-release banner (desktop custom-release-banner-prefix):
        // a visible reminder that swaps run against user-supplied
        // contracts, not the built-in release.
        ReleaseStore.Release active = ReleaseStore.readActive(KeyViewModel.getSecureStorage());
        if (!active.builtin) {
            releaseBanner.setText(jsonViewModel.lang(
                    "custom-release-banner-prefix", "Custom release contracts: ") + active.name);
            releaseBanner.setVisibility(View.VISIBLE);
        }

        String customLabel = jsonViewModel.lang("custom-contract-address", "Custom...");
        // Desktop flow: neither side is preselected - both triggers
        // read "Select token" until the user picks. "To" lists the full
        // recognized allow-list even when the account holds none of a
        // token (you swap TO tokens you do not own yet).
        fromPicker = new TokenPickerController(getContext(),
                (Button) view.findViewById(R.id.spinner_swap_from),
                (EditText) view.findViewById(R.id.editText_swap_from_custom),
                walletAddress, customLabel, false, false);
        toPicker = new TokenPickerController(getContext(),
                (Button) view.findViewById(R.id.spinner_swap_to),
                (EditText) view.findViewById(R.id.editText_swap_to_custom),
                walletAddress, customLabel, true, false);
        fromPicker.setOnChanged(this::onTokensChanged);
        toPicker.setOnChanged(this::onTokensChanged);

        // Desktop: tapping a balance value fills that side's quantity.
        fromBalanceTextView.setOnClickListener(v -> {
            setAmountSilently(amountInEditText, fromBalanceTextView.getText().toString());
            scheduleQuote(true);
        });
        toBalanceTextView.setOnClickListener(v -> {
            setAmountSilently(amountOutEditText, toBalanceTextView.getText().toString());
            scheduleQuote(false);
        });

        // Bidirectional debounced quoting (desktop 400ms debounce).
        amountInEditText.addTextChangedListener(quoteWatcher(true));
        amountOutEditText.addTextChangedListener(quoteWatcher(false));

        flipButton.setOnClickListener(v -> flipTokens());
        backArrow.setOnClickListener(v -> mListener.onSwapCompleteByBackArrow());
        nextButton.setOnClickListener(v -> onNextClick());

        // Desktop parity: the Swap surface is still in early-phase
        // testing; warn once per screen open. Uses the shared Yes/No
        // card dialog (desktop modalYesNoDialog: red-glass No +
        // teal-glass Yes), not the platform OK/Cancel text buttons.
        View warnView = LayoutInflater.from(getContext())
                .inflate(R.layout.yes_no_dialog, null);
        ((TextView) warnView.findViewById(R.id.textView_yes_no_message))
                .setText(jsonViewModel.lang("swapEarlyPhaseWarn",
                        "This is a feature still in early phases of testing. Do you want to continue?"));
        Button yesButton = warnView.findViewById(R.id.button_yes_no_yes);
        Button noButton = warnView.findViewById(R.id.button_yes_no_no);
        yesButton.setText(jsonViewModel.lang("yes", "Yes"));
        noButton.setText(jsonViewModel.lang("no", "No"));
        final AlertDialog warnDialog = new AlertDialog.Builder(getContext())
                .setView(warnView)
                .setCancelable(false)
                .create();
        if (warnDialog.getWindow() != null) {
            warnDialog.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(
                            android.graphics.Color.TRANSPARENT));
        }
        yesButton.setOnClickListener(v -> warnDialog.dismiss());
        noButton.setOnClickListener(v -> {
            warnDialog.dismiss();
            mListener.onSwapCompleteByBackArrow();
        });
        warnDialog.show();
    }

    // ---------------------------------------------------------------
    // Desktop-parity form flow: token change -> route check; amount
    // typing -> debounced bidirectional quote; Next -> validate + swap.
    // ---------------------------------------------------------------

    /** Desktop updateSwapScreenInfo(): clear amounts/route, refresh
     *  balances, gate the quantity boxes + flip on both sides being
     *  selected, then route-check when they are. */
    private void onTokensChanged() {
        lastQuotedAmountOut = null;
        setAmountSilently(amountInEditText, "");
        setAmountSilently(amountOutEditText, "");
        routeTextView.setVisibility(View.GONE);
        updateBalances();
        String from = fromPicker.getTokenValue();
        String to = toPicker.getTokenValue();
        boolean ready = !from.isEmpty() && !to.isEmpty();
        fromBoxView.setVisibility(ready ? View.VISIBLE : View.GONE);
        toBoxView.setVisibility(ready ? View.VISIBLE : View.GONE);
        flipRowView.setVisibility(ready ? View.VISIBLE : View.GONE);
        if (!ready || from.equalsIgnoreCase(to)) return;
        setBusy(true);
        resolveMeta(fromPicker, () -> resolveMeta(toPicker, () -> fetchRoute(true)));
    }

    private void updateBalances() {
        fromBalanceTextView.setText(TokenPickerController
                .balanceForValue(walletAddress, fromPicker.getTokenValue()));
        toBalanceTextView.setText(TokenPickerController
                .balanceForValue(walletAddress, toPicker.getTokenValue()));
    }

    /** Desktop flipSwapTokens(): exchange the two sides' selections,
     *  carry the old To amount into From, and re-quote. */
    private void flipTokens() {
        String previousToAmount = text(amountOutEditText);
        Object fromSel = fromPicker.captureSelection();
        Object toSel = toPicker.captureSelection();
        fromPicker.restoreSelection(toSel);
        toPicker.restoreSelection(fromSel);
        onTokensChanged();
        if (!previousToAmount.isEmpty()) {
            setAmountSilently(amountInEditText, previousToAmount);
            scheduleQuote(true);
        }
    }

    private android.text.TextWatcher quoteWatcher(final boolean fromSide) {
        return new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                if (syncingAmounts) return;
                scheduleQuote(fromSide);
            }
        };
    }

    private void setAmountSilently(EditText field, String value) {
        syncingAmounts = true;
        try {
            field.setText(value);
        } finally {
            syncingAmounts = false;
        }
    }

    private void scheduleQuote(final boolean fromChanged) {
        if (pendingQuote != null) mainHandler.removeCallbacks(pendingQuote);
        pendingQuote = () -> runQuote(fromChanged);
        mainHandler.postDelayed(pendingQuote, QUOTE_DEBOUNCE_MS);
    }

    private void runQuote(final boolean fromChanged) {
        String from = fromPicker.getTokenValue();
        String to = toPicker.getTokenValue();
        if (from.isEmpty() || to.isEmpty() || from.equalsIgnoreCase(to)) return;
        String amount = text(fromChanged ? amountInEditText : amountOutEditText);
        if (amount.isEmpty() || !amount.matches("\\d*\\.?\\d+")
                || Double.parseDouble(amount) <= 0) {
            setAmountSilently(fromChanged ? amountOutEditText : amountInEditText, "");
            lastQuotedAmountOut = null;
            return;
        }
        setBusy(true);
        resolveMeta(fromPicker, () -> resolveMeta(toPicker, () -> doQuote(fromChanged)));
    }

    /** Resolve decimals/symbol for a custom contract entry via the
     *  bridge before quoting; no-op for Q / cached tokens. */
    private void resolveMeta(final TokenPickerController picker, final Runnable onDone) {
        if (!picker.needsMetadata()) {
            onDone.run();
            return;
        }
        try {
            final String addr = picker.getTokenValue();
            JSONObject payload = DexPayloads.base();
            payload.put("contractAddress", addr);
            payload.put("ownerAddress", walletAddress);
            KeyViewModel.getBridge().dexCallAsync("swapGetTokenMetadata", payload,
                    uiCallback(data -> {
                        picker.setResolvedMeta(
                                data.optString("contractAddress", addr),
                                data.optString("symbol", ""),
                                data.optInt("decimals", 18));
                        onDone.run();
                    }));
        } catch (Exception e) {
            failFlow(e.getMessage());
        }
    }

    /** One quote leg: from-amount -> to-amount (swapGetAmountsOut) or
     *  to-amount -> from-amount (swapGetAmountsIn), desktop
     *  updateToQuantityFromFrom / updateFromQuantityFromTo. */
    private void doQuote(final boolean fromChanged) {
        try {
            JSONObject payload = DexPayloads.base();
            payload.put("fromTokenValue", fromPicker.getTokenValue());
            payload.put("toTokenValue", toPicker.getTokenValue());
            payload.put("fromDecimals", fromPicker.getDecimals());
            payload.put("toDecimals", toPicker.getDecimals());
            if (fromChanged) {
                payload.put("amountIn", text(amountInEditText));
                KeyViewModel.getBridge().dexCallAsync("swapGetAmountsOut", payload,
                        uiCallback(data -> {
                            setBusy(false);
                            lastQuotedAmountOut = data.optString("amountOut", "");
                            setAmountSilently(amountOutEditText, lastQuotedAmountOut);
                        }));
            } else {
                payload.put("amountOut", text(amountOutEditText));
                KeyViewModel.getBridge().dexCallAsync("swapGetAmountsIn", payload,
                        uiCallback(data -> {
                            setBusy(false);
                            lastQuotedAmountOut = text(amountOutEditText);
                            setAmountSilently(amountInEditText,
                                    data.optString("amountIn", ""));
                        }));
            }
        } catch (Exception e) {
            failFlow(e.getMessage());
        }
    }

    private void fetchRoute(final boolean alertWhenMissing) {
        try {
            JSONObject payload = DexPayloads.base();
            payload.put("fromTokenValue", fromPicker.getTokenValue());
            payload.put("toTokenValue", toPicker.getTokenValue());
            KeyViewModel.getBridge().dexCallAsync("swapCheckPairExists", payload,
                    uiCallback(data -> {
                        setBusy(false);
                        JSONArray path = data.optJSONArray("path");
                        JSONArray symbols = data.optJSONArray("pathSymbols");
                        if (!data.optBoolean("exists", false) || path == null) {
                            routeTextView.setVisibility(View.GONE);
                            if (alertWhenMissing) {
                                GlobalMethods.ShowErrorDialog(getContext(),
                                        jsonViewModel.getErrorTitleByLangValues(),
                                        jsonViewModel.lang("swap-no-pair",
                                                "No swap route exists between these two tokens (max 3 hops)"));
                            }
                            return;
                        }
                        StringBuilder sb = new StringBuilder(
                                jsonViewModel.lang("swap-route", "Route")).append(": ");
                        for (int i = 0; i < path.length(); i++) {
                            if (i > 0) sb.append(" > ");
                            String sym = symbols == null ? null : symbols.optString(i, null);
                            String addr = path.optString(i, "");
                            sb.append(sym != null && !sym.isEmpty() && !"null".equals(sym)
                                    ? sanitizeSymbol(sym) : shortAddr(addr));
                        }
                        routeTextView.setText(sb.toString());
                        routeTextView.setVisibility(View.VISIBLE);
                    }));
        } catch (Exception e) {
            failFlow(e.getMessage());
        }
    }

    // ---------------------------------------------------------------
    // Approve + swap
    // ---------------------------------------------------------------

    /** Desktop onSwapNextClick: post-click validation (button is never
     *  disabled), then the allowance check decides the step plan and
     *  the tx-steps dialog (desktop #modalTxSteps) drives
     *  [Approve FROM] -> [Swap FROM -> TO]. */
    private void onNextClick() {
        final String amountIn = text(amountInEditText);
        if (!validateInputs(amountIn)) return;
        if (lastQuotedAmountOut == null || lastQuotedAmountOut.isEmpty()) {
            lastQuotedAmountOut = text(amountOutEditText);
        }
        if (flowInFlight) return;
        setBusy(true);
        resolveMeta(fromPicker, () -> resolveMeta(toPicker, this::checkAllowanceThenSteps));
    }

    private void checkAllowanceThenSteps() {
        try {
            JSONObject payload = DexPayloads.base();
            payload.put("fromTokenValue", fromPicker.getTokenValue());
            payload.put("fromDecimals", fromPicker.getDecimals());
            payload.put("requiredAmount", text(amountInEditText));
            payload.put("ownerAddress", walletAddress);
            KeyViewModel.getBridge().dexCallAsync("swapCheckAllowance", payload,
                    uiCallback(data -> {
                        setBusy(false);
                        showStepsDialog(!data.optBoolean("sufficient", false));
                    }));
        } catch (Exception e) {
            failFlow(e.getMessage());
        }
    }

    /** Desktop createSwapWorkflowStepPlan: optional "Approve FROM",
     *  always "Swap FROM -> TO". Each step estimates its own gas,
     *  reviews (fields + "i agree" + password) and confirms via the
     *  scan-API status poll inside {@link TxStepsDialog}. */
    private void showStepsDialog(boolean needsApproval) {
        final String fromSym = sanitizeSymbol(fromPicker.getSymbol());
        final String toSym = sanitizeSymbol(toPicker.getSymbol());
        final String amountIn = text(amountInEditText);
        final String amountOut = lastQuotedAmountOut == null ? "" : lastQuotedAmountOut;
        final ReleaseStore.Release release = ReleaseStore.readActive(KeyViewModel.getSecureStorage());
        final String fromContract = resolveTokenContract(fromPicker.getTokenValue(), release);
        final String toContract = resolveTokenContract(toPicker.getTokenValue(), release);
        final boolean fromNative = "Q".equals(fromPicker.getTokenValue());

        // Desktop buildSwapReview: base rows shared by both steps.
        String routeSuffix = "";
        if (routeTextView.getVisibility() == View.VISIBLE) {
            String route = routeTextView.getText().toString();
            int idx = route.indexOf(": ");
            if (idx >= 0 && route.indexOf(" > ") > 0) {
                routeSuffix = " (" + route.substring(idx + 2).replace(" > ", " -> ") + ")";
            }
        }
        TransactionReviewDialog.ReviewSpec base = new TransactionReviewDialog.ReviewSpec()
                .action(jsonViewModel.lang("swap", "Swap") + " " + fromSym + " "
                        + jsonViewModel.lang("swap-for", "for") + " " + toSym + routeSuffix)
                .fromTokenContract(fromContract)
                .toTokenContract(toContract)
                .fromAddress(walletAddress)
                .toAddress(release.router)
                .quantityValue(fromNative ? amountIn : "0")
                .tokenQuantityValue(amountIn + " " + fromSym + " "
                        + jsonViewModel.lang("swap-for", "for") + " " + amountOut + " " + toSym)
                .networkText(TransactionReviewDialog.networkText(jsonViewModel));

        java.util.List<TxStepsDialog.Step> steps = new java.util.ArrayList<>();
        if (needsApproval) {
            TransactionReviewDialog.ReviewSpec approveReview = new TransactionReviewDialog.ReviewSpec()
                    .action(jsonViewModel.lang("approve", "Approve") + " " + fromSym)
                    .fromTokenContractLabelKey("approval-token-contract")
                    .toTokenContract(TransactionReviewDialog.HIDE)
                    .toAddress(fromContract)
                    .quantityValue("0")
                    .tokenQuantityLabelKey("approval-token-quantity")
                    .tokenQuantityValue(amountIn + " " + fromSym);
            steps.add(new TxStepsDialog.Step(
                    jsonViewModel.lang("approve", "Approve") + " " + fromSym,
                    GasKind.APPROVE, true,
                    () -> {
                        JSONObject p = new JSONObject();
                        p.put("fromTokenValue", fromPicker.getTokenValue());
                        p.put("fromDecimals", fromPicker.getDecimals());
                        p.put("amount", amountIn);
                        return p;
                    },
                    approveReview,
                    (gasLimit, credentials, chain, cb) -> {
                        try {
                            JSONObject payload = DexPayloads.withKeys(getContext(),
                                    credentials.privateKeyBase64, credentials.publicKeyBase64);
                            TxStepsDialog.overlay(payload, chain);
                            payload.put("fromTokenValue", fromPicker.getTokenValue());
                            payload.put("fromDecimals", fromPicker.getDecimals());
                            payload.put("amount", amountIn);
                            payload.put("gasLimit", gasLimit);
                            KeyViewModel.getBridge().dexCallAsync("swapSubmitApproval", payload,
                                    stepCallback(cb));
                        } catch (Exception e) {
                            cb.fail(sanitizeError(e.getMessage()));
                        }
                    }));
        }
        steps.add(new TxStepsDialog.Step(
                jsonViewModel.lang("swap", "Swap") + " " + fromSym + " -> " + toSym,
                GasKind.SWAP, true,
                () -> {
                    JSONObject p = new JSONObject();
                    putSwapArgs(p);
                    p.put("recipientAddress", walletAddress);
                    return p;
                },
                null,
                (gasLimit, credentials, chain, cb) -> {
                    try {
                        JSONObject payload = DexPayloads.withKeys(getContext(),
                                credentials.privateKeyBase64, credentials.publicKeyBase64);
                        TxStepsDialog.overlay(payload, chain);
                        putSwapArgs(payload);
                        payload.put("recipientAddress", walletAddress);
                        payload.put("gasLimit", gasLimit);
                        KeyViewModel.getBridge().dexCallAsync("swapSubmitSwap", payload,
                                stepCallback(cb));
                    } catch (Exception e) {
                        cb.fail(sanitizeError(e.getMessage()));
                    }
                }));
        flowInFlight = true;
        stepsDialog = new TxStepsDialog(getActivity(), jsonViewModel, walletAddress,
                jsonViewModel.lang("swap", "Swap"), base, steps,
                null,
                () -> {
                    stepsDialog = null;
                    flowInFlight = false;
                    lastQuotedAmountOut = null;
                    if (getView() == null) return;
                    setAmountSilently(amountInEditText, "");
                    setAmountSilently(amountOutEditText, "");
                    updateBalances();
                });
        stepsDialog.show();
    }

    private TxStepsDialog stepsDialog;

    @Override
    public void onDestroyView() {
        if (stepsDialog != null) { stepsDialog.dismiss(); stepsDialog = null; }
        super.onDestroyView();
    }

    /** "Q" -> the active release's wrapped-Q contract (what the bridge
     *  maps it to); otherwise the 0x contract address as-is. */
    private static String resolveTokenContract(String tokenValue, ReleaseStore.Release release) {
        if (tokenValue == null) return "";
        return "Q".equals(tokenValue) ? release.wq : tokenValue;
    }

    /** Bridge submit result -> step submitted(txHash) / fail(message),
     *  on the main thread. */
    private BridgeCallback stepCallback(final TxStepsDialog.RunCallback cb) {
        return new BridgeCallback() {
            @Override public void onResult(final String jsonResult) {
                mainHandler.post(() -> {
                    try {
                        JSONObject data = new JSONObject(jsonResult).getJSONObject("data");
                        String hash = data.optString("txHash", "");
                        if (hash.isEmpty()) throw new IllegalStateException("No transaction hash returned");
                        cb.submitted(hash);
                    } catch (Exception e) {
                        cb.fail(sanitizeError(e.getMessage()));
                    }
                });
            }
            @Override public void onError(final String error) {
                mainHandler.post(() -> cb.fail(sanitizeError(error)));
            }
        };
    }

    private void putSwapArgs(JSONObject payload) throws Exception {
        payload.put("fromTokenValue", fromPicker.getTokenValue());
        payload.put("toTokenValue", toPicker.getTokenValue());
        payload.put("fromDecimals", fromPicker.getDecimals());
        payload.put("toDecimals", toPicker.getDecimals());
        payload.put("amountIn", text(amountInEditText));
        payload.put("lastChanged", "from");
        payload.put("slippagePercent", slippagePercent());
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private boolean validateInputs(String amountIn) {
        String from = fromPicker.getTokenValue();
        String to = toPicker.getTokenValue();
        if (from.isEmpty() || to.isEmpty() || from.equalsIgnoreCase(to)) {
            GlobalMethods.ShowErrorDialog(getContext(),
                    jsonViewModel.getErrorTitleByLangValues(),
                    from.isEmpty() || to.isEmpty()
                            ? jsonViewModel.lang("select-token", "Select token")
                            : jsonViewModel.err("identicalTokens",
                                    "From and To tokens must differ."));
            return false;
        }
        String amountOut = text(amountOutEditText);
        if (amountOut.isEmpty() || !amountOut.matches("\\d*\\.?\\d+")
                || Double.parseDouble(amountOut) <= 0) {
            GlobalMethods.ShowErrorDialog(getContext(),
                    jsonViewModel.getErrorTitleByLangValues(),
                    jsonViewModel.err("invalidQuantity", "Enter a valid quantity."));
            return false;
        }
        if (amountIn.isEmpty() || !amountIn.matches("\\d*\\.?\\d+")
                || Double.parseDouble(amountIn) <= 0) {
            GlobalMethods.ShowErrorDialog(getContext(),
                    jsonViewModel.getErrorTitleByLangValues(),
                    jsonViewModel.err("invalidQuantity", "Enter a valid quantity."));
            return false;
        }
        return true;
    }

    private double slippagePercent() {
        try {
            double v = Double.parseDouble(text(slippageEditText));
            return Math.max(0, Math.min(100, v));
        } catch (Exception e) {
            return 1;
        }
    }

    /** Symbols are untrusted on-chain strings; strip control chars and
     *  clamp length before rendering (mirrors desktop sanitization). */
    private static String sanitizeSymbol(String s) {
        if (s == null) return "";
        String cleaned = s.replaceAll("[\\p{Cntrl}]", "");
        return cleaned.length() > 20 ? cleaned.substring(0, 20) : cleaned;
    }

    private static String shortAddr(String addr) {
        if (addr == null) return "";
        return addr.length() > 14
                ? addr.substring(0, 8) + "..." + addr.substring(addr.length() - 4) : addr;
    }

    private static String text(EditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        nextButton.setEnabled(!busy);
    }

    private void setStatus(String message) {
        statusTextView.setText(message);
        statusTextView.setVisibility(View.VISIBLE);
    }

    private void clearStatus() {
        statusTextView.setVisibility(View.GONE);
    }

    private void failFlow(String error) {
        flowInFlight = false;
        setBusy(false);
        clearStatus();
        if (error != null && !error.isEmpty() && getContext() != null) {
            GlobalMethods.ShowErrorDialog(getContext(),
                    jsonViewModel.getErrorTitleByLangValues(),
                    jsonViewModel.getErrorOccurredByLangValues() + sanitizeError(error));
        }
    }

    /** Error strings can carry bridge/SDK internals; clamp length and
     *  strip control characters before display. */
    private static String sanitizeError(String s) {
        if (s == null) return "";
        String cleaned = s.replaceAll("[\\p{Cntrl}]", " ");
        return cleaned.length() > 300 ? cleaned.substring(0, 300) : cleaned;
    }

    /** Wrap a data-consumer into a BridgeCallback that unwraps the
     *  {success, data} envelope on the UI thread and routes errors
     *  into failFlow. */
    private BridgeCallback uiCallback(final DataConsumer onData) {
        return new BridgeCallback() {
            @Override
            public void onResult(final String jsonResult) {
                mainHandler.post(() -> {
                    if (getActivity() == null) return;
                    try {
                        JSONObject result = new JSONObject(jsonResult);
                        onData.accept(result.getJSONObject("data"));
                    } catch (Exception e) {
                        failFlow(e.getMessage());
                    }
                });
            }

            @Override
            public void onError(final String error) {
                mainHandler.post(() -> {
                    if (getActivity() == null) return;
                    failFlow(error);
                });
            }
        };
    }

    private interface DataConsumer {
        void accept(JSONObject data) throws Exception;
    }

    public interface OnSwapCompleteListener {
        void onSwapCompleteByBackArrow();
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        try {
            this.mListener = (OnSwapCompleteListener) context;
        } catch (final ClassCastException e) {
            throw new ClassCastException(context.toString() + " ");
        }
    }
}
