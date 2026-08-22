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

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.quantumswap.app.R;
import com.quantumswap.app.bridge.BridgeCallback;
import com.quantumswap.app.tokens.StablecoinImpersonatorFilter;
import com.quantumswap.app.utils.DexPayloads;
import com.quantumswap.app.view.dialog.DexUnlockPrompt;
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

    private static final long DEPLOY_TOKEN_DEFAULT_GAS = 6000000L;

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

    private String[] sessionKeys;
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
        java.util.List<TxStepsDialog.Step> steps = new java.util.ArrayList<>();
        steps.add(new TxStepsDialog.Step(stepLabel,
                cb -> runDeployStep(name, symbol, decimals, supply, cb)));
        sessionKeys = null;
        deployedContractAddress = null;
        new TxStepsDialog(getContext(),
                jsonViewModel.lang("create-token-status", "Create Token Status"),
                jsonViewModel.lang("transaction-id", "Transaction ID"),
                jsonViewModel.getOkByLangValues(),
                steps,
                () -> {
                    sessionKeys = null;
                    showContractAddressDialog();
                    resetForm();
                }).show();
    }

    private String deployedContractAddress;

    private void runDeployStep(final String name, final String symbol, final int decimals,
                               final String supply, final TxStepsDialog.StepCallbacks cb) {
        // Desktop review copy: "Create Token <name> (<symbol>)" with the
        // Total Supply row; the shared unlock prompt is the password gate.
        String message = jsonViewModel.lang("create-token", "Create Token")
                + " " + name + " (" + symbol + ")\n\n"
                + jsonViewModel.lang("token-total-supply", "Total Supply")
                + ": " + supply + " " + symbol;
        new AlertDialog.Builder(getContext())
                .setTitle(jsonViewModel.lang("create-token", "Create Token"))
                .setMessage(message)
                .setPositiveButton(jsonViewModel.getOkByLangValues(), (d, w) ->
                        withSessionKeys(() -> estimateAndDeploy(name, symbol, decimals, supply, cb), cb))
                .setNegativeButton(jsonViewModel.getCancelByLangValues(), (d, w) -> {
                    d.dismiss();
                    cb.cancelled();
                })
                .setCancelable(false)
                .show();
    }

    private void withSessionKeys(final Runnable onReady,
                                 final TxStepsDialog.StepCallbacks cb) {
        if (sessionKeys != null) {
            onReady.run();
            return;
        }
        DexUnlockPrompt.show(getActivity(), jsonViewModel, password -> {
            final Context appCtx = getActivity().getApplicationContext();
            new Thread(() -> {
                try {
                    final String[] keys = DexUnlockPrompt.loadWalletKeys(appCtx, walletAddress);
                    mainHandler.post(() -> {
                        sessionKeys = keys;
                        onReady.run();
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> cb.fail(e.getMessage()));
                }
            }).start();
        }, cb::cancelled);
    }

    private void estimateAndDeploy(final String name, final String symbol, final int decimals,
                                   final String supply, final TxStepsDialog.StepCallbacks cb) {
        try {
            cb.status(jsonViewModel.lang("pleaseWaitEstimatingGas",
                    "Please wait, estimating gas..."));
            JSONObject estimate = DexPayloads.base();
            estimate.put("name", name);
            estimate.put("symbol", symbol);
            estimate.put("decimals", decimals);
            estimate.put("totalSupply", supply);
            estimate.put("fromAddress", walletAddress);
            KeyViewModel.getBridge().dexCallAsync("tokensEstimateDeployGas", estimate,
                    new BridgeCallback() {
                        @Override public void onResult(String jsonResult) {
                            long gasLimit = DEPLOY_TOKEN_DEFAULT_GAS;
                            try {
                                JSONObject result = new JSONObject(jsonResult);
                                long v = Long.parseLong(result.getJSONObject("data")
                                        .getString("gasLimit"));
                                // Desktop pads estimates the same way.
                                gasLimit = Math.max((v * 12) / 10, 100000L);
                            } catch (Exception ignore) { }
                            submitDeploy(name, symbol, decimals, supply, gasLimit, cb);
                        }
                        @Override public void onError(String error) {
                            submitDeploy(name, symbol, decimals, supply,
                                    DEPLOY_TOKEN_DEFAULT_GAS, cb);
                        }
                    });
        } catch (Exception e) {
            cb.fail(e.getMessage());
        }
    }

    private void submitDeploy(final String name, final String symbol, final int decimals,
                              final String supply, final long gasLimit,
                              final TxStepsDialog.StepCallbacks cb) {
        mainHandler.post(() -> {
            if (getActivity() == null) return;
            try {
                cb.status(jsonViewModel.lang("create-token-progress", "Creating token."));
                JSONObject payload = DexPayloads.withKeys(getContext(),
                        sessionKeys[0], sessionKeys[1]);
                payload.put("name", name);
                payload.put("symbol", symbol);
                payload.put("decimals", decimals);
                payload.put("totalSupply", supply);
                payload.put("gasLimit", gasLimit);
                KeyViewModel.getBridge().dexCallAsync("tokensSubmitCreate", payload,
                        new BridgeCallback() {
                            @Override public void onResult(final String jsonResult) {
                                mainHandler.post(() -> {
                                    if (getActivity() == null) return;
                                    try {
                                        JSONObject result = new JSONObject(jsonResult);
                                        JSONObject data = result.getJSONObject("data");
                                        deployedContractAddress =
                                                data.optString("contractAddress", "");
                                        cb.txHash(data.optString("txHash", ""));
                                        cb.done();
                                    } catch (Exception e) {
                                        cb.fail(e.getMessage());
                                    }
                                });
                            }
                            @Override public void onError(final String error) {
                                mainHandler.post(() -> {
                                    if (getActivity() == null) return;
                                    cb.fail(error);
                                });
                            }
                        });
            } catch (Exception e) {
                cb.fail(e.getMessage());
            }
        });
    }

    /** Desktop onAllDone panel: "Token contract address" + the full
     *  address (monospace, selectable). */
    private void showContractAddressDialog() {
        if (deployedContractAddress == null || deployedContractAddress.isEmpty()
                || getContext() == null) {
            return;
        }
        TextView content = new TextView(getContext());
        content.setText(deployedContractAddress);
        content.setTextIsSelectable(true);
        content.setTypeface(android.graphics.Typeface.MONOSPACE);
        content.setTextSize(13);
        content.setTextColor(0xFFE0E0E6);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        content.setPadding(pad, pad / 2, pad, 0);
        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setTitle(jsonViewModel.lang("token-contract-address",
                        "Token contract address"))
                .setView(content)
                .setPositiveButton(jsonViewModel.getOkByLangValues(), (d, w) -> d.dismiss())
                .create();
        dialog.show();
    }

    private void resetForm() {
        nameEditText.setText("");
        symbolEditText.setText("");
        decimalsSpinner.setSelection(17);
        supplyEditText.setText("");
        setError(null);
        deployedContractAddress = null;
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
