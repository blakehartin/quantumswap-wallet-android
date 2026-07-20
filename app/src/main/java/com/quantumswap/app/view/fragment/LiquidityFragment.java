package com.quantumswap.app.view.fragment;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.quantumswap.app.R;
import com.quantumswap.app.bridge.BridgeCallback;
import com.quantumswap.app.utils.CoinUtils;
import com.quantumswap.app.utils.DexPayloads;
import com.quantumswap.app.utils.GlobalMethods;
import com.quantumswap.app.view.dialog.DexUnlockPrompt;
import com.quantumswap.app.view.widget.TokenPickerController;
import com.quantumswap.app.viewmodel.JsonViewModel;
import com.quantumswap.app.viewmodel.KeyViewModel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigInteger;

/**
 * Liquidity screen — port of the desktop app's Liquidity page: the
 * owner's LP positions (with per-position Remove), and an
 * Add-Liquidity form (addLiquidity / addLiquidityETH via the bridge).
 */
public class LiquidityFragment extends Fragment {

    private static final int POLL_MAX_ATTEMPTS = 24;
    private static final long POLL_INTERVAL_MS = 5000;

    private OnLiquidityCompleteListener mListener;

    private JsonViewModel jsonViewModel;
    private String walletAddress;

    private LinearLayout positionsLayout;
    private TextView noPositionsTextView;
    private TextView statusTextView;
    private ProgressBar progress;
    private ImageButton refreshButton;
    private Button addButton;

    private TokenPickerController tokenAPicker;
    private TokenPickerController tokenBPicker;
    private EditText amountAEditText;
    private EditText amountBEditText;
    private EditText slippageEditText;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static LiquidityFragment newInstance() {
        return new LiquidityFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.liquidity_fragment, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        String languageKey = getArguments().getString("languageKey");
        walletAddress = getArguments().getString("walletAddress");
        jsonViewModel = new JsonViewModel(getContext(), languageKey);

        ImageButton backArrow = view.findViewById(R.id.imageButton_liquidity_back_arrow);
        TextView title = view.findViewById(R.id.textView_liquidity_title);
        TextView positionsTitle = view.findViewById(R.id.textView_liquidity_positions_title);
        TextView addTitle = view.findViewById(R.id.textView_liquidity_add_title);
        TextView tokenALabel = view.findViewById(R.id.textView_liquidity_tokenA_label);
        TextView tokenBLabel = view.findViewById(R.id.textView_liquidity_tokenB_label);
        TextView slippageLabel = view.findViewById(R.id.textView_liquidity_slippage_label);
        positionsLayout = view.findViewById(R.id.layout_liquidity_positions);
        noPositionsTextView = view.findViewById(R.id.textView_liquidity_no_positions);
        statusTextView = view.findViewById(R.id.textView_liquidity_status);
        progress = view.findViewById(R.id.progress_liquidity);
        refreshButton = view.findViewById(R.id.imageButton_liquidity_refresh);
        addButton = view.findViewById(R.id.button_liquidity_add);
        amountAEditText = view.findViewById(R.id.editText_liquidity_amountA);
        amountBEditText = view.findViewById(R.id.editText_liquidity_amountB);
        slippageEditText = view.findViewById(R.id.editText_liquidity_slippage);

        title.setText(jsonViewModel.lang("liquidity", "Liquidity"));
        positionsTitle.setText(jsonViewModel.lang("your-positions", "Your positions"));
        addTitle.setText(jsonViewModel.lang("add-liquidity", "Add Liquidity"));
        tokenALabel.setText(jsonViewModel.lang("token-a", "Token A"));
        tokenBLabel.setText(jsonViewModel.lang("token-b", "Token B"));
        slippageLabel.setText(jsonViewModel.lang("slippage", "Slippage"));
        amountAEditText.setHint(jsonViewModel.lang("amount", "Amount"));
        amountBEditText.setHint(jsonViewModel.lang("amount", "Amount"));
        addButton.setText(jsonViewModel.lang("add-liquidity", "Add Liquidity"));
        noPositionsTextView.setText(jsonViewModel.lang("no-positions",
                "You have no liquidity positions."));

        String customLabel = jsonViewModel.lang("custom-contract-address", "Custom...");
        tokenAPicker = new TokenPickerController(getContext(),
                (Spinner) view.findViewById(R.id.spinner_liquidity_tokenA),
                (EditText) view.findViewById(R.id.editText_liquidity_tokenA_custom),
                walletAddress, customLabel);
        tokenBPicker = new TokenPickerController(getContext(),
                (Spinner) view.findViewById(R.id.spinner_liquidity_tokenB),
                (EditText) view.findViewById(R.id.editText_liquidity_tokenB_custom),
                walletAddress, customLabel);

        backArrow.setOnClickListener(v -> mListener.onLiquidityCompleteByBackArrow());
        refreshButton.setOnClickListener(v -> loadPositions());
        addButton.setOnClickListener(v -> startAdd());

        loadPositions();
    }

    // ---------------------------------------------------------------
    // Positions
    // ---------------------------------------------------------------

    private void loadPositions() {
        try {
            setBusy(true);
            JSONObject payload = DexPayloads.base();
            payload.put("ownerAddress", walletAddress);
            KeyViewModel.getBridge().dexCallAsync("liquidityListPositions", payload,
                    uiCallback(data -> {
                        setBusy(false);
                        renderPositions(data.optJSONArray("positions"));
                    }));
        } catch (Exception e) {
            failFlow(e.getMessage());
        }
    }

    private void renderPositions(JSONArray positions) {
        positionsLayout.removeAllViews();
        if (positions == null || positions.length() == 0) {
            noPositionsTextView.setVisibility(View.VISIBLE);
            return;
        }
        noPositionsTextView.setVisibility(View.GONE);
        for (int i = 0; i < positions.length(); i++) {
            final JSONObject pos = positions.optJSONObject(i);
            if (pos == null) continue;
            positionsLayout.addView(buildPositionRow(pos));
        }
    }

    private View buildPositionRow(final JSONObject pos) {
        Context ctx = getContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        String sym0 = sanitize(pos.optString("symbol0", ""));
        String sym1 = sanitize(pos.optString("symbol1", ""));
        String pairLabel = (sym0.isEmpty() ? shortAddr(pos.optString("token0", "")) : sym0)
                + " / " + (sym1.isEmpty() ? shortAddr(pos.optString("token1", "")) : sym1);

        TextView pairText = new TextView(ctx);
        pairText.setText(pairLabel);
        pairText.setTypeface(null, Typeface.BOLD);
        pairText.setTextSize(15);
        pairText.setTextColor(getResources().getColor(R.color.colorCommon6));
        row.addView(pairText);

        TextView lpText = new TextView(ctx);
        // LP tokens are fixed 18-decimals (UniswapV2 semantics).
        lpText.setText(jsonViewModel.lang("lp-tokens", "LP tokens") + ": "
                + CoinUtils.formatUnits(pos.optString("lpBalance", "0"), 18));
        lpText.setTextSize(13);
        lpText.setTextColor(getResources().getColor(R.color.colorCommon3));
        row.addView(lpText);

        TextView reservesText = new TextView(ctx);
        reservesText.setText(jsonViewModel.lang("pool-reserves", "Reserves") + ": "
                + CoinUtils.formatUnits(pos.optString("reserve0", "0"), pos.optInt("decimals0", 18))
                + " / "
                + CoinUtils.formatUnits(pos.optString("reserve1", "0"), pos.optInt("decimals1", 18)));
        reservesText.setTextSize(13);
        reservesText.setTextColor(getResources().getColor(R.color.colorCommon3));
        row.addView(reservesText);

        Button removeButton = new Button(ctx);
        removeButton.setText(jsonViewModel.lang("remove-liquidity", "Remove Liquidity"));
        removeButton.setAllCaps(false);
        removeButton.setTextColor(getResources().getColor(R.color.colorWhite));
        removeButton.setBackgroundResource(R.drawable.button_green_selector);
        removeButton.setPadding(dp(20), dp(6), dp(20), dp(6));
        removeButton.setOnClickListener(v -> promptRemove(pos));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        row.addView(removeButton, lp);

        View divider = new View(ctx);
        divider.setBackgroundResource(R.drawable.line_2_shape);
        divider.setAlpha(0.2f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dlp.topMargin = dp(8);
        row.addView(divider, dlp);

        return row;
    }

    // ---------------------------------------------------------------
    // Remove liquidity
    // ---------------------------------------------------------------

    private void promptRemove(final JSONObject pos) {
        Context ctx = getContext();
        final EditText percentEditText = new EditText(ctx);
        percentEditText.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        percentEditText.setText("100");
        percentEditText.setGravity(android.view.Gravity.CENTER);

        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(24), dp(8), dp(24), 0);
        TextView label = new TextView(ctx);
        label.setText(jsonViewModel.lang("remove-percent", "Percent of position to remove"));
        wrap.addView(label);
        wrap.addView(percentEditText);

        new AlertDialog.Builder(ctx)
                .setTitle(jsonViewModel.lang("remove-liquidity", "Remove Liquidity"))
                .setView(wrap)
                .setPositiveButton(jsonViewModel.getOkByLangValues(), (d, w) -> {
                    int pct;
                    try {
                        pct = Integer.parseInt(percentEditText.getText().toString().trim());
                    } catch (Exception e) {
                        pct = 0;
                    }
                    if (pct <= 0 || pct > 100) {
                        GlobalMethods.ShowErrorDialog(ctx,
                                jsonViewModel.getErrorTitleByLangValues(),
                                jsonViewModel.err("invalidQuantity", "Enter a valid quantity."));
                        return;
                    }
                    final int pctFinal = pct;
                    DexUnlockPrompt.show(getActivity(), jsonViewModel,
                            password -> runRemoveFlow(pos, pctFinal));
                })
                .setNegativeButton(jsonViewModel.getCancelByLangValues(), (d, w) -> d.dismiss())
                .show();
    }

    private void runRemoveFlow(final JSONObject pos, final int percent) {
        setBusy(true);
        final Context appCtx = getActivity().getApplicationContext();
        new Thread(() -> {
            try {
                final String[] keys = DexUnlockPrompt.loadWalletKeys(appCtx, walletAddress);

                // Integer math mirrors desktop liquidity-tx.ts: burn
                // share of each reserve, then slippage tolerance.
                BigInteger lpBalance = new BigInteger(pos.optString("lpBalance", "0"));
                BigInteger liquidity = lpBalance
                        .multiply(BigInteger.valueOf(percent))
                        .divide(BigInteger.valueOf(100));
                if (liquidity.signum() <= 0) {
                    throw new Exception("Nothing to remove");
                }
                BigInteger totalSupply = new BigInteger(pos.optString("totalSupply", "1"));
                if (totalSupply.signum() <= 0) totalSupply = BigInteger.ONE;
                BigInteger reserve0 = new BigInteger(pos.optString("reserve0", "0"));
                BigInteger reserve1 = new BigInteger(pos.optString("reserve1", "0"));
                long slipBps = Math.round(slippagePercent() * 100);
                BigInteger keep = BigInteger.valueOf(10000 - slipBps);
                BigInteger amountAMin = reserve0.multiply(liquidity).divide(totalSupply)
                        .multiply(keep).divide(BigInteger.valueOf(10000));
                BigInteger amountBMin = reserve1.multiply(liquidity).divide(totalSupply)
                        .multiply(keep).divide(BigInteger.valueOf(10000));

                final String pairAddress = pos.optString("pairAddress", "");
                final JSONObject submit = DexPayloads.withKeys(appCtx, keys[0], keys[1]);
                submit.put("tokenAAddress", pos.optString("token0", ""));
                submit.put("tokenBAddress", pos.optString("token1", ""));
                submit.put("liquidityWei", liquidity.toString());
                submit.put("amountAMinWei", amountAMin.toString());
                submit.put("amountBMinWei", amountBMin.toString());
                submit.put("ownerAddress", walletAddress);
                submit.put("gasLimit", 300000);

                final BigInteger liquidityFinal = liquidity;
                mainHandler.post(() -> ensureAllowanceThen(keys, pairAddress,
                        liquidityFinal, () -> submitDex("liquiditySubmitRemove", submit,
                                jsonViewModel.lang("remove-liquidity", "Remove Liquidity"))));
            } catch (Exception e) {
                mainHandler.post(() -> failFlow(e.getMessage()));
            }
        }).start();
    }

    // ---------------------------------------------------------------
    // Add liquidity
    // ---------------------------------------------------------------

    private void startAdd() {
        final String amountA = text(amountAEditText);
        final String amountB = text(amountBEditText);
        if (tokenAPicker.getTokenValue().equalsIgnoreCase(tokenBPicker.getTokenValue())) {
            GlobalMethods.ShowErrorDialog(getContext(),
                    jsonViewModel.getErrorTitleByLangValues(),
                    jsonViewModel.err("identicalTokens", "Token A and Token B must differ."));
            return;
        }
        if (!isPositiveDecimal(amountA) || !isPositiveDecimal(amountB)) {
            GlobalMethods.ShowErrorDialog(getContext(),
                    jsonViewModel.getErrorTitleByLangValues(),
                    jsonViewModel.err("invalidQuantity", "Enter a valid quantity."));
            return;
        }
        setBusy(true);
        resolveMeta(tokenAPicker, () -> resolveMeta(tokenBPicker, this::checkPairThenAdd));
    }

    private void checkPairThenAdd() {
        try {
            JSONObject payload = DexPayloads.base();
            payload.put("tokenAValue", tokenAPicker.getTokenValue());
            payload.put("tokenBValue", tokenBPicker.getTokenValue());
            payload.put("ownerAddress", walletAddress);
            KeyViewModel.getBridge().dexCallAsync("liquidityGetPairInfo", payload,
                    uiCallback(data -> {
                        boolean exists = data.optBoolean("exists", false);
                        boolean emptyPool = exists && data.optJSONObject("pair") != null
                                && "0".equals(data.getJSONObject("pair").optString("reserve0", "0"))
                                && "0".equals(data.getJSONObject("pair").optString("reserve1", "0"));
                        if (!exists || emptyPool) {
                            // Desktop first-provider warning: the ratio
                            // sets the initial price.
                            new AlertDialog.Builder(getContext())
                                    .setTitle(jsonViewModel.lang("add-liquidity", "Add Liquidity"))
                                    .setMessage(jsonViewModel.lang("first-provider-warn",
                                            "This pool is empty. You are the first liquidity provider: the ratio of the amounts you add sets the initial price of this pair."))
                                    .setPositiveButton(jsonViewModel.getOkByLangValues(),
                                            (d, w) -> unlockThenAdd())
                                    .setNegativeButton(jsonViewModel.getCancelByLangValues(),
                                            (d, w) -> {
                                                d.dismiss();
                                                failFlow(null);
                                            })
                                    .setCancelable(false)
                                    .show();
                        } else {
                            unlockThenAdd();
                        }
                    }));
        } catch (Exception e) {
            failFlow(e.getMessage());
        }
    }

    private void unlockThenAdd() {
        DexUnlockPrompt.show(getActivity(), jsonViewModel, password -> {
            final Context appCtx = getActivity().getApplicationContext();
            new Thread(() -> {
                try {
                    final String[] keys = DexUnlockPrompt.loadWalletKeys(appCtx, walletAddress);
                    mainHandler.post(() -> approveSideAThen(keys));
                } catch (Exception e) {
                    mainHandler.post(() -> failFlow(e.getMessage()));
                }
            }).start();
        });
    }

    private void approveSideAThen(final String[] keys) {
        String tokenA = tokenAPicker.getTokenValue();
        if ("Q".equals(tokenA)) {
            // Native side goes through addLiquidityETH's value field;
            // no ERC20 approval exists for it.
            approveSideBThen(keys);
            return;
        }
        BigInteger required = new BigInteger(
                CoinUtils.parseUnits(text(amountAEditText), tokenAPicker.getDecimals()));
        ensureAllowanceThen(keys, tokenA, required, () -> approveSideBThen(keys));
    }

    private void approveSideBThen(final String[] keys) {
        String tokenB = tokenBPicker.getTokenValue();
        if ("Q".equals(tokenB)) {
            submitAdd(keys);
            return;
        }
        BigInteger required = new BigInteger(
                CoinUtils.parseUnits(text(amountBEditText), tokenBPicker.getDecimals()));
        ensureAllowanceThen(keys, tokenB, required, () -> submitAdd(keys));
    }

    private void submitAdd(final String[] keys) {
        try {
            JSONObject payload = DexPayloads.withKeys(getContext(), keys[0], keys[1]);
            payload.put("tokenAValue", tokenAPicker.getTokenValue());
            payload.put("tokenBValue", tokenBPicker.getTokenValue());
            payload.put("amountA", text(amountAEditText));
            payload.put("amountB", text(amountBEditText));
            payload.put("decimalsA", tokenAPicker.getDecimals());
            payload.put("decimalsB", tokenBPicker.getDecimals());
            payload.put("slippagePercent", slippagePercent());
            payload.put("ownerAddress", walletAddress);
            payload.put("gasLimit", 300000);
            submitDex("liquiditySubmitAdd", payload,
                    jsonViewModel.lang("add-liquidity", "Add Liquidity"));
        } catch (Exception e) {
            failFlow(e.getMessage());
        }
    }

    // ---------------------------------------------------------------
    // Shared allowance/approve/poll chain (ERC20 sides and LP tokens)
    // ---------------------------------------------------------------

    private void ensureAllowanceThen(final String[] keys, final String tokenAddress,
                                     final BigInteger requiredWei, final Runnable onReady) {
        try {
            JSONObject payload = DexPayloads.base();
            payload.put("tokenAddress", tokenAddress);
            payload.put("requiredAmountWei", requiredWei.toString());
            payload.put("ownerAddress", walletAddress);
            KeyViewModel.getBridge().dexCallAsync("liquidityCheckAllowance", payload,
                    uiCallback(data -> {
                        if (data.optBoolean("sufficient", false)) {
                            onReady.run();
                            return;
                        }
                        setStatus(jsonViewModel.lang("step-approve", "Approve") + ": "
                                + shortAddr(tokenAddress));
                        JSONObject approve = DexPayloads.withKeys(getContext(), keys[0], keys[1]);
                        approve.put("tokenAddress", tokenAddress);
                        approve.put("gasLimit", 84000);
                        KeyViewModel.getBridge().dexCallAsync("liquiditySubmitApprove", approve,
                                uiCallback(approveData -> {
                                    setStatus(jsonViewModel.lang("swap-approval-status-pending",
                                            "Transaction is still pending..."));
                                    pollAllowance(tokenAddress, requiredWei, onReady, 0);
                                }));
                    }));
        } catch (Exception e) {
            failFlow(e.getMessage());
        }
    }

    private void pollAllowance(final String tokenAddress, final BigInteger requiredWei,
                               final Runnable onReady, final int attempt) {
        if (attempt >= POLL_MAX_ATTEMPTS) {
            failFlow(jsonViewModel.lang("swap-approval-may-close",
                    "You may close this dialog, the transaction for approval has already been submitted."));
            return;
        }
        mainHandler.postDelayed(() -> {
            if (getActivity() == null) return;
            try {
                JSONObject payload = DexPayloads.base();
                payload.put("tokenAddress", tokenAddress);
                payload.put("requiredAmountWei", requiredWei.toString());
                payload.put("ownerAddress", walletAddress);
                KeyViewModel.getBridge().dexCallAsync("liquidityCheckAllowance", payload,
                        new BridgeCallback() {
                            @Override public void onResult(String jsonResult) {
                                mainHandler.post(() -> {
                                    if (getActivity() == null) return;
                                    boolean sufficient = false;
                                    try {
                                        sufficient = new JSONObject(jsonResult)
                                                .getJSONObject("data")
                                                .optBoolean("sufficient", false);
                                    } catch (Exception ignore) { }
                                    if (sufficient) onReady.run();
                                    else pollAllowance(tokenAddress, requiredWei, onReady, attempt + 1);
                                });
                            }
                            @Override public void onError(String error) {
                                mainHandler.post(() ->
                                        pollAllowance(tokenAddress, requiredWei, onReady, attempt + 1));
                            }
                        });
            } catch (Exception e) {
                failFlow(e.getMessage());
            }
        }, POLL_INTERVAL_MS);
    }

    private void submitDex(String method, JSONObject payload, final String successTitle) {
        try {
            setStatus(jsonViewModel.getSubmittingTransactionByLangValues());
            KeyViewModel.getBridge().dexCallAsync(method, payload,
                    uiCallback(data -> {
                        setBusy(false);
                        clearStatus();
                        String txHash = data.optString("txHash", "");
                        new AlertDialog.Builder(getContext())
                                .setTitle(successTitle)
                                .setMessage(jsonViewModel.lang("transaction-submitted",
                                        "Transaction submitted.") + "\n\n" + txHash)
                                .setPositiveButton(jsonViewModel.getOkByLangValues(),
                                        (d, w) -> {
                                            d.dismiss();
                                            loadPositions();
                                        })
                                .show();
                    }));
        } catch (Exception e) {
            failFlow(e.getMessage());
        }
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

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

    private double slippagePercent() {
        try {
            double v = Double.parseDouble(text(slippageEditText));
            return Math.max(0, Math.min(100, v));
        } catch (Exception e) {
            return 1;
        }
    }

    private static boolean isPositiveDecimal(String s) {
        return s != null && s.matches("\\d*\\.?\\d+") && Double.parseDouble(s) > 0;
    }

    private static String text(EditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        String cleaned = s.replaceAll("[\\p{Cntrl}]", "");
        return cleaned.length() > 20 ? cleaned.substring(0, 20) : cleaned;
    }

    private static String shortAddr(String addr) {
        if (addr == null) return "";
        return addr.length() > 14
                ? addr.substring(0, 8) + "..." + addr.substring(addr.length() - 4) : addr;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        refreshButton.setVisibility(busy ? View.GONE : View.VISIBLE);
        addButton.setEnabled(!busy);
    }

    private void setStatus(String message) {
        statusTextView.setText(message);
        statusTextView.setVisibility(View.VISIBLE);
    }

    private void clearStatus() {
        statusTextView.setVisibility(View.GONE);
    }

    private void failFlow(String error) {
        setBusy(false);
        clearStatus();
        if (error != null && !error.isEmpty() && getContext() != null) {
            GlobalMethods.ShowErrorDialog(getContext(),
                    jsonViewModel.getErrorTitleByLangValues(),
                    jsonViewModel.getErrorOccurredByLangValues() + sanitizeError(error));
        }
    }

    private static String sanitizeError(String s) {
        if (s == null) return "";
        String cleaned = s.replaceAll("[\\p{Cntrl}]", " ");
        return cleaned.length() > 300 ? cleaned.substring(0, 300) : cleaned;
    }

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

    public interface OnLiquidityCompleteListener {
        void onLiquidityCompleteByBackArrow();
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        try {
            this.mListener = (OnLiquidityCompleteListener) context;
        } catch (final ClassCastException e) {
            throw new ClassCastException(context.toString() + " ");
        }
    }
}
