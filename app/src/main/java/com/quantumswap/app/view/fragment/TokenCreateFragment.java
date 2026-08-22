package com.quantumswap.app.view.fragment;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.quantumswap.app.R;
import com.quantumswap.app.bridge.BridgeCallback;
import com.quantumswap.app.tokens.StablecoinImpersonatorFilter;
import com.quantumswap.app.utils.DexPayloads;
import com.quantumswap.app.gas.GasChipController;
import com.quantumswap.app.gas.GasKind;
import com.quantumswap.app.view.dialog.TransactionReviewDialog;
import com.quantumswap.app.view.dialog.TxStepsDialog;
import com.quantumswap.app.viewmodel.JsonViewModel;
import com.quantumswap.app.viewmodel.KeyViewModel;

import org.json.JSONObject;

import java.math.BigDecimal;

/**
 * Create Token screen - port of the desktop app's Advanced -> Tokens
 * option (screens/advanced.ts buildTokenCreateScreen + app/advanced.ts
 * onCreateTokenClick): a single deploy-new-ERC20 form. Validation,
 * the stablecoin-impersonator gate, the 6,000,000 default deploy gas,
 * and the predicted-contract-address success surface all mirror the
 * desktop flow; progress runs through the shared TxStepsDialog with
 * one "Deploy token SYM" step.
 */
public class TokenCreateFragment extends Fragment {


    private OnTokenCreateCompleteListener mListener;

    private JsonViewModel jsonViewModel;
    private String walletAddress;

    private EditText nameEditText;
    private EditText symbolEditText;
    private Spinner decimalsSpinner;
    private EditText supplyEditText;
    private TextView errorTextView;
    private Button createButton;
    private ProgressBar progress;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static TokenCreateFragment newInstance() {
        return new TokenCreateFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.token_create_fragment, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        String languageKey = getArguments().getString("languageKey");
        walletAddress = getArguments().getString("walletAddress");
        jsonViewModel = new JsonViewModel(getContext(), languageKey);

        ImageButton backArrow = view.findViewById(R.id.imageButton_token_create_back_arrow);
        TextView title = view.findViewById(R.id.textView_token_create_title);
        TextView nameLabel = view.findViewById(R.id.textView_token_create_name_label);
        TextView symbolLabel = view.findViewById(R.id.textView_token_create_symbol_label);
        TextView decimalsLabel = view.findViewById(R.id.textView_token_create_decimals_label);
        TextView supplyLabel = view.findViewById(R.id.textView_token_create_supply_label);
        nameEditText = view.findViewById(R.id.editText_token_create_name);
        symbolEditText = view.findViewById(R.id.editText_token_create_symbol);
        decimalsSpinner = view.findViewById(R.id.spinner_token_create_decimals);
        supplyEditText = view.findViewById(R.id.editText_token_create_supply);
        errorTextView = view.findViewById(R.id.textView_token_create_error);
        createButton = view.findViewById(R.id.button_token_create);
        progress = view.findViewById(R.id.progress_token_create);

        title.setText(jsonViewModel.lang("create-token", "Create Token"));
        nameLabel.setText(jsonViewModel.lang("token-name", "Token Name"));
        symbolLabel.setText(jsonViewModel.lang("token-symbol", "Token Symbol"));
        decimalsLabel.setText(jsonViewModel.lang("token-decimals", "Decimals"));
        supplyLabel.setText(jsonViewModel.lang("token-total-supply", "Total Supply"));
        createButton.setText(jsonViewModel.lang("create", "Create"));

        // Desktop: decimals is a 1..18 select defaulting to 18.
        String[] decimalsOptions = new String[18];
        for (int i = 0; i < 18; i++) decimalsOptions[i] = String.valueOf(i + 1);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(),
                android.R.layout.simple_spinner_item, decimalsOptions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        decimalsSpinner.setAdapter(adapter);
        decimalsSpinner.setSelection(17);

        backArrow.setOnClickListener(v -> mListener.onTokenCreateCompleteByBackArrow());
        createButton.setOnClickListener(v -> onCreateClick());

        gasChip = new GasChipController(getActivity(), jsonViewModel, walletAddress,
                view.findViewById(R.id.imageView_token_create_gas_icon),
                view.findViewById(R.id.textView_token_create_gas_fee), GasKind.DEPLOY_TOKEN);
        nameEditText.addTextChangedListener(gasWatcher());
        symbolEditText.addTextChangedListener(gasWatcher());
        supplyEditText.addTextChangedListener(gasWatcher());
        decimalsSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) { scheduleGasEstimate(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) { }
        });
    }

    // ---------------------------------------------------------------
    // Validation (desktop onCreateTokenClick step A)
    // ---------------------------------------------------------------

    private void setError(String message) {
        if (message == null || message.isEmpty()) {
            errorTextView.setVisibility(View.GONE);
        } else {
            errorTextView.setText(message);
            errorTextView.setVisibility(View.VISIBLE);
        }
    }

    private void onCreateClick() {
        final String name = text(nameEditText);
        final String symbol = text(symbolEditText);
        final int decimals = decimalsSpinner.getSelectedItemPosition() + 1;
        final String supply = text(supplyEditText);

        if (name.length() < 1 || name.length() > 48 || containsUnsafeText(name)) {
            setError(jsonViewModel.lang("token-name-invalid",
                    "Enter a token name (up to 48 plain-text characters)."));
            return;
        }
        if (!symbol.matches("^[A-Za-z0-9]{1,16}$")) {
            setError(jsonViewModel.lang("token-symbol-invalid",
                    "Symbol must be 1-16 letters or digits."));
            return;
        }
        if (StablecoinImpersonatorFilter.impersonatesStablecoin(symbol, name)) {
            setError(jsonViewModel.lang("token-impersonator",
                    "This name or symbol is not allowed because it impersonates a stablecoin or fiat currency."));
            return;
        }
        if (!isValidSupply(supply, decimals)) {
            setError(jsonViewModel.lang("token-supply-invalid",
                    "Enter a valid total supply."));
            return;
        }
        setError(null);
        showDeploySteps(name, symbol, decimals, supply);
    }

    /** Desktop containsUnsafeDisplayText / htmlEncode check: reject
     *  control chars, bidi overrides, and HTML-active characters. */
    private static boolean containsUnsafeText(String s) {
        return s.matches(".*[\\p{Cntrl}<>&\"'`\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069].*");
    }

    /** Desktop parseBaseUnits: plain decimal, fraction no longer than
     *  the token's decimals, value > 0. */
    private static boolean isValidSupply(String supply, int decimals) {
        String cleaned = supply.replace(",", "").trim();
        if (!cleaned.matches("^\\d+(\\.\\d*)?$|^\\.\\d+$")) return false;
        int dot = cleaned.indexOf('.');
        if (dot >= 0 && cleaned.length() - dot - 1 > decimals) return false;
        try {
            return new BigDecimal(cleaned).signum() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------
    // Deploy (desktop review-then-steps with one "Deploy token" step)
    // ---------------------------------------------------------------

    private void showDeploySteps(final String name, final String symbol,
                                 final int decimals, final String supply) {
        String stepLabel = jsonViewModel.lang("step-deploy-token", "Deploy token")
                + " " + symbol;
        TransactionReviewDialog.ReviewSpec base = new TransactionReviewDialog.ReviewSpec()
                .action(jsonViewModel.lang("create-token", "Create Token")
                        + " " + name + " (" + symbol + ")")
                .fromAddress(walletAddress)
                .quantityLabelKey("token-total-supply")
                .quantityValue(supply + " " + symbol)
                .networkText(TransactionReviewDialog.networkText(jsonViewModel));
        java.util.List<TxStepsDialog.Step> steps = new java.util.ArrayList<>();
        steps.add(new TxStepsDialog.Step(stepLabel, GasKind.DEPLOY_TOKEN, true,
                () -> deployPayload(name, symbol, decimals, supply),
                null,
                (gasLimit, credentials, chain, cb) -> {
                    try {
                        JSONObject payload = DexPayloads.withKeys(getContext(),
                                credentials.privateKeyBase64, credentials.publicKeyBase64);
                        TxStepsDialog.overlay(payload, chain);
                        payload.put("name", name);
                        payload.put("symbol", symbol);
                        payload.put("decimals", decimals);
                        payload.put("totalSupply", supply);
                        payload.put("gasLimit", gasLimit);
                        KeyViewModel.getBridge().dexCallAsync("tokensSubmitCreate", payload,
                                new BridgeCallback() {
                                    @Override public void onResult(final String jsonResult) {
                                        mainHandler.post(() -> {
                                            try {
                                                JSONObject data = new JSONObject(jsonResult)
                                                        .getJSONObject("data");
                                                String hash = data.optString("txHash", "");
                                                if (hash.isEmpty()) {
                                                    throw new IllegalStateException(
                                                            "No transaction hash returned");
                                                }
                                                deployedContractAddress =
                                                        data.optString("contractAddress", "");
                                                cb.submitted(hash);
                                            } catch (Exception e) {
                                                cb.fail(e.getMessage());
                                            }
                                        });
                                    }
                                    @Override public void onError(final String error) {
                                        mainHandler.post(() -> cb.fail(error));
                                    }
                                });
                    } catch (Exception e) {
                        cb.fail(e.getMessage());
                    }
                }));
        deployedContractAddress = null;
        stepsDialog = new TxStepsDialog(getActivity(), jsonViewModel, walletAddress,
                jsonViewModel.lang("create-token-status", "Create Token Status"),
                base, steps,
                this::buildContractAddressBlock,
                () -> {
                    stepsDialog = null;
                    if (getView() != null) resetForm();
                });
        stepsDialog.show();
    }

    private String deployedContractAddress;
    private TxStepsDialog stepsDialog;
    private GasChipController gasChip;

    private JSONObject deployPayload(String name, String symbol, int decimals, String supply)
            throws Exception {
        JSONObject p = new JSONObject();
        p.put("name", name);
        p.put("symbol", symbol);
        p.put("decimals", decimals);
        p.put("totalSupply", supply);
        return p;
    }

    /** Desktop scheduleCreateTokenGasEstimate: 2 s debounce on every
     *  input edit; nothing is requested until the form validates. */
    private void scheduleGasEstimate() {
        if (gasChip == null) return;
        gasChip.schedule(() -> {
            String name = text(nameEditText);
            String symbol = text(symbolEditText);
            String supply = text(supplyEditText);
            int decimals = decimalsSpinner.getSelectedItemPosition() + 1;
            if (name.isEmpty() || symbol.isEmpty() || !isValidSupply(supply, decimals)
                    || containsUnsafeText(name) || containsUnsafeText(symbol)) {
                return null;
            }
            try { return deployPayload(name, symbol, decimals, supply); } catch (Exception e) { return null; }
        });
    }

    private android.text.TextWatcher gasWatcher() {
        return new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) { scheduleGasEstimate(); }
        };
    }

    @Override
    public void onDestroyView() {
        if (stepsDialog != null) { stepsDialog.dismiss(); stepsDialog = null; }
        super.onDestroyView();
    }

    /** Desktop onAllDone panel inside the steps dialog: bold "Token
     *  contract address" + copy / block-explorer buttons + the full
     *  address (monospace, selectable). */
    private View buildContractAddressBlock(LayoutInflater inflater) {
        final String addr = deployedContractAddress;
        if (addr == null || addr.isEmpty() || getContext() == null) return null;
        android.widget.LinearLayout wrap = new android.widget.LinearLayout(getContext());
        wrap.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.LinearLayout header = new android.widget.LinearLayout(getContext());
        header.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView label = new TextView(getContext());
        label.setText(jsonViewModel.lang("token-contract-address", "Token contract address"));
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(13);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(label, new android.widget.LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        int size = (int) (36 * getResources().getDisplayMetrics().density);
        int pad = (int) (6 * getResources().getDisplayMetrics().density);
        android.widget.ImageButton copy = new android.widget.ImageButton(getContext());
        copy.setImageResource(R.drawable.copy_outline);
        copy.setBackgroundResource(R.drawable.image_selector);
        copy.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        copy.setPadding(pad, pad, pad, pad);
        copy.setImageTintList(android.content.res.ColorStateList.valueOf(
                getResources().getColor(R.color.colorCommon6)));
        copy.setOnClickListener(v -> com.quantumswap.app.utils.SecureClipboard
                .copyAddress(getContext(), "contractAddress", addr));
        header.addView(copy, new android.widget.LinearLayout.LayoutParams(size, size));
        android.widget.ImageButton scan = new android.widget.ImageButton(getContext());
        scan.setImageResource(R.drawable.address_explore);
        scan.setBackgroundResource(R.drawable.image_selector);
        scan.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        scan.setPadding(pad, pad, pad, pad);
        scan.setImageTintList(android.content.res.ColorStateList.valueOf(
                getResources().getColor(R.color.colorCommon6)));
        scan.setOnClickListener(v -> {
            android.net.Uri u = com.quantumswap.app.networking.UrlBuilder.blockExplorerTokenUrl(addr);
            if (u == null) return;
            try {
                startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, u));
            } catch (Throwable ignore) { }
        });
        android.widget.LinearLayout.LayoutParams slp =
                new android.widget.LinearLayout.LayoutParams(size, size);
        slp.setMarginStart(pad);
        header.addView(scan, slp);
        wrap.addView(header);
        TextView content = new TextView(getContext());
        content.setText(addr);
        content.setTextIsSelectable(true);
        content.setTypeface(android.graphics.Typeface.MONOSPACE);
        content.setTextSize(11);
        content.setTextColor(getResources().getColor(R.color.colorCommon6));
        wrap.addView(content);
        return wrap;
    }

    private void resetForm() {
        nameEditText.setText("");
        symbolEditText.setText("");
        decimalsSpinner.setSelection(17);
        supplyEditText.setText("");
        setError(null);
    }

    private static String text(EditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }

    public interface OnTokenCreateCompleteListener {
        void onTokenCreateCompleteByBackArrow();
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        try {
            this.mListener = (OnTokenCreateCompleteListener) context;
        } catch (final ClassCastException e) {
            throw new ClassCastException(context.toString() + " ");
        }
    }
}
