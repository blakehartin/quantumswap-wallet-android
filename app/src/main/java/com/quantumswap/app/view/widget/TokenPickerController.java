package com.quantumswap.app.view.widget;

import android.content.Context;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;

import com.quantumswap.app.api.read.model.AccountTokenSummary;
import com.quantumswap.app.utils.GlobalMethods;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Shared token picker for the DEX screens (Swap / Liquidity / Pools).
 * Binds a Spinner listing the native coin ("Q"), the wallet's cached
 * recognized tokens (same source as the Send asset spinner), and a
 * trailing "Custom..." entry that reveals a contract-address EditText.
 *
 * <p>{@link #getTokenValue()} returns the bridge-side token value:
 * the literal {@code "Q"} for the native coin (the bridge maps it to
 * the active release's wrapped-Q contract) or a 0x contract address.
 * Decimals default to 18 for Q and custom entries; callers that need
 * exact custom-token decimals resolve them through the bridge's
 * {@code swapGetTokenMetadata} and push them back via
 * {@link #setResolvedMeta}.</p>
 */
public class TokenPickerController {

    private final Spinner spinner;
    private final EditText customField;
    private final List<AccountTokenSummary> tokens = new ArrayList<>();
    private Runnable onChanged;

    // Resolved metadata for the current custom entry (null = unresolved).
    private String resolvedCustomAddress;
    private String resolvedCustomSymbol;
    private int resolvedCustomDecimals = 18;

    public TokenPickerController(Context context, Spinner spinner, EditText customField,
                                 String walletAddress, String customLabel) {
        this.spinner = spinner;
        this.customField = customField;

        if (GlobalMethods.CURRENT_WALLET_TOKEN_LIST != null
                && Objects.equals(GlobalMethods.CURRENT_WALLET_TOKEN_LIST_ADDRESS, walletAddress)) {
            List<AccountTokenSummary> filtered = com.quantumswap.app.tokens
                    .StablecoinImpersonatorFilter.filter(GlobalMethods.CURRENT_WALLET_TOKEN_LIST);
            for (AccountTokenSummary t : filtered) {
                if (t == null || t.getContractAddress() == null) continue;
                if (com.quantumswap.app.tokens.RecognizedTokens
                        .isRecognized(t.getContractAddress())) {
                    tokens.add(t);
                }
            }
        }

        List<String> labels = new ArrayList<>();
        labels.add("Q");
        for (AccountTokenSummary t : tokens) {
            String sym = t.getSymbol() == null ? "" : t.getSymbol();
            String addr = t.getContractAddress();
            String shortAddr = addr.length() > 14
                    ? addr.substring(0, 8) + "..." + addr.substring(addr.length() - 4) : addr;
            labels.add(sym.isEmpty() ? shortAddr : sym + " (" + shortAddr + ")");
        }
        labels.add(customLabel);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                customField.setVisibility(isCustomSelected() ? View.VISIBLE : View.GONE);
                if (onChanged != null) onChanged.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    public void setOnChanged(Runnable onChanged) {
        this.onChanged = onChanged;
    }

    public boolean isCustomSelected() {
        return spinner.getSelectedItemPosition() == tokens.size() + 1;
    }

    /** "Q", a cached token's contract address, or the trimmed custom input. */
    public String getTokenValue() {
        int pos = spinner.getSelectedItemPosition();
        if (pos <= 0) return "Q";
        if (pos <= tokens.size()) return tokens.get(pos - 1).getContractAddress();
        return customField.getText() == null ? "" : customField.getText().toString().trim();
    }

    public int getDecimals() {
        int pos = spinner.getSelectedItemPosition();
        if (pos <= 0) return 18;
        if (pos <= tokens.size()) {
            Integer d = tokens.get(pos - 1).getDecimals();
            return d == null ? 18 : d;
        }
        if (metaResolvedForCurrentCustom()) return resolvedCustomDecimals;
        return 18;
    }

    public String getSymbol() {
        int pos = spinner.getSelectedItemPosition();
        if (pos <= 0) return "Q";
        if (pos <= tokens.size()) {
            String s = tokens.get(pos - 1).getSymbol();
            return (s == null || s.isEmpty()) ? shortValue() : s;
        }
        if (metaResolvedForCurrentCustom() && resolvedCustomSymbol != null
                && !resolvedCustomSymbol.isEmpty()) {
            return resolvedCustomSymbol;
        }
        return shortValue();
    }

    /** True when the selection is a custom address whose decimals/symbol
     *  have not been resolved through the bridge yet. */
    public boolean needsMetadata() {
        return isCustomSelected() && !metaResolvedForCurrentCustom();
    }

    /** Cache bridge-resolved metadata for the current custom address. */
    public void setResolvedMeta(String address, String symbol, int decimals) {
        resolvedCustomAddress = address == null ? null : address.toLowerCase();
        resolvedCustomSymbol = symbol;
        resolvedCustomDecimals = decimals;
    }

    private boolean metaResolvedForCurrentCustom() {
        String current = getTokenValue();
        return resolvedCustomAddress != null && current != null
                && resolvedCustomAddress.equalsIgnoreCase(current);
    }

    private String shortValue() {
        String v = getTokenValue();
        if (v == null) return "";
        return v.length() > 14 ? v.substring(0, 8) + "..." + v.substring(v.length() - 4) : v;
    }
}
