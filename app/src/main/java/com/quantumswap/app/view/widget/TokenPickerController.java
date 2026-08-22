package com.quantumswap.app.view.widget;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.quantumswap.app.R;
import com.quantumswap.app.api.read.model.AccountTokenSummary;
import com.quantumswap.app.utils.GlobalMethods;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Shared token picker for the DEX screens (Swap / Liquidity / Pools),
 * ported from the desktop app's token picker (token-picker.ts +
 * overrides.css): a dropdown-styled trigger button ("Select token")
 * that opens a modal picker dialog with a search box (which doubles as
 * the custom-contract input), a "Show unrecognized tokens" checkbox,
 * and a flat row list (marker + symbol + badge / name / address /
 * balance).
 *
 * <p>Differences from desktop, kept deliberately:
 * <ul>
 *   <li>The native coin "Q" is listed and preselected (the Android
 *       bridge maps "Q" to the active release's wrapped-Q contract;
 *       desktop instead hides native Q on swap and trades WQ
 *       directly).</li>
 *   <li>With {@code alwaysIncludeRecognized} (the swap "To" side) the
 *       recognized allow-list is listed even when the account holds
 *       none of a token; such placeholder rows resolve their decimals
 *       through the bridge before use ({@link #needsMetadata()}).</li>
 *   <li>Custom contracts are not resolved inside the dialog; the
 *       existing pre-quote {@code swapGetTokenMetadata} flow does
 *       that, exactly as before.</li>
 * </ul></p>
 *
 * <p>{@link #getTokenValue()} returns the bridge-side token value:
 * the literal {@code "Q"} for the native coin or a 0x contract
 * address. Decimals default to 18 for Q and unresolved entries;
 * callers resolve exact custom/placeholder decimals through the
 * bridge and push them back via {@link #setResolvedMeta}.</p>
 */
public class TokenPickerController {

    /** Desktop token-picker-core.ts: 64 hex chars, not EVM's 40. */
    private static final String TOKEN_ADDRESS_REGEX = "^0x[0-9a-fA-F]{64}$";

    private static final class Item {
        String value;          // "Q" or 0x contract address
        String symbol;
        String name;
        Integer decimals;      // null = unresolved (placeholder/custom)
        String balanceText;    // null renders as em dash
        boolean recognized;
        boolean custom;
        boolean placeholder;   // allow-list row the account doesn't hold
    }

    private final Context context;
    private final Button trigger;
    private final EditText customField;
    private final String walletAddress;
    private final String customLabel;
    private final boolean alwaysIncludeRecognized;

    private final List<Item> recognizedItems = new ArrayList<>();
    private final List<Item> unrecognizedItems = new ArrayList<>();
    private Item selected;
    private boolean showUnrecognized;
    private Runnable onChanged;

    // Resolved metadata for the current custom/placeholder entry
    // (null = unresolved). Keyed by address so switching selections
    // invalidates it naturally.
    private String resolvedCustomAddress;
    private String resolvedCustomSymbol;
    private int resolvedCustomDecimals = 18;

    public TokenPickerController(Context context, Button trigger, EditText customField,
                                 String walletAddress, String customLabel) {
        this(context, trigger, customField, walletAddress, customLabel, false);
    }

    public TokenPickerController(Context context, Button trigger, EditText customField,
                                 String walletAddress, String customLabel,
                                 boolean alwaysIncludeRecognized) {
        this(context, trigger, customField, walletAddress, customLabel,
                alwaysIncludeRecognized, true);
    }

    public TokenPickerController(Context context, Button trigger, EditText customField,
                                 String walletAddress, String customLabel,
                                 boolean alwaysIncludeRecognized, boolean preselectNative) {
        this.context = context;
        this.trigger = trigger;
        this.customField = customField;
        this.walletAddress = walletAddress;
        this.customLabel = customLabel;
        this.alwaysIncludeRecognized = alwaysIncludeRecognized;

        // The custom field is a hidden value store now: the address is
        // entered through the picker dialog's search box.
        customField.setVisibility(View.GONE);

        buildItems();

        // Legacy pickers (Liquidity / Pools) preselect native Q so
        // getTokenValue() always has a value (old spinner position 0);
        // the Swap screen follows desktop and starts unselected with
        // the "Select token" placeholder.
        if (preselectNative) {
            selected = nativeItem();
        }
        trigger.setText(displayLabel(selected));
        trigger.setOnClickListener(v -> openDialog());
    }

    /** True once the user (or a preselect) has chosen an entry. */
    public boolean hasSelection() {
        return selected != null;
    }

    /** Opaque selection snapshot for the swap-direction flip. */
    public Object captureSelection() {
        return selected;
    }

    /** Restore a snapshot captured from this or a sibling picker
     *  (the swap flip exchanges the two sides' selections). */
    public void restoreSelection(Object snapshot) {
        selected = (snapshot instanceof Item) ? (Item) snapshot : null;
        if (selected != null && selected.custom) {
            customField.setText(selected.value);
        }
        trigger.setText(displayLabel(selected));
    }

    private Item nativeItem() {
        Item q = new Item();
        q.value = "Q";
        q.symbol = "Q";
        q.name = "QuantumCoin";
        q.decimals = 18;
        q.recognized = true;
        q.balanceText = balanceForValue(walletAddress, "Q");
        return q;
    }

    private void buildItems() {
        recognizedItems.clear();
        unrecognizedItems.clear();
        Set<String> held = new HashSet<>();

        if (GlobalMethods.CURRENT_WALLET_TOKEN_LIST != null
                && Objects.equals(GlobalMethods.CURRENT_WALLET_TOKEN_LIST_ADDRESS, walletAddress)) {
            List<AccountTokenSummary> filtered = com.quantumswap.app.tokens
                    .StablecoinImpersonatorFilter.filter(GlobalMethods.CURRENT_WALLET_TOKEN_LIST);
            for (AccountTokenSummary t : filtered) {
                if (t == null || t.getContractAddress() == null) continue;
                Item item = new Item();
                item.value = t.getContractAddress();
                item.symbol = t.getSymbol() == null || t.getSymbol().isEmpty()
                        ? shortAddress(t.getContractAddress()) : t.getSymbol();
                item.name = t.getName() == null ? "" : t.getName();
                item.decimals = t.getDecimals();
                item.balanceText = formatBalance(t.getTokenBalance(), t.getDecimals());
                item.recognized = com.quantumswap.app.tokens.RecognizedTokens
                        .isRecognized(t.getContractAddress());
                held.add(t.getContractAddress().toLowerCase(Locale.ROOT));
                (item.recognized ? recognizedItems : unrecognizedItems).add(item);
            }
        }

        if (alwaysIncludeRecognized) {
            for (String addr : com.quantumswap.app.tokens.RecognizedTokens.LISTED) {
                if (held.contains(addr.toLowerCase(Locale.ROOT))) continue;
                Item item = new Item();
                item.value = addr;
                item.symbol = com.quantumswap.app.tokens.RecognizedTokens.displaySymbol(addr);
                item.name = "";
                item.decimals = null; // bridge-resolved before use
                item.recognized = true;
                item.placeholder = true;
                recognizedItems.add(item);
            }
        }
    }

    // ---------------------------------------------------------------
    // Dialog
    // ---------------------------------------------------------------

    private void openDialog() {
        buildItems();
        View dialogView = LayoutInflater.from(context)
                .inflate(R.layout.token_picker_dialog, null);
        TextView title = dialogView.findViewById(R.id.textView_token_picker_title);
        TextView close = dialogView.findViewById(R.id.textView_token_picker_close);
        EditText search = dialogView.findViewById(R.id.editText_token_picker_search);
        CheckBox unrecognizedCheck =
                dialogView.findViewById(R.id.checkBox_token_picker_unrecognized);
        TextView status = dialogView.findViewById(R.id.textView_token_picker_status);
        LinearLayout list = dialogView.findViewById(R.id.linearLayout_token_picker_list);

        title.setText("Select a token");
        search.setHint("Search name / symbol or paste address");
        unrecognizedCheck.setText("Show unrecognized tokens");
        unrecognizedCheck.setVisibility(
                unrecognizedItems.isEmpty() ? View.GONE : View.VISIBLE);
        // Desktop resets the toggle on every open.
        showUnrecognized = false;
        unrecognizedCheck.setChecked(false);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(dialogView)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    new ColorDrawable(Color.TRANSPARENT));
        }

        renderList(list, status, "", dialog);
        close.setOnClickListener(v -> dialog.dismiss());
        unrecognizedCheck.setOnCheckedChangeListener((btn, checked) -> {
            showUnrecognized = checked;
            renderList(list, status, search.getText().toString().trim(), dialog);
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                renderList(list, status, s.toString().trim(), dialog);
            }
        });

        dialog.show();
    }

    private void renderList(LinearLayout list, TextView status, String query,
                            AlertDialog dialog) {
        list.removeAllViews();
        String q = query.toLowerCase(Locale.ROOT);

        List<Item> pool = new ArrayList<>();
        pool.add(nativeItem());
        pool.addAll(recognizedItems);
        if (showUnrecognized) pool.addAll(unrecognizedItems);

        int shown = 0;
        for (Item item : pool) {
            if (!q.isEmpty() && !matches(item, q)) continue;
            list.addView(buildRow(item, dialog));
            shown++;
        }

        if (shown > 0) {
            status.setText("");
            return;
        }
        // Desktop: an unmatched 64-hex-address query becomes a
        // selectable custom-contract row; metadata resolves later
        // through the bridge (needsMetadata / swapGetTokenMetadata).
        if (query.matches(TOKEN_ADDRESS_REGEX)) {
            Item custom = new Item();
            custom.value = query;
            custom.symbol = "Token";
            custom.name = customLabel;
            custom.custom = true;
            list.addView(buildRow(custom, dialog));
            status.setText("");
        } else {
            status.setText("No tokens match your search.");
        }
    }

    private boolean matches(Item item, String q) {
        return (item.symbol != null && item.symbol.toLowerCase(Locale.ROOT).contains(q))
                || (item.name != null && item.name.toLowerCase(Locale.ROOT).contains(q))
                || (item.value != null && item.value.toLowerCase(Locale.ROOT).contains(q));
    }

    private View buildRow(final Item item, final AlertDialog dialog) {
        View row = LayoutInflater.from(context)
                .inflate(R.layout.token_picker_row, null);
        TextView symbol = row.findViewById(R.id.textView_token_row_symbol);
        TextView badge = row.findViewById(R.id.textView_token_row_badge);
        TextView name = row.findViewById(R.id.textView_token_row_name);
        TextView address = row.findViewById(R.id.textView_token_row_address);
        TextView balance = row.findViewById(R.id.textView_token_row_balance);

        symbol.setText(item.symbol == null ? "" : item.symbol);
        if (item.recognized) {
            badge.setText("default");
            badge.setTextColor(0xFF7EE6A0);
            badge.setBackgroundResource(R.drawable.token_badge_default_bg);
        } else {
            badge.setText("unrecognized");
            badge.setTextColor(0xFFFFC16E);
            badge.setBackgroundResource(R.drawable.token_badge_unrecognized_bg);
        }
        if (item.name == null || item.name.isEmpty()) {
            name.setVisibility(View.GONE);
        } else {
            name.setText(item.name);
        }
        if ("Q".equals(item.value)) {
            address.setVisibility(View.GONE);
        } else {
            address.setText(item.value);
        }
        balance.setText(item.balanceText == null ? "—" : item.balanceText);

        row.setOnClickListener(v -> {
            dialog.dismiss();
            select(item);
        });
        return row;
    }

    private void select(Item item) {
        selected = item;
        if (item.custom) {
            customField.setText(item.value);
        }
        trigger.setText(displayLabel(item));
        if (onChanged != null) onChanged.run();
    }

    private String displayLabel(Item item) {
        if (item == null) return "Select token";
        if ("Q".equals(item.value)) return item.symbol;
        return item.symbol + " (" + shortAddress(item.value) + ")";
    }

    // ---------------------------------------------------------------
    // Public API (unchanged from the spinner-based controller)
    // ---------------------------------------------------------------

    public void setOnChanged(Runnable onChanged) {
        this.onChanged = onChanged;
    }

    public boolean isCustomSelected() {
        return selected != null && selected.custom;
    }

    /** "Q", a listed token's contract address, or the custom input;
     *  empty string while nothing is selected ("Select token"). */
    public String getTokenValue() {
        if (selected == null) return "";
        if (selected.custom) {
            return customField.getText() == null
                    ? "" : customField.getText().toString().trim();
        }
        return selected.value;
    }

    public int getDecimals() {
        if (selected == null) return 18;
        if (selected.decimals != null) return selected.decimals;
        if (metaResolvedForCurrentSelection()) return resolvedCustomDecimals;
        return 18;
    }

    public String getSymbol() {
        if (selected == null) return "";
        if (selected.custom && metaResolvedForCurrentSelection()
                && resolvedCustomSymbol != null && !resolvedCustomSymbol.isEmpty()) {
            return resolvedCustomSymbol;
        }
        return selected.symbol == null || selected.symbol.isEmpty()
                ? shortAddress(getTokenValue()) : selected.symbol;
    }

    /** True when the selection is a custom address, or an allow-list
     *  placeholder the account does not hold, whose decimals/symbol
     *  have not been resolved through the bridge yet. */
    public boolean needsMetadata() {
        if (selected == null) return false;
        return (selected.custom || selected.placeholder)
                && !metaResolvedForCurrentSelection();
    }

    /** Cache bridge-resolved metadata for the current custom or
     *  placeholder address. */
    public void setResolvedMeta(String address, String symbol, int decimals) {
        resolvedCustomAddress = address == null ? null : address.toLowerCase(Locale.ROOT);
        resolvedCustomSymbol = symbol;
        resolvedCustomDecimals = decimals;
    }

    private boolean metaResolvedForCurrentSelection() {
        String current = getTokenValue();
        return resolvedCustomAddress != null && current != null
                && resolvedCustomAddress.equalsIgnoreCase(current);
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /** Desktop getSwapBalanceForSymbol: wallet token-list balance for
     *  a picker value, "0" when unknown (incl. native Q, whose live
     *  balance is not cached globally on Android). */
    public static String balanceForValue(String walletAddress, String value) {
        if (value == null || value.isEmpty()) return "0";
        if ("Q".equals(value)) {
            // Native coin: HomeActivity's balance fetch caches the
            // formatted value (desktop reads currentAccountDetails).
            String cached = GlobalMethods.CURRENT_WALLET_BALANCE_FORMATTED;
            if (cached != null && Objects.equals(
                    GlobalMethods.CURRENT_WALLET_BALANCE_ADDRESS, walletAddress)) {
                return cached;
            }
            return "0";
        }
        if (GlobalMethods.CURRENT_WALLET_TOKEN_LIST != null
                && Objects.equals(GlobalMethods.CURRENT_WALLET_TOKEN_LIST_ADDRESS, walletAddress)) {
            for (AccountTokenSummary t : GlobalMethods.CURRENT_WALLET_TOKEN_LIST) {
                if (t != null && value.equalsIgnoreCase(t.getContractAddress())) {
                    String b = formatBalance(t.getTokenBalance(), t.getDecimals());
                    return b == null ? "0" : b;
                }
            }
        }
        return "0";
    }

    private static String shortAddress(String v) {
        if (v == null) return "";
        return v.length() > 14 ? v.substring(0, 8) + "..." + v.substring(v.length() - 4) : v;
    }

    /** Scan-API hex balance -> human units; null on any parse issue
     *  (renders as an em dash, like desktop's missing balance). */
    private static String formatBalance(String hexBalance, Integer decimals) {
        try {
            if (hexBalance == null || hexBalance.isEmpty()) return null;
            String clean = hexBalance.startsWith("0x")
                    ? hexBalance.substring(2) : hexBalance;
            BigInteger raw = new BigInteger(clean, 16);
            int scale = decimals == null ? 18 : decimals;
            BigDecimal value = new BigDecimal(raw, scale);
            BigDecimal stripped = value.stripTrailingZeros();
            if (stripped.scale() > 6) {
                stripped = stripped.setScale(6, java.math.RoundingMode.DOWN)
                        .stripTrailingZeros();
            }
            return stripped.toPlainString();
        } catch (Exception e) {
            return null;
        }
    }
}
