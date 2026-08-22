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
import com.quantumswap.app.gas.GasChipController;
import com.quantumswap.app.gas.GasKind;
import com.quantumswap.app.utils.ReleaseStore;
import com.quantumswap.app.view.dialog.TransactionReviewDialog;
import com.quantumswap.app.view.dialog.TxStepsDialog;
import com.quantumswap.app.view.widget.TokenPickerController;
import com.quantumswap.app.viewmodel.JsonViewModel;
import com.quantumswap.app.viewmodel.KeyViewModel;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Pools screen — browse factory pairs with reserves and create a new
 * pair (desktop Advanced → Pools).
 */
public class PoolsFragment extends Fragment {

    private OnPoolsCompleteListener mListener;

    private JsonViewModel jsonViewModel;
    private String walletAddress;

    private LinearLayout poolsLayout;
    private TextView emptyTextView;
    private TextView statusTextView;
    private ProgressBar progress;
    private ImageButton refreshButton;
    private Button createButton;

    private TokenPickerController tokenAPicker;
    private TokenPickerController tokenBPicker;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static PoolsFragment newInstance() {
        return new PoolsFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.pools_fragment, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        String languageKey = getArguments().getString("languageKey");
        walletAddress = getArguments().getString("walletAddress");
        jsonViewModel = new JsonViewModel(getContext(), languageKey);

        ImageButton backArrow = view.findViewById(R.id.imageButton_pools_back_arrow);
        TextView title = view.findViewById(R.id.textView_pools_title);
        TextView listTitle = view.findViewById(R.id.textView_pools_list_title);
        TextView createTitle = view.findViewById(R.id.textView_pools_create_title);
        TextView tokenALabel = view.findViewById(R.id.textView_pools_tokenA_label);
        TextView tokenBLabel = view.findViewById(R.id.textView_pools_tokenB_label);
        poolsLayout = view.findViewById(R.id.layout_pools_list);
        emptyTextView = view.findViewById(R.id.textView_pools_empty);
        statusTextView = view.findViewById(R.id.textView_pools_status);
        progress = view.findViewById(R.id.progress_pools);
        refreshButton = view.findViewById(R.id.imageButton_pools_refresh);
        createButton = view.findViewById(R.id.button_pools_create);
        gasChip = new GasChipController(getActivity(), jsonViewModel, walletAddress,
                view.findViewById(R.id.imageView_pools_gas_icon),
                view.findViewById(R.id.textView_pools_gas_fee), GasKind.CREATE_PAIR);

        title.setText(jsonViewModel.lang("pools", "Pools"));
        listTitle.setText(jsonViewModel.lang("all-pools", "All pools"));
        createTitle.setText(jsonViewModel.lang("create-pair", "Create Pair"));
        tokenALabel.setText(jsonViewModel.lang("token-a", "Token A"));
        tokenBLabel.setText(jsonViewModel.lang("token-b", "Token B"));
        createButton.setText(jsonViewModel.lang("create-pair", "Create Pair"));
        emptyTextView.setText(jsonViewModel.lang("no-pools", "No pools yet."));

        String customLabel = jsonViewModel.lang("custom-contract-address", "Custom...");
        tokenAPicker = new TokenPickerController(getContext(),
                (Button) view.findViewById(R.id.spinner_pools_tokenA),
                (EditText) view.findViewById(R.id.editText_pools_tokenA_custom),
                walletAddress, customLabel);
        tokenBPicker = new TokenPickerController(getContext(),
                (Button) view.findViewById(R.id.spinner_pools_tokenB),
                (EditText) view.findViewById(R.id.editText_pools_tokenB_custom),
                walletAddress, customLabel);

        tokenAPicker.setOnChanged(this::scheduleGasEstimate);
        tokenBPicker.setOnChanged(this::scheduleGasEstimate);

        listPanel = view.findViewById(R.id.layout_pools_list_panel);
        formPanel = view.findViewById(R.id.layout_pools_form_panel);
        TextView createLink = view.findViewById(R.id.textView_pools_create_link);
        createLink.setText(jsonViewModel.lang("create-pair", "Create Pair"));
        createLink.setPaintFlags(createLink.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        createLink.setOnClickListener(v -> showCreatePanel());
        listScroll = view.findViewById(R.id.scroll_pools_list);
        listScroll.capToScreen(dp(70));

        // Desktop: back from the create form returns to the pool list;
        // back from the list leaves the screen.
        backArrow.setOnClickListener(v -> {
            if (formPanel.getVisibility() == View.VISIBLE) showListPanel();
            else mListener.onPoolsCompleteByBackArrow();
        });
        refreshButton.setOnClickListener(v -> loadPools());
        createButton.setOnClickListener(v -> startCreate());

        loadPools();
    }

    private void loadPools() {
        try {
            setBusy(true);
            JSONObject payload = DexPayloads.base();
            KeyViewModel.getBridge().dexCallAsync("liquidityListPools", payload,
                    uiCallback(data -> {
                        setBusy(false);
                        renderPools(data.optJSONArray("pools"));
                    }));
        } catch (Exception e) {
            failFlow(e.getMessage());
        }
    }

    private void renderPools(JSONArray pools) {
        poolsLayout.removeAllViews();
        if (pools == null || pools.length() == 0) {
            emptyTextView.setVisibility(View.VISIBLE);
            return;
        }
        emptyTextView.setVisibility(View.GONE);
        for (int i = 0; i < pools.length(); i++) {
            JSONObject pool = pools.optJSONObject(i);
            if (pool == null) continue;
            poolsLayout.addView(buildPoolRow(pool));
        }
    }

    private View buildPoolRow(JSONObject pool) {
        Context ctx = getContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        String sym0 = sanitize(pool.optString("symbol0", ""));
        String sym1 = sanitize(pool.optString("symbol1", ""));
        String pairLabel = (sym0.isEmpty() ? shortAddr(pool.optString("token0", "")) : sym0)
                + " / " + (sym1.isEmpty() ? shortAddr(pool.optString("token1", "")) : sym1);

        TextView pairText = new TextView(ctx);
        // Symbols link to the token contract on the block explorer.
        com.quantumswap.app.view.widget.ExplorerLinks.setPairLabel(pairText,
                sym0.isEmpty() ? shortAddr(pool.optString("token0", "")) : sym0, pool.optString("token0", ""),
                sym1.isEmpty() ? shortAddr(pool.optString("token1", "")) : sym1, pool.optString("token1", ""));
        pairText.setTypeface(null, Typeface.BOLD);
        pairText.setTextSize(15);
        pairText.setTextColor(getResources().getColor(R.color.colorCommon6));
        row.addView(pairText);

        TextView addrText = new TextView(ctx);
        addrText.setText(shortAddr(pool.optString("pairAddress", "")));
        addrText.setTextSize(12);
        addrText.setTextColor(getResources().getColor(R.color.colorCommon3));
        row.addView(addrText);

        TextView reservesText = new TextView(ctx);
        reservesText.setText(jsonViewModel.lang("pool-reserves", "Reserves") + ": "
                + CoinUtils.formatUnits(pool.optString("reserve0", "0"), pool.optInt("decimals0", 18))
                + " / "
                + CoinUtils.formatUnits(pool.optString("reserve1", "0"), pool.optInt("decimals1", 18)));
        reservesText.setTextSize(13);
        reservesText.setTextColor(getResources().getColor(R.color.colorCommon3));
        row.addView(reservesText);

        View divider = new View(ctx);
        divider.setBackgroundResource(R.drawable.line_2_shape);
        divider.setAlpha(0.2f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dlp.topMargin = dp(8);
        row.addView(divider, dlp);

        return row;
    }

    private void startCreate() {
        if (tokenAPicker.getTokenValue().equalsIgnoreCase(tokenBPicker.getTokenValue())) {
            GlobalMethods.ShowErrorDialog(getContext(),
                    jsonViewModel.getErrorTitleByLangValues(),
                    jsonViewModel.err("identicalTokens", "Token A and Token B must differ."));
            return;
        }
        setBusy(true);
        resolveMeta(tokenAPicker, () -> resolveMeta(tokenBPicker, this::checkPairThenCreate));
    }

    private void checkPairThenCreate() {
        try {
            JSONObject payload = DexPayloads.base();
            payload.put("tokenAValue", tokenAPicker.getTokenValue());
            payload.put("tokenBValue", tokenBPicker.getTokenValue());
            KeyViewModel.getBridge().dexCallAsync("liquidityGetPairInfo", payload,
                    uiCallback(data -> {
                        if (data.optBoolean("exists", false)) {
                            failFlow(jsonViewModel.lang("pair-exists",
                                    "A pool already exists for this pair."));
                            return;
                        }
                        setBusy(false);
                        showCreateSteps();
                    }));
        } catch (Exception e) {
            failFlow(e.getMessage());
        }
    }

    /** Desktop createPair flow: one "Create Pair" step; review rows
     *  contract/to = factory, Quantity (Q) "0". The screen gas chip's
     *  estimate (or manual override) seeds the step. */
    private void showCreateSteps() {
        final String label = jsonViewModel.lang("create-pair", "Create Pair");
        final ReleaseStore.Release release = ReleaseStore.readActive(KeyViewModel.getSecureStorage());
        final String symA = sanitize(tokenAPicker.getSymbol());
        final String symB = sanitize(tokenBPicker.getSymbol());
        TransactionReviewDialog.ReviewSpec base = new TransactionReviewDialog.ReviewSpec()
                .action(label + ": " + symA + " / " + symB)
                .contractAddress(release.factory)
                .fromAddress(walletAddress)
                .toAddress(release.factory)
                .quantityValue("0")
                .networkText(TransactionReviewDialog.networkText(jsonViewModel));
        java.util.List<TxStepsDialog.Step> steps = new java.util.ArrayList<>();
        steps.add(new TxStepsDialog.Step(label, GasKind.CREATE_PAIR, true,
                this::createPairPayload,
                null,
                (gasLimit, credentials, chain, cb) -> {
                    try {
                        JSONObject submit = DexPayloads.withKeys(getActivity().getApplicationContext(),
                                credentials.privateKeyBase64, credentials.publicKeyBase64);
                        TxStepsDialog.overlay(submit, chain);
                        submit.put("tokenAValue", tokenAPicker.getTokenValue());
                        submit.put("tokenBValue", tokenBPicker.getTokenValue());
                        submit.put("gasLimit", gasLimit);
                        KeyViewModel.getBridge().dexCallAsync("poolsSubmitCreatePair", submit,
                                stepCallback(cb));
                    } catch (Exception e) {
                        cb.fail(sanitizeError(e.getMessage()));
                    }
                }));
        stepsDialog = new TxStepsDialog(getActivity(), jsonViewModel, walletAddress,
                label, base, steps, null,
                () -> {
                    stepsDialog = null;
                    if (getView() != null) showListPanel();
                });
        stepsDialog.show();
    }

    private TxStepsDialog stepsDialog;
    private View listPanel;
    private com.quantumswap.app.view.widget.MaxHeightScrollView listScroll;
    private View formPanel;

    /** Desktop showPoolsCreatePanel: swap to the form with a clean slate. */
    private void showCreatePanel() {
        tokenAPicker.restoreSelection(null);
        tokenBPicker.restoreSelection(null);
        clearStatus();
        if (gasChip != null) gasChip.reset();
        listPanel.setVisibility(View.GONE);
        formPanel.setVisibility(View.VISIBLE);
    }

    /** Desktop showPoolsScreen list view. */
    private void showListPanel() {
        formPanel.setVisibility(View.GONE);
        listPanel.setVisibility(View.VISIBLE);
        loadPools();
    }
    private GasChipController gasChip;

    private JSONObject createPairPayload() throws Exception {
        JSONObject p = new JSONObject();
        p.put("tokenAValue", tokenAPicker.getTokenValue());
        p.put("tokenBValue", tokenBPicker.getTokenValue());
        p.put("ownerAddress", walletAddress);
        return p;
    }

    /** Desktop scheduleCreatePairGasEstimate: 2 s debounce on token
     *  change; nothing is requested until both sides are set. */
    private void scheduleGasEstimate() {
        if (gasChip == null) return;
        gasChip.schedule(() -> {
            String a = tokenAPicker.getTokenValue();
            String b = tokenBPicker.getTokenValue();
            if (a == null || b == null || a.isEmpty() || b.isEmpty() || a.equalsIgnoreCase(b)) {
                return null;
            }
            try { return createPairPayload(); } catch (Exception e) { return null; }
        });
    }

    @Override
    public void onDestroyView() {
        if (stepsDialog != null) { stepsDialog.dismiss(); stepsDialog = null; }
        super.onDestroyView();
    }

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
        createButton.setEnabled(!busy);
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

    public interface OnPoolsCompleteListener {
        void onPoolsCompleteByBackArrow();
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        try {
            this.mListener = (OnPoolsCompleteListener) context;
        } catch (final ClassCastException e) {
            throw new ClassCastException(context.toString() + " ");
        }
    }
}
