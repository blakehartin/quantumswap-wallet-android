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
import com.quantumswap.app.utils.GlobalMethods;
import com.quantumswap.app.viewmodel.KeyViewModel;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

public class WalletAdapter extends
        Adapter<WalletAdapter.DataObjectHolder> {

    private static final String TAG = "WalletAdapter";

    OnWalletItemClickListener clickListener;

    public Map<String, String> walletSummaries;

    public static String walletAddress;

    private Context context;

    class DataObjectHolder extends ViewHolder {
        TextView textViewAddress;
        ImageView imageViewExplore;
        ImageView imageViewRevealSeed;
        ImageView imageViewExport;
        View revealSeedContainer;

        public DataObjectHolder(View itemView) {
            super(itemView);
            try{
                this.textViewAddress = (TextView) itemView.findViewById(R.id.textView_waller_adapter_address);
                this.imageViewExplore = (ImageView) itemView.findViewById(R.id.imageView_wallet_adapter_explore);
                this.imageViewRevealSeed = (ImageView) itemView.findViewById(R.id.imageView_wallet_adapter_reveal_seed);
                this.imageViewExport = (ImageView) itemView.findViewById(R.id.imageView_wallet_adapter_export);
                this.revealSeedContainer = (View) this.imageViewRevealSeed.getParent();
            } catch(Exception ex){
                GlobalMethods.ExceptionError(context, TAG, ex);
            }
        }

       // @Override
       // public void onClick(View v) {
       //     clickListener.onWalletItemClick(v, getAdapterPosition());
       // }
    }

    public WalletAdapter(Context context, Map<String, String> walletSummaries) {
        this.context = context;
        this.walletSummaries = walletSummaries;
    }

    @Override
    public DataObjectHolder onCreateViewHolder(ViewGroup viewGroup, int viewType) {
        return new DataObjectHolder(LayoutInflater.from(viewGroup.getContext()).inflate(
                R.layout.wallet_adapter, viewGroup, false));
    }

    @Override
    public void onBindViewHolder(DataObjectHolder holder, @SuppressLint("RecyclerView") final int position) {
        try {

            String address = (walletSummaries.get(String.valueOf(position)).toString());

            holder.textViewAddress.setText(address.substring(2,7) + "..." + address.substring(address.length()-5,address.length()));

            holder.textViewAddress.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clickListener.onWalletItemClick(v, position);
                }
            });

            holder.imageViewExplore.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // Validate + percent-encode the address
                    // before letting the system browser see it.
                    Uri u = com.quantumswap.app.networking.UrlBuilder
                            .blockExplorerAccountUrl(address);
                    if (u == null) return;
                    context.startActivity(new Intent(Intent.ACTION_VIEW, u));
                }
            });

            holder.imageViewRevealSeed.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clickListener.onWalletRevealClick(v, position);
                }
            });

            String indexKey = String.valueOf(position);
            Boolean hasSeedBoxed = com.quantumswap.app.utils.PrefConnect
                    .WALLET_INDEX_HAS_SEED_MAP.get(indexKey);
            // Default to false on a missing map entry: every
            // create/restore path that produces a seed pushes
            // hasSeed=true into the map, so a missing entry
            // means "no seed". Safer to under-show the reveal
            // affordance than to over-show it on a key-only
            // import (which would route the user to a flow
            // that ultimately reveals an empty seed phrase).
            boolean hasSeed = hasSeedBoxed != null && hasSeedBoxed;
            if (holder.revealSeedContainer != null) {
                holder.revealSeedContainer.setVisibility(hasSeed ? View.VISIBLE : View.INVISIBLE);
            }

            if (holder.imageViewExport != null) {
                holder.imageViewExport.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (clickListener != null) {
                            clickListener.onWalletExportClick(v, position);
                        }
                    }
                });
            }

        }catch(Exception ex){
            GlobalMethods.ExceptionError(context, TAG, ex);
        }
    }

    @Override
    public int getItemCount() {
        return this.walletSummaries == null ? 0 : this.walletSummaries.size();
    }


    public void SetOnWalletItemClickListener(
            final OnWalletItemClickListener itemClickListener) {
        this.clickListener = itemClickListener;
    }

    public interface OnWalletItemClickListener {
        public void onWalletItemClick(View view, int position);
        public void onWalletRevealClick(View view, int position);
        public void onWalletExportClick(View view, int position);
    }
}
