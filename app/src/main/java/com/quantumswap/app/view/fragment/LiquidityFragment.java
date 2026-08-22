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
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.quantumswap.app.R;
import com.quantumswap.app.bridge.BridgeCallback;
import com.quantumswap.app.utils.CoinUtils;
import com.quantumswap.app.utils.DexPayloads;
import com.quantumswap.app.utils.GlobalMethods;
import com.quantumswap.app.gas.GasKind;
import com.quantumswap.app.utils.ReleaseStore;
import com.quantumswap.app.view.dialog.TransactionReviewDialog;
import com.quantumswap.app.view.dialog.TxStepsDialog;
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

    /** Signing keys loaded once per tx-steps dialog session. */

    /** Small callback for the pre-flight allowance checks that decide
     *  the step plan. */
    private interface BoolConsumer {
        void accept(boolean value);
    }

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
                (Button) view.findViewById(R.id.spinner_liquidity_tokenA),
                (EditText) view.findViewById(R.id.editText_liquidity_tokenA_custom),
                walletAddress, customLabel);
        tokenBPicker = new TokenPickerController(getContext(),
                (Button) view.findViewById(R.id.spinner_liquidity_tokenB),
                (EditText) view.findViewById(R.id.editText_liquidity_tokenB_custom),
                walletAddress, customLabel);

        listPanel = view.findViewById(R.id.layout_liquidity_list_panel);
        formPanel = view.findViewById(R.id.layout_liquidity_form_panel);
        TextView addLink = view.findViewById(R.id.textView_liquidity_add_link);
        addLink.setText(jsonViewModel.lang("add-liquidity", "Add Liquidity"));
        addLink.setPaintFlags(addLink.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        addLink.setOnClickListener(v -> showAddPanel());
        ((com.quantumswap.app.view.widget.MaxHeightScrollView) view.findViewById(R.id.scroll_liquidity_positions)).capToScreen(dp(70));

        // Desktop: back from the add form returns to My Positions; back
        // from the positions list leaves the screen.
        backArrow.setOnClickListener(v -> {
            if (formPanel.getVisibility() == View.VISIBLE) showPositionsPanel();
            else mListener.onLiquidityCompleteByBackArrow();
        });
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
        // Symbols link to the token contract on the block explorer.
        com.quantumswap.app.view.widget.ExplorerLinks.setPairLabel(pairText,
                sym0.isEmpty() ? shortAddr(pos.optString("token0", "")) : sym0, pos.optString("token0", ""),
                sym1.isEmpty() ? shortAddr(pos.optString("token1", "")) : sym1, pos.optString("token1", ""));
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

        // Desktop position card: "Remove Liquidity" is a link.
        TextView removeButton = new TextView(ctx);
        removeButton.setText(jsonViewModel.lang("remove-liquidity", "Remove Liquidity"));
        removeButton.setTextSize(14);
        removeButton.setTextColor(getResources().getColor(R.color.quantumTeal));
        removeButton.setPaintFlags(removeButton.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        removeButton.setBackgroundResource(R.drawable.drawer_item_bg);
        removeButton.setClickable(true);
        removeButton.setFocusable(true);
        removeButton.setPadding(dp(8), dp(6), dp(8), dp(6));
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
                    runRemoveFlow(pos, pctFinal);
                })
                .setNegativeButton(jsonViewModel.getCancelByLangValues(), (d, w) -> d.dismiss())
                .show();
    }

    /** Desktop tx-steps model for Remove: compute the burn amounts
     *  (no keys needed), pre-check the LP-token allowance, then run
     *  [Approve LP?] [Remove Liquidity] through the steps dialog. */
    private void runRemoveFlow(final JSONObject pos, final int percent) {
        setBusy(true);
        new Thread(() -> {
            try {
                // Integer math mirrors desktop liquidity-tx.ts: burn
                // share of each reserve, then slippage tolerance.
                BigInteger lpBalance = new BigInteger(pos.optString("lpBalance", "0"));
                final BigInteger liquidity = lpBalance
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
                final BigInteger amountAMin = reserve0.multiply(liquidity).divide(totalSupply)
                        .multiply(keep).divide(BigInteger.valueOf(10000));
                final BigInteger amountBMin = reserve1.multiply(liquidity).divide(totalSupply)
                        .multiply(keep).divide(BigInteger.valueOf(10000));
                final String pairAddress = pos.optString("pairAddress", "");

                mainHandler.post(() -> checkNeedsApproval(pairAddress, liquidity,
                        needsApprove -> {
                            setBusy(false);
                            showRemoveSteps(pos, pairAddress, liquidity,
                                    amountAMin, amountBMin, needsApprove, percent);
                        }));
            } catch (Exception e) {
                mainHandler.post(() -> failFlow(e.getMessage()));
            }
        }).start();
    }

    /** Desktop remove plan: [Approve A/B LP?] [Remove Liquidity A / B];
     *  reviews per desktop buildRemoveLiquidityReview / LP approve. */
    private void showRemoveSteps(final JSONObject pos, final String pairAddress,
                                 final BigInteger liquidity, final BigInteger amountAMin,
                                 final BigInteger amountBMin, boolean needsApprove,
                                 final int percent) {
        final String removeLabel = jsonViewModel.lang("remove-liquidity", "Remove Liquidity");
        final ReleaseStore.Release release = ReleaseStore.readActive(KeyViewModel.getSecureStorage());
        String sym0 = sanitize(pos.optString("symbol0", ""));
        String sym1 = sanitize(pos.optString("symbol1", ""));
        final String pairLabel = (sym0.isEmpty() ? shortAddr(pos.optString("token0", "")) : sym0)
                + " / " + (sym1.isEmpty() ? shortAddr(pos.optString("token1", "")) : sym1);
        final String lpAmount = CoinUtils.formatUnits(liquidity.toString(), 18);

        TransactionReviewDialog.ReviewSpec base = new TransactionReviewDialog.ReviewSpec()
                .action(removeLabel + " " + pairLabel)
                .contractAddress(release.router)
                .fromAddress(walletAddress)
                .toAddress(release.router)
                .quantityLabelKey("lp-to-burn")
                .quantityValue(lpAmount + " LP (" + percent + "%)")
                .networkText(TransactionReviewDialog.networkText(jsonViewModel));

        java.util.List<TxStepsDialog.Step> steps = new java.util.ArrayList<>();
        if (needsApprove) {
            TransactionReviewDialog.ReviewSpec approveReview = new TransactionReviewDialog.ReviewSpec()
                    .action(jsonViewModel.lang("step-approve", "Approve") + " "
                            + pairLabel.replace(" / ", "/") + " LP")
                    .contractAddress(pairAddress)
                    .contractIsToken(true)
                    .toAddress(pairAddress)
                    .quantityLabelKey("send-quantity")
                    .quantityValue(lpAmount + " LP");
            steps.add(approveTokenStep(
                    jsonViewModel.lang("step-approve", "Approve") + " LP",
                    pairAddress, approveReview));
        }
        steps.add(new TxStepsDialog.Step(removeLabel,
                GasKind.REMOVE_LIQUIDITY, true,
                () -> {
                    JSONObject p = new JSONObject();
                    putRemoveArgs(p, pos, liquidity, amountAMin, amountBMin);
                    return p;
                },
                null,
                (gasLimit, credentials, chain, cb) -> {
                    try {
                        JSONObject submit = DexPayloads.withKeys(getContext(),
                                credentials.privateKeyBase64, credentials.publicKeyBase64);
                        TxStepsDialog.overlay(submit, chain);
                        putRemoveArgs(submit, pos, liquidity, amountAMin, amountBMin);
                        submit.put("gasLimit", gasLimit);
                        KeyViewModel.getBridge().dexCallAsync("liquiditySubmitRemove", submit,
                                stepCallback(cb));
                    } catch (Exception e) {
                        cb.fail(sanitizeError(e.getMessage()));
                    }
                }));
        openSteps(removeLabel, base, steps);
    }

    private void putRemoveArgs(JSONObject p, JSONObject pos, BigInteger liquidity,
                               BigInteger amountAMin, BigInteger amountBMin) throws Exception {
        p.put("tokenAAddress", pos.optString("token0", ""));
        p.put("tokenBAddress", pos.optString("token1", ""));
        p.put("liquidityWei", liquidity.toString());
        p.put("amountAMinWei", amountAMin.toString());
        p.put("amountBMinWei", amountBMin.toString());
        p.put("ownerAddress", walletAddress);
    }

    /** Shared ERC20 approve-toward-router step (liquidity token or LP
     *  token); desktop txKind "approveToken". */
    private TxStepsDialog.Step approveTokenStep(String label, final String tokenAddress,
                                                TransactionReviewDialog.ReviewSpec review) {
        return new TxStepsDialog.Step(label, GasKind.APPROVE_TOKEN, true,
                () -> {
                    JSONObject p = new JSONObject();
                    p.put("tokenAddress", tokenAddress);
                    p.put("ownerAddress", walletAddress);
                    return p;
                },
                review,
                (gasLimit, credentials, chain, cb) -> {
                    try {
                        JSONObject approve = DexPayloads.withKeys(getContext(),
                                credentials.privateKeyBase64, credentials.publicKeyBase64);
                        TxStepsDialog.overlay(approve, chain);
                        approve.put("tokenAddress", tokenAddress);
                        approve.put("gasLimit", gasLimit);
                        KeyViewModel.getBridge().dexCallAsync("liquiditySubmitApprove", approve,
                                stepCallback(cb));
                    } catch (Exception e) {
                        cb.fail(sanitizeError(e.getMessage()));
                    }
                });
    }

    private TxStepsDialog stepsDialog;
    private View listPanel;
    private View formPanel;

    /** Desktop showLiquidityAddPanel: swap to the form with a clean slate. */
    private void showAddPanel() {
        tokenAPicker.restoreSelection(null);
        tokenBPicker.restoreSelection(null);
        amountAEditText.setText("");
        amountBEditText.setText("");
        clearStatus();
        listPanel.setVisibility(View.GONE);
        formPanel.setVisibility(View.VISIBLE);
    }

    /** Desktop showLiquidityPositionsPanel. */
    private void showPositionsPanel() {
        formPanel.setVisibility(View.GONE);
        listPanel.setVisibility(View.VISIBLE);
        loadPositions();
    }

    private void openSteps(String title, TransactionReviewDialog.ReviewSpec base,
                           java.util.List<TxStepsDialog.Step> steps) {
        stepsDialog = new TxStepsDialog(getActivity(), jsonViewModel, walletAddress,
                title, base, steps, null,
                () -> {
                    stepsDialog = null;
                    if (getView() != null) showPositionsPanel();
                });
        stepsDialog.show();
    }

    @Override
    public void onDestroyView() {
        if (stepsDialog != null) { stepsDialog.dismiss(); stepsDialog = null; }
        super.onDestroyView();
    }

    /** Bridge submit result -> step submitted(txHash) / fail(message). */
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
                                            (d, w) -> unlockThenAdd(exists))
                                    .setNegativeButton(jsonViewModel.getCancelByLangValues(),
                                            (d, w) -> {
                                                d.dismiss();
                                                failFlow(null);
                                            })
                                    .setCancelable(false)
                                    .show();
                        } else {
                            unlockThenAdd(true);
                        }
                    }));
        } catch (Exception e) {
            failFlow(e.getMessage());
        }
    }

    /** Desktop tx-steps model: pre-check both ERC20 sides' allowances
     *  to build the step plan ([Approve A?] [Approve B?] [Add]), then
     *  drive it through the shared steps dialog. Native "Q" sides need
     *  no approval (they travel as addLiquidityETH value). */
    private void unlockThenAdd(final boolean pairExists) {
        final String tokenA = tokenAPicker.getTokenValue();
        final String tokenB = tokenBPicker.getTokenValue();
        final BigInteger requiredA = "Q".equals(tokenA) ? null : new BigInteger(
                CoinUtils.parseUnits(text(amountAEditText), tokenAPicker.getDecimals()));
        final BigInteger requiredB = "Q".equals(tokenB) ? null : new BigInteger(
                CoinUtils.parseUnits(text(amountBEditText), tokenBPicker.getDecimals()));
        checkNeedsApproval(tokenA, requiredA, needsA ->
                checkNeedsApproval(tokenB, requiredB, needsB -> {
                    setBusy(false);
                    showAddSteps(needsA, needsB, tokenA, tokenB, requiredA, requiredB, pairExists);
                }));
    }

    private void checkNeedsApproval(final String tokenAddress, final BigInteger requiredWei,
                                    final BoolConsumer onResult) {
        if (requiredWei == null) {
            onResult.accept(false);
            return;
        }
        try {
            JSONObject payload = DexPayloads.base();
            payload.put("tokenAddress", tokenAddress);
            payload.put("requiredAmountWei", requiredWei.toString());
            payload.put("ownerAddress", walletAddress);
            KeyViewModel.getBridge().dexCallAsync("liquidityCheckAllowance", payload,
                    uiCallback(data ->
                            onResult.accept(!data.optBoolean("sufficient", false))));
        } catch (Exception e) {
            failFlow(e.getMessage());
        }
    }

    /** Desktop add plan: [Approve A?] [Approve B?] [Add Liquidity A / B].
     *  {@code pairExists} picks the add-liquidity default gas
     *  (600000 vs 4500000 for a brand-new pair). */
    private void showAddSteps(boolean needsA, boolean needsB,
                              final String tokenA, final String tokenB,
                              final BigInteger requiredA, final BigInteger requiredB,
                              final boolean pairExists) {
        final String approveLabel = jsonViewModel.lang("step-approve", "Approve");
        final String addLabel = jsonViewModel.lang("add-liquidity", "Add Liquidity");
        final String symA = sanitize(tokenAPicker.getSymbol());
        final String symB = sanitize(tokenBPicker.getSymbol());
        final String amountA = text(amountAEditText);
        final String amountB = text(amountBEditText);
        final ReleaseStore.Release release = ReleaseStore.readActive(KeyViewModel.getSecureStorage());
        final String nativeLeg = "Q".equals(tokenA) ? amountA : ("Q".equals(tokenB) ? amountB : "0");

        TransactionReviewDialog.ReviewSpec base = new TransactionReviewDialog.ReviewSpec()
                .action(addLabel + " " + symA + " / " + symB)
                .contractAddress(release.router)
                .fromAddress(walletAddress)
                .toAddress(release.router)
                .quantityValue(nativeLeg)
                .tokenQuantityValue(amountA + " " + symA + " + " + amountB + " " + symB)
                .networkText(TransactionReviewDialog.networkText(jsonViewModel));

        java.util.List<TxStepsDialog.Step> steps = new java.util.ArrayList<>();
        if (needsA) {
            steps.add(approveTokenStep(approveLabel + " " + symA, tokenA,
                    liquidityApproveReview(approveLabel + " " + symA, tokenA, amountA + " " + symA)));
        }
        if (needsB) {
            steps.add(approveTokenStep(approveLabel + " " + symB, tokenB,
                    liquidityApproveReview(approveLabel + " " + symB, tokenB, amountB + " " + symB)));
        }
        steps.add(new TxStepsDialog.Step(addLabel + " " + symA + " / " + symB,
                GasKind.ADD_LIQUIDITY, pairExists,
                () -> {
                    JSONObject p = new JSONObject();
                    putAddArgs(p);
                    return p;
                },
                null,
                (gasLimit, credentials, chain, cb) -> {
                    try {
                        JSONObject payload = DexPayloads.withKeys(getContext(),
                                credentials.privateKeyBase64, credentials.publicKeyBase64);
                        TxStepsDialog.overlay(payload, chain);
                        putAddArgs(payload);
                        payload.put("gasLimit", gasLimit);
                        KeyViewModel.getBridge().dexCallAsync("liquiditySubmitAdd", payload,
                                stepCallback(cb));
                    } catch (Exception e) {
                        cb.fail(sanitizeError(e.getMessage()));
                    }
                }));
        openSteps(addLabel, base, steps);
    }

    /** Desktop approveStep(..., showAsTokenQuantity=true): contract/to =
     *  token, Quantity (Q) "0", "Approval token quantity" = amount. */
    private TransactionReviewDialog.ReviewSpec liquidityApproveReview(String action,
                                                                      String tokenAddress,
                                                                      String tokenQuantity) {
        return new TransactionReviewDialog.ReviewSpec()
                .action(action)
                .contractAddress(tokenAddress)
                .contractIsToken(true)
                .toAddress(tokenAddress)
                .quantityLabelKey("send-quantity")
                .quantityValue("0")
                .tokenQuantityLabelKey("approval-token-quantity")
                .tokenQuantityValue(tokenQuantity);
    }

    private void putAddArgs(JSONObject payload) throws Exception {
        payload.put("tokenAValue", tokenAPicker.getTokenValue());
        payload.put("tokenBValue", tokenBPicker.getTokenValue());
        payload.put("amountA", text(amountAEditText));
        payload.put("amountB", text(amountBEditText));
        payload.put("decimalsA", tokenAPicker.getDecimals());
        payload.put("decimalsB", tokenBPicker.getDecimals());
        payload.put("slippagePercent", slippagePercent());
        payload.put("ownerAddress", walletAddress);
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
