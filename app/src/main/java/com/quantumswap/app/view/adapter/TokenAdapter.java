package com.quantumswap.app.view.adapter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.quantumswap.app.R;
import com.quantumswap.app.api.read.model.AccountTokenSummary;
import com.quantumswap.app.utils.CoinUtils;
import com.quantumswap.app.utils.GlobalMethods;

import java.util.ArrayList;
import java.util.List;

/**
 * RecyclerView adapter for the main screen token list. Each row mirrors the
 * desktop wallet's Symbol / Balance / Contract / Name columns.
 */
public class TokenAdapter extends RecyclerView.Adapter<TokenAdapter.TokenHolder> {

    private static final String TAG = "TokenAdapter";

    private final Context context;
    private List<AccountTokenSummary> tokens;

    public TokenAdapter(Context context, List<AccountTokenSummary> tokens) {
        this.context = context;
        this.tokens = tokens == null ? new ArrayList<AccountTokenSummary>() : tokens;
    }

    public void setTokens(List<AccountTokenSummary> tokens) {
        this.tokens = tokens == null ? new ArrayList<AccountTokenSummary>() : tokens;
        notifyDataSetChanged();
    }

    static class TokenHolder extends RecyclerView.ViewHolder {
        TextView symbolView;
        TextView balanceView;
        TextView contractView;
        TextView nameView;

        TokenHolder(View itemView) {
            super(itemView);
            symbolView = itemView.findViewById(R.id.textView_token_adapter_symbol);
            balanceView = itemView.findViewById(R.id.textView_token_adapter_balance);
            contractView = itemView.findViewById(R.id.textView_token_adapter_contract);
            nameView = itemView.findViewById(R.id.textView_token_adapter_name);
        }
    }

    @Override
    public TokenHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.token_adapter, parent, false);
        return new TokenHolder(v);
    }

    @Override
    public void onBindViewHolder(TokenHolder holder, @SuppressLint("RecyclerView") final int position) {
        try {
            final AccountTokenSummary token = tokens.get(position);

            holder.symbolView.setText(safe(token.getSymbol()));
            holder.nameView.setText(safe(token.getName()));

            int decimals = token.getDecimals() == null ? 18 : token.getDecimals();
            String balanceString = CoinUtils.formatUnits(safe(token.getTokenBalance()), decimals);
            holder.balanceView.setText(balanceString);

            final String contract = safe(token.getContractAddress());
            holder.contractView.setText(shortAddress(contract));
            holder.contractView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        // Contract address comes from the
                        // scan-API JSON which a hostile RPC operator
                        // controls. UrlBuilder rejects anything not
                        // matching ^0x[0-9a-fA-F]{64}$ rather than
                        // pivoting Chrome to an attacker URL.
                        Uri u = com.quantumswap.app.networking.UrlBuilder
                                .blockExplorerAccountUrl(contract);
                        if (u == null) return;
                        context.startActivity(new Intent(Intent.ACTION_VIEW, u));
                    } catch (Exception ex) {
                        GlobalMethods.ExceptionError(context, TAG, ex);
                    }
                }
            });
        } catch (Exception ex) {
            GlobalMethods.ExceptionError(context, TAG, ex);
        }
    }

    @Override
    public int getItemCount() {
        return tokens == null ? 0 : tokens.size();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String shortAddress(String address) {
        if (address == null || address.length() < 12) {
            return safe(address);
        }
        return address.substring(0, 6) + "..." + address.substring(address.length() - 4);
    }
}
