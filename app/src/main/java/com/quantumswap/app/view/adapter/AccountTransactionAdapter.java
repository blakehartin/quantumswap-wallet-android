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
import com.quantumswap.app.api.read.model.AccountTransactionSummary;
import com.quantumswap.app.utils.AccountTransactionUi;
import com.quantumswap.app.utils.CoinUtils;
import com.quantumswap.app.utils.GlobalMethods;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import java.math.BigInteger;
import java.util.List;
import java.util.Locale;

public class AccountTransactionAdapter extends
        Adapter<AccountTransactionAdapter.DataObjectHolder> {

    private static final String TAG = "AccountTransactionAdapter";

    public List<AccountTransactionSummary> accountTransactionSummaries;

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

    public AccountTransactionAdapter(Context context,
                                     List<AccountTransactionSummary> accountTransactionSummaries,
                                     String walletAddress) {
        this.context = context;
        this.accountTransactionSummaries = accountTransactionSummaries;
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
            AccountTransactionSummary txn = accountTransactionSummaries.get(position);

            String hash = AccountTransactionUi.safeAddress(txn.getHash());
            String from = AccountTransactionUi.safeAddress(txn.getFrom());
            String to = AccountTransactionUi.safeAddress(txn.getTo());
            String rawValue = txn.getValue() != null ? txn.getValue().toString() : null;
            String rawDate = txn.getCreatedAt() != null ? txn.getCreatedAt().toString() : null;

            boolean outgoing = walletAddress != null && from.length() > 0
                    && walletAddress.equalsIgnoreCase(from);
            boolean success = AccountTransactionUi.isCompletedSuccessful(txn);
            if (holder.imageViewFailed != null) {
                holder.imageViewFailed.setVisibility(success ? View.GONE : View.VISIBLE);
            }
            holder.imageViewInOut.setImageResource(outgoing
                    ? R.drawable.arrow_up_circle_outline
                    : R.drawable.arrow_down_circle_outline);

            // Completed list keeps the Date column. Force VISIBLE in case this row view
            // was previously bound by the pending adapter (which hides these).
            if (holder.textViewDate != null) {
                holder.textViewDate.setVisibility(View.VISIBLE);
            }
            if (holder.viewDateSeparator != null) {
                holder.viewDateSeparator.setVisibility(View.VISIBLE);
            }

            // The scan API returns createdAt as an ISO-8601 offset
            // datetime (typically "...Z" / UTC). Users expect to see
            // their own wall clock, not the server's, so we convert
            // the parsed instant to the device's default zone via
            // atZoneSameInstant(...) and let the formatter render a
            // localized short zone abbreviation via the "z" token
            // (e.g. "PST", "IST"). The previous version printed the
            // server's offset fields with a hardcoded " GMT" suffix,
            // which was misleading whenever the server offset wasn't
            // actually UTC. Locale.getDefault() makes the day-of-week
            // and month abbreviations follow the device language.
            try {
                if (rawDate != null && rawDate.length() > 0) {
                    ZonedDateTime localDateTime = OffsetDateTime.parse(rawDate)
                            .atZoneSameInstant(ZoneId.systemDefault());
                    String formattedDateString = localDateTime.format(
                            DateTimeFormatter.ofPattern(
                                    "E, dd MMM yyyy HH:mm:ss z", Locale.getDefault()));
                    holder.textViewDate.setText(formattedDateString);
                } else {
                    holder.textViewDate.setText("");
                }
            } catch (Exception e) {
                holder.textViewDate.setText("");
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
                        // Hash comes from scan-API JSON; gate
                        // through UrlBuilder so a malformed value is
                        // a no-op rather than an injection vector.
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
        return this.accountTransactionSummaries == null ? 0 : this.accountTransactionSummaries.size();
    }
}
