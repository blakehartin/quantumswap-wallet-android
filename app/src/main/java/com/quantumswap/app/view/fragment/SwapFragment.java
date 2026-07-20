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
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.quantumswap.app.R;
import com.quantumswap.app.bridge.BridgeCallback;
import com.quantumswap.app.utils.DexPayloads;
import com.quantumswap.app.utils.GlobalMethods;
import com.quantumswap.app.utils.ReleaseStore;
import com.quantumswap.app.view.dialog.DexUnlockPrompt;
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

    private static final int APPROVAL_POLL_MAX_ATTEMPTS = 24;
    private static final long APPROVAL_POLL_INTERVAL_MS = 5000;

    private OnSwapCompleteListener mListener;

    private JsonViewModel jsonViewModel;
    private String walletAddress;

    private TokenPickerController fromPicker;
    private TokenPickerController toPicker;
    private EditText amountInEditText;
    private EditText slippageEditText;
    private TextView amountOutTextView;
    private TextView routeTextView;
    private TextView statusTextView;
    private Button quoteButton;
    private Button swapButton;
    private ProgressBar progress;

    private String lastQuotedAmountOut;
    private boolean flowInFlight;
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
        TextView amountOutLabel = view.findViewById(R.id.textView_swap_amount_out_label);
        TextView slippageLabel = view.findViewById(R.id.textView_swap_slippage_label);
        amountInEditText = view.findViewById(R.id.editText_swap_amount_in);
        slippageEditText = view.findViewById(R.id.editText_swap_slippage);
        amountOutTextView = view.findViewById(R.id.textView_swap_amount_out);
        routeTextView = view.findViewById(R.id.textView_swap_route);
        statusTextView = view.findViewById(R.id.textView_swap_status);
        quoteButton = view.findViewById(R.id.button_swap_quote);
        swapButton = view.findViewById(R.id.button_swap_execute);
        progress = view.findViewById(R.id.progress_swap);

        title.setText(jsonViewModel.lang("swap", "Swap"));
        fromLabel.setText(jsonViewModel.lang("swap-from-token", "From token"));
        toLabel.setText(jsonViewModel.lang("swap-to-token", "To token"));
        amountOutLabel.setText(jsonViewModel.lang("swap-to-quantity", "To quantity"));
        slippageLabel.setText(jsonViewModel.lang("slippage", "Slippage"));
        amountInEditText.setHint(jsonViewModel.lang("swap-from-quantity", "From quantity"));
        quoteButton.setText(jsonViewModel.lang("get-quote", "Get Quote"));
        swapButton.setText(jsonViewModel.lang("swap", "Swap"));

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
        fromPicker = new TokenPickerController(getContext(),
                (Spinner) view.findViewById(R.id.spinner_swap_from),
                (EditText) view.findViewById(R.id.editText_swap_from_custom),
                walletAddress, customLabel);
        toPicker = new TokenPickerController(getContext(),
                (Spinner) view.findViewById(R.id.spinner_swap_to),
                (EditText) view.findViewById(R.id.editText_swap_to_custom),
                walletAddress, customLabel);
        Runnable clearQuote = () -> {
            lastQuotedAmountOut = null;
            amountOutTextView.setText("-");
            routeTextView.setVisibility(View.GONE);
        };
        fromPicker.setOnChanged(clearQuote);
        toPicker.setOnChanged(clearQuote);

        backArrow.setOnClickListener(v -> mListener.onSwapCompleteByBackArrow());
        quoteButton.setOnClickListener(v -> requestQuote());
        swapButton.setOnClickListener(v -> startSwap());

        // Desktop parity: the Swap surface is still in early-phase
        // testing; warn once per screen open.
        new AlertDialog.Builder(getContext())
                .setTitle(jsonViewModel.lang("swap", "Swap"))
                .setMessage(jsonViewModel.lang("swapEarlyPhaseWarn",
                        "This is a feature still in early phases of testing. Do you want to continue?"))
                .setPositiveButton(jsonViewModel.getOkByLangValues(), (d, w) -> d.dismiss())
                .setNegativeButton(jsonViewModel.getCancelByLangValues(),
                        (d, w) -> mListener.onSwapCompleteByBackArrow())
                .setCancelable(false)
                .show();
    }

    // ---------------------------------------------------------------
    // Quote
    // ---------------------------------------------------------------

    private void requestQuote() {
        String amountIn = text(amountInEditText);
        if (!validateInputs(amountIn)) return;
        setBusy(true);
        resolveMeta(fromPicker, () -> resolveMeta(toPicker, this::doQuote));
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

    private void doQuote() {
        try {
            final String amountIn = text(amountInEditText);
            JSONObject payload = DexPayloads.base();
            payload.put("fromTokenValue", fromPicker.getTokenValue());
            payload.put("toTokenValue", toPicker.getTokenValue());
            payload.put("fromDecimals", fromPicker.getDecimals());
            payload.put("toDecimals", toPicker.getDecimals());
            payload.put("amountIn", amountIn);
            KeyViewModel.getBridge().dexCallAsync("swapGetAmountsOut", payload,
                    uiCallback(data -> {
                        lastQuotedAmountOut = data.optString("amountOut", "");
                        amountOutTextView.setText(lastQuotedAmountOut);
                        fetchRoute();
                    }));
        } catch (Exception e) {
            failFlow(e.getMessage());
        }
    }

    private void fetchRoute() {
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

    private void startSwap() {
        final String amountIn = text(amountInEditText);
        if (!validateInputs(amountIn)) return;
        if (lastQuotedAmountOut == null || lastQuotedAmountOut.isEmpty()) {
            // Quote first so the user always confirms against a live
            // number (mirrors the desktop flow where the quote drives
            // the confirmation copy).
            requestQuote();
            return;
        }
        if (flowInFlight) return;

        String message = jsonViewModel.lang("swap-execute-confirm-message",
                        "You are swapping [FROM_AMOUNT] [FROM_SYMBOL] for at least [TO_AMOUNT] [TO_SYMBOL].")
                .replace("[FROM_AMOUNT]", amountIn)
                .replace("[FROM_SYMBOL]", sanitizeSymbol(fromPicker.getSymbol()))
                .replace("[TO_AMOUNT]", minOutForDisplay())
                .replace("[TO_SYMBOL]", sanitizeSymbol(toPicker.getSymbol()));

        new AlertDialog.Builder(getContext())
                .setTitle(jsonViewModel.lang("swap", "Swap"))
                .setMessage(message)
                .setPositiveButton(jsonViewModel.getOkByLangValues(), (d, w) ->
                        DexUnlockPrompt.show(getActivity(), jsonViewModel, this::runSwapFlow))
                .setNegativeButton(jsonViewModel.getCancelByLangValues(), (d, w) -> d.dismiss())
                .show();
    }

    /** Post-unlock: load keys off the main thread, then walk the
     *  allowance -> (approve -> poll) -> swap chain. */
    private void runSwapFlow(String password) {
        flowInFlight = true;
        setBusy(true);
        final Context appCtx = getActivity().getApplicationContext();
        new Thread(() -> {
            try {
                final String[] keys = DexUnlockPrompt.loadWalletKeys(appCtx, walletAddress);
                mainHandler.post(() -> checkAllowanceThen(keys));
            } catch (Exception e) {
                mainHandler.post(() -> failFlow(e.getMessage()));
            }
        }).start();
    }

    private void checkAllowanceThen(final String[] keys) {
        try {
            JSONObject payload = DexPayloads.base();
            payload.put("fromTokenValue", fromPicker.getTokenValue());
            payload.put("fromDecimals", fromPicker.getDecimals());
            payload.put("requiredAmount", text(amountInEditText));
            payload.put("ownerAddress", walletAddress);
            KeyViewModel.getBridge().dexCallAsync("swapCheckAllowance", payload,
                    uiCallback(data -> {
                        if (data.optBoolean("sufficient", false)) {
                            estimateAndSubmitSwap(keys);
                        } else {
                            confirmApproval(keys);
                        }
                    }));
        } catch (Exception e) {
            failFlow(e.getMessage());
        }
    }

    private void confirmApproval(final String[] keys) {
        String message = jsonViewModel.lang("swap-approval-confirm-message",
                        "You are approving [QUANTITY] tokens for use in QuantumSwap.")
                .replace("[QUANTITY]", text(amountInEditText));
        new AlertDialog.Builder(getContext())
                .setTitle(jsonViewModel.lang("approve", "Approve"))
                .setMessage(message)
                .setPositiveButton(jsonViewModel.getOkByLangValues(),
                        (d, w) -> submitApproval(keys))
                .setNegativeButton(jsonViewModel.getCancelByLangValues(), (d, w) -> {
                    d.dismiss();
                    failFlow(null);
                })
                .setCancelable(false)
                .show();
    }

    private void submitApproval(final String[] keys) {
        try {
            setStatus(jsonViewModel.lang("swap-approval-status-wait", "Please wait, checking..."));
            // Gas estimate for the approve; fall back to the token
            // default when estimation reverts (e.g. RPC hiccup).
            JSONObject estimate = DexPayloads.base();
            estimate.put("fromTokenValue", fromPicker.getTokenValue());
            estimate.put("fromDecimals", fromPicker.getDecimals());
            estimate.put("amount", text(amountInEditText));
            estimate.put("fromAddress", walletAddress);
            KeyViewModel.getBridge().dexCallAsync("swapEstimateApproveGas", estimate,
                    new BridgeCallback() {
                        @Override public void onResult(String jsonResult) {
                            long gasLimit = parseGas(jsonResult, 84000L);
                            postSubmitApproval(keys, gasLimit);
                        }
                        @Override public void onError(String error) {
                            postSubmitApproval(keys, 84000L);
                        }
                    });
        } catch (Exception e) {
            failFlow(e.getMessage());
        }
    }

    private void postSubmitApproval(final String[] keys, final long gasLimit) {
        mainHandler.post(() -> {
            try {
                JSONObject payload = DexPayloads.withKeys(getContext(), keys[0], keys[1]);
                payload.put("fromTokenValue", fromPicker.getTokenValue());
                payload.put("fromDecimals", fromPicker.getDecimals());
                payload.put("amount", text(amountInEditText));
                payload.put("gasLimit", gasLimit);
                KeyViewModel.getBridge().dexCallAsync("swapSubmitApproval", payload,
                        uiCallback(data -> {
                            setStatus(jsonViewModel.lang("swap-approval-status-pending",
                                    "Transaction is still pending..."));
                            pollAllowance(keys, 0);
                        }));
            } catch (Exception e) {
                failFlow(e.getMessage());
            }
        });
    }

    private void pollAllowance(final String[] keys, final int attempt) {
        if (attempt >= APPROVAL_POLL_MAX_ATTEMPTS) {
            failFlow(jsonViewModel.lang("swap-approval-may-close",
                    "You may close this dialog, the transaction for approval has already been submitted."));
            return;
        }
        if (attempt > 2) {
            setStatus(jsonViewModel.lang("swap-approval-status-minute",
                    "This can take up to a minute..."));
        }
        mainHandler.postDelayed(() -> {
            if (getActivity() == null) return;
            try {
                JSONObject payload = DexPayloads.base();
                payload.put("fromTokenValue", fromPicker.getTokenValue());
                payload.put("fromDecimals", fromPicker.getDecimals());
                payload.put("requiredAmount", text(amountInEditText));
                payload.put("ownerAddress", walletAddress);
                KeyViewModel.getBridge().dexCallAsync("swapCheckAllowance", payload,
                        new BridgeCallback() {
                            @Override public void onResult(String jsonResult) {
                                mainHandler.post(() -> {
                                    if (getActivity() == null) return;
                                    boolean sufficient = false;
                                    try {
                                        JSONObject result = new JSONObject(jsonResult);
                                        sufficient = result.getJSONObject("data")
                                                .optBoolean("sufficient", false);
                                    } catch (Exception ignore) { }
                                    if (sufficient) {
                                        setStatus(jsonViewModel.lang("swap-approval-completed",
                                                "Token approval completed. You can continue with Swap."));
                                        estimateAndSubmitSwap(keys);
                                    } else {
                                        pollAllowance(keys, attempt + 1);
                                    }
                                });
                            }
                            @Override public void onError(String error) {
                                mainHandler.post(() -> pollAllowance(keys, attempt + 1));
                            }
                        });
            } catch (Exception e) {
                failFlow(e.getMessage());
            }
        }, APPROVAL_POLL_INTERVAL_MS);
    }

    private void estimateAndSubmitSwap(final String[] keys) {
        try {
            setStatus(jsonViewModel.getSubmittingTransactionByLangValues());
            JSONObject estimate = DexPayloads.base();
            putSwapArgs(estimate);
            estimate.put("recipientAddress", walletAddress);
            KeyViewModel.getBridge().dexCallAsync("swapEstimateGas", estimate,
                    new BridgeCallback() {
                        @Override public void onResult(String jsonResult) {
                            submitSwap(keys, parseGas(jsonResult, 300000L));
                        }
                        @Override public void onError(String error) {
                            submitSwap(keys, 300000L);
                        }
                    });
        } catch (Exception e) {
            failFlow(e.getMessage());
        }
    }

    private void submitSwap(final String[] keys, final long gasLimit) {
        mainHandler.post(() -> {
            if (getActivity() == null) return;
            try {
                JSONObject payload = DexPayloads.withKeys(getContext(), keys[0], keys[1]);
                putSwapArgs(payload);
                payload.put("recipientAddress", walletAddress);
                payload.put("gasLimit", gasLimit);
                KeyViewModel.getBridge().dexCallAsync("swapSubmitSwap", payload,
                        uiCallback(data -> {
                            flowInFlight = false;
                            setBusy(false);
                            clearStatus();
                            lastQuotedAmountOut = null;
                            amountOutTextView.setText("-");
                            String txHash = data.optString("txHash", "");
                            new AlertDialog.Builder(getContext())
                                    .setTitle(jsonViewModel.lang("swap", "Swap"))
                                    .setMessage(jsonViewModel.lang("swap-succeeded",
                                            "Swap transaction succeeded.") + "\n\n" + txHash)
                                    .setPositiveButton(jsonViewModel.getOkByLangValues(),
                                            (d, w) -> d.dismiss())
                                    .show();
                        }));
            } catch (Exception e) {
                failFlow(e.getMessage());
            }
        });
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
        if (fromPicker.getTokenValue().equalsIgnoreCase(toPicker.getTokenValue())) {
            GlobalMethods.ShowErrorDialog(getContext(),
                    jsonViewModel.getErrorTitleByLangValues(),
                    jsonViewModel.err("identicalTokens", "From and To tokens must differ."));
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

    /** Display-side min-out: quoted amount reduced by the slippage
     *  tolerance, mirroring the bridge's integer math closely enough
     *  for the confirmation copy (the bridge computes the binding value). */
    private String minOutForDisplay() {
        try {
            java.math.BigDecimal out = new java.math.BigDecimal(lastQuotedAmountOut);
            java.math.BigDecimal pct = java.math.BigDecimal.valueOf(100 - (int) slippagePercent())
                    .divide(java.math.BigDecimal.valueOf(100));
            return out.multiply(pct).stripTrailingZeros().toPlainString();
        } catch (Exception e) {
            return lastQuotedAmountOut == null ? "-" : lastQuotedAmountOut;
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

    private static long parseGas(String jsonResult, long fallback) {
        try {
            JSONObject result = new JSONObject(jsonResult);
            long v = Long.parseLong(result.getJSONObject("data").getString("gasLimit"));
            // Same padding the desktop applies to estimates.
            return Math.max(fallback, (v * 12) / 10);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String text(EditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        quoteButton.setEnabled(!busy);
        swapButton.setEnabled(!busy);
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
