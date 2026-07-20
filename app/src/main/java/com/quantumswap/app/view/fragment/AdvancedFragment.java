package com.quantumswap.app.view.fragment;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.quantumswap.app.R;
import com.quantumswap.app.viewmodel.JsonViewModel;

/**
 * Advanced hub (entry in Settings), mirroring the desktop app's
 * Advanced section: Liquidity and Pools.
 */
public class AdvancedFragment extends Fragment {

    private OnAdvancedCompleteListener mListener;

    public static AdvancedFragment newInstance() {
        return new AdvancedFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.advanced_fragment, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        String languageKey = getArguments().getString("languageKey");
        JsonViewModel jsonViewModel = new JsonViewModel(getContext(), languageKey);

        ImageButton backArrow = view.findViewById(R.id.imageButton_advanced_back_arrow);
        TextView title = view.findViewById(R.id.textView_advanced_title);
        Button liquidityButton = view.findViewById(R.id.button_advanced_liquidity);
        Button poolsButton = view.findViewById(R.id.button_advanced_pools);

        title.setText(jsonViewModel.lang("advanced", "Advanced"));
        liquidityButton.setText(jsonViewModel.lang("adv-liquidity", "Liquidity"));
        poolsButton.setText(jsonViewModel.lang("adv-pools", "Pools"));

        backArrow.setOnClickListener(v -> mListener.onAdvancedCompleteByBackArrow());
        liquidityButton.setOnClickListener(v -> mListener.onAdvancedCompleteByLiquidity());
        poolsButton.setOnClickListener(v -> mListener.onAdvancedCompleteByPools());
    }

    public interface OnAdvancedCompleteListener {
        void onAdvancedCompleteByBackArrow();
        void onAdvancedCompleteByLiquidity();
        void onAdvancedCompleteByPools();
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        try {
            this.mListener = (OnAdvancedCompleteListener) context;
        } catch (final ClassCastException e) {
            throw new ClassCastException(context.toString() + " ");
        }
    }
}
