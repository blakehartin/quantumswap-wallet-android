package com.quantumswap.app.view.adapter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView.Adapter;
import androidx.recyclerview.widget.RecyclerView.ViewHolder;

import com.quantumswap.app.R;
import com.quantumswap.app.api.read.model.AccountPendingTransactionSummary;
import com.quantumswap.app.utils.AccountTransactionUi;
import com.quantumswap.app.utils.CoinUtils;
import com.quantumswap.app.utils.GlobalMethods;

import java.math.BigInteger;
import java.util.List;

public class AccountPendingTransactionAdapter extends
        Adapter<AccountPendingTransactionAdapter.DataObjectHolder> {

    private static final String TAG = "AccountPendingTransactionAdapter";

    public List<AccountPendingTransactionSummary> accountPendingTransactionSummaries;

    public static String walletAddress;

    private Context context;

    class DataObjectHolder extends ViewHolder {

        ImageView imageViewFailed;
        ImageView imageViewInOut;

        TextView textViewTransHash;
        TextView textViewDate;
        View viewDateSeparator;

        TextView textViewFrom;
        TextView textViewTo;

        TextView textViewQuantity;

        public DataObjectHolder(View itemView) {
            super(itemView);
            try{
                this.imageViewFailed = (ImageView) itemView.findViewById(R.id.imageView_account_transactions_adapter_failed);
                this.imageViewInOut = (ImageView) itemView.findViewById(R.id.imageView_account_transactions_adapter_in_out);
                this.textViewQuantity = (TextView) itemView.findViewById(R.id.textView_account_transactions_adapter_quantity);
                this.textViewDate = (TextView) itemView.findViewById(R.id.textView_account_transactions_adapter_date);
                this.viewDateSeparator = itemView.findViewById(R.id.view_account_transactions_adapter_date_separator);
                this.textViewFrom = (TextView) itemView.findViewById(R.id.textView_account_transactions_adapter_from);
                this.textViewTo = (TextView) itemView.findViewById(R.id.textView_account_transactions_adapter_to);
                this.textViewTransHash = (TextView) itemView.findViewById(R.id.textView_account_transactions_adapter_trans_hash);
            } catch(Exception ex){
                GlobalMethods.ExceptionError(context, TAG, ex);
            }
        }
    }

    public AccountPendingTransactionAdapter(Context context,
                                     List<AccountPendingTransactionSummary> accountPendingTransactionSummaries,
                                     String walletAddress) {
        this.context = context;
        this.accountPendingTransactionSummaries = accountPendingTransactionSummaries;
        this.walletAddress = walletAddress;
    }

    @Override
    public DataObjectHolder onCreateViewHolder(ViewGroup viewGroup, int viewType) {
        return new DataObjectHolder(LayoutInflater.from(viewGroup.getContext()).inflate(
                R.layout.account_transactions_adapter, viewGroup, false));
    }

    @Override
    public void onBindViewHolder(DataObjectHolder holder, @SuppressLint("RecyclerView") final int position) {
        try {
            AccountPendingTransactionSummary txn = accountPendingTransactionSummaries.get(position);

            String hash = AccountTransactionUi.safeAddress(txn.getHash());
            String from = AccountTransactionUi.safeAddress(txn.getFrom());
            String to = AccountTransactionUi.safeAddress(txn.getTo());
            String rawValue = txn.getValue() != null ? txn.getValue().toString() : null;

            // Desktop: pending rows always use the outgoing (up) template.
            if (holder.imageViewFailed != null) {
                holder.imageViewFailed.setVisibility(View.GONE);
            }
            holder.imageViewInOut.setImageResource(R.drawable.arrow_up_circle_outline);

            // Pending list intentionally omits the Date column; a completed row reused
            // by the RecyclerView may still have it visible, so force hide here.
            if (holder.textViewDate != null) {
                holder.textViewDate.setVisibility(View.GONE);
            }
            if (holder.viewDateSeparator != null) {
                holder.viewDateSeparator.setVisibility(View.GONE);
            }

            try {
                if (rawValue != null && rawValue.length() > 0) {
                    BigInteger valueBigInteger = new BigInteger(rawValue.replace("0x", ""), 16);
                    String wei = valueBigInteger.toString(10);
                    holder.textViewQuantity.setText(CoinUtils.formatWei(wei));
                } else {
                    holder.textViewQuantity.setText("0");
                }
            } catch (Exception e) {
                holder.textViewQuantity.setText("0");
            }

            holder.textViewTransHash.setText(hash.length() >= 7 ? hash.substring(0, 7) : hash);
            holder.textViewFrom.setText(from.length() >= 7 ? from.substring(0, 7) : from);
            holder.textViewTo.setText(to.length() >= 7 ? to.substring(0, 7) : to);

            if (hash.length() > 0) {
                holder.textViewTransHash.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // Hash flows from a pending-tx scan API
                        // response; UrlBuilder regex-validates before
                        // any browser handoff.
                        Uri u = com.quantumswap.app.networking.UrlBuilder
                                .blockExplorerTxUrl(hash);
                        if (u == null) return;
                        context.startActivity(new Intent(Intent.ACTION_VIEW, u));
                    }
                });
            }

            if (from.length() > 0) {
                holder.textViewFrom.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Uri u = com.quantumswap.app.networking.UrlBuilder
                                .blockExplorerAccountUrl(from);
                        if (u == null) return;
                        context.startActivity(new Intent(Intent.ACTION_VIEW, u));
                    }
                });
            }

            if (to.length() > 0) {
                holder.textViewTo.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Uri u = com.quantumswap.app.networking.UrlBuilder
                                .blockExplorerAccountUrl(to);
                        if (u == null) return;
                        context.startActivity(new Intent(Intent.ACTION_VIEW, u));
                    }
                });
            }

        } catch (Exception ex) {
            GlobalMethods.ExceptionError(context, TAG, ex);
        }
    }

    @Override
    public int getItemCount() {
        return this.accountPendingTransactionSummaries == null ? 0 : this.accountPendingTransactionSummaries.size();
    }
}
