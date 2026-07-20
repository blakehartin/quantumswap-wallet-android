package com.quantumswap.app.view.fragment;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.quantumswap.app.R;
import com.quantumswap.app.model.BlockchainNetwork;
import com.quantumswap.app.utils.GlobalMethods;
import com.quantumswap.app.viewmodel.JsonViewModel;

import java.util.List;

public class BlockchainNetworkFragment extends Fragment  {

    private static final String TAG = "BlockchainNetworkFragment";

    private OnBlockchainNetworkCompleteListener mBlockchainNetworkListener;

    public static BlockchainNetworkFragment newInstance() {
        BlockchainNetworkFragment fragment = new BlockchainNetworkFragment();
        return fragment;
    }

    public BlockchainNetworkFragment() {

    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.blockchain_network_fragment, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        try {
            assert getArguments() != null;

            String languageKey = getArguments().getString("languageKey");

            JsonViewModel jsonViewModel = new JsonViewModel(getContext(), languageKey);

            ImageButton backArrowImageButton = (ImageButton) getView().findViewById(R.id.imageButton_blockchain_network_back_arrow);
            TextView blockchainNetworkTitleTextView = (TextView) getView().findViewById(R.id.textview_blockchain_network_langValues_networks);
            TextView blockchainNetworkAddNetworkTextView = (TextView) getView().findViewById(R.id.textview_blockchain_network_langValues_add_network);
            ProgressBar progressBar = (ProgressBar) getView().findViewById(R.id.progress_blockchain_network);
            TableLayout tableLayout = (TableLayout) getView().findViewById(R.id.table_blockchain_network);

            blockchainNetworkTitleTextView.setText(jsonViewModel.getNetworksByLangValues());
            blockchainNetworkAddNetworkTextView.setText(jsonViewModel.getAddNetworkByLangValues());

            progressBar.setVisibility(View.VISIBLE);

            List<BlockchainNetwork> blockchainNetworkList = GlobalMethods.BlockChainNetworkRead(getContext());

            tableLayout.removeAllViews();

            int paddingPx = dpToPx(8);

            TableRow headerRow = new TableRow(getContext());
            String[] headers = {
                jsonViewModel.getIdByLangValues(),
                jsonViewModel.getNameByLangValues(),
                jsonViewModel.getScanApiUrlByLangValues(),
                jsonViewModel.getRpcEndpointByLangValues(),
                jsonViewModel.getBlockExplorerUrlByLangValues()
            };
            for (String header : headers) {
                TextView tv = new TextView(getContext());
                tv.setText(header);
                tv.setTypeface(null, Typeface.BOLD);
                tv.setPadding(paddingPx, paddingPx, paddingPx, paddingPx);
                tv.setSingleLine(true);
                headerRow.addView(tv);
            }
            tableLayout.addView(headerRow);

            for (BlockchainNetwork network : blockchainNetworkList) {
                TableRow row = new TableRow(getContext());
                String[] cells = {
                    network.getNetworkId(),
                    network.getBlockchainName(),
                    network.getScanApiDomain(),
                    network.getRpcEndpoint(),
                    network.getBlockExplorerDomain()
                };
                for (String cellText : cells) {
                    TextView tv = new TextView(getContext());
                    tv.setText(cellText != null ? cellText : "");
                    tv.setPadding(paddingPx, paddingPx, paddingPx, paddingPx);
                    tv.setSingleLine(true);
                    row.addView(tv);
                }
                tableLayout.addView(row);
            }

            progressBar.setVisibility(View.GONE);

            backArrowImageButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    mBlockchainNetworkListener.onBlockchainNetworkCompleteByBackArrow();
                }
            });

            blockchainNetworkAddNetworkTextView.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    mBlockchainNetworkListener.onBlockchainNetworkCompleteByAdd();
                }
            });

        } catch(Exception e){
            GlobalMethods.ExceptionError(getContext(), TAG, e);
        }
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp,
                getResources().getDisplayMetrics());
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onStop(){
        super.onStop();
    }

    public static interface OnBlockchainNetworkCompleteListener {
        public abstract void onBlockchainNetworkCompleteByBackArrow();
        public abstract void onBlockchainNetworkCompleteByAdd();
    }

    public void onAttach(Context context) {
        super.onAttach(context);
        try {
            this.mBlockchainNetworkListener = (BlockchainNetworkFragment.OnBlockchainNetworkCompleteListener)context;
        }
        catch (final ClassCastException e) {
            throw new ClassCastException(context.toString() + " ");
        }
    }

}