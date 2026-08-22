package com.quantumswap.app.view.dialog;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.quantumswap.app.R;
import com.quantumswap.app.gas.GasFee;
import com.quantumswap.app.utils.GlobalMethods;
import com.quantumswap.app.viewmodel.JsonViewModel;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Desktop showGasConfigDialog (#modalGasConfig): the gas limit is the
 * only editable field; the fee is recomputed live as
 * {@code limit x (openedFee / openedLimit)}. Ok validates limit > 0 and
 * fee >= 0 ("Invalid value"); Cancel changes nothing.
 */
public final class GasConfigDialog {

    private GasConfigDialog() { }

    public interface OnOk {
        void onOk(long gasLimit, String feeNumber);
    }

    public static void show(Context ctx, JsonViewModel vm, long gasLimit,
                            String feeNumber, final OnOk onOk) {
        View root = LayoutInflater.from(ctx).inflate(R.layout.gas_config_dialog, null);
        ((TextView) root.findViewById(R.id.textView_gas_config_title))
                .setText(vm.lang("gas", "Gas"));
        ((TextView) root.findViewById(R.id.textView_gas_config_limit_label))
                .setText(vm.lang("gas-limit", "Gas limit (gas-units)"));
        ((TextView) root.findViewById(R.id.textView_gas_config_fee_label))
                .setText(vm.lang("gas-fee", "Estimated gas fee (coins)"));
        final EditText limitField = root.findViewById(R.id.editText_gas_config_limit);
        final TextView feeText = root.findViewById(R.id.textView_gas_config_fee);
        Button cancel = root.findViewById(R.id.button_gas_config_cancel);
        Button ok = root.findViewById(R.id.button_gas_config_ok);
        cancel.setText(vm.getCancelByLangValues());
        ok.setText(vm.getOkByLangValues());

        limitField.setText(gasLimit > 0 ? String.valueOf(gasLimit) : "");
        feeText.setText(feeNumber == null ? "" : feeNumber);

        // Linear per-gas-unit rate captured at open (desktop gasConfigFeeRate).
        BigDecimal rate = null;
        try {
            if (gasLimit > 0 && feeNumber != null && !feeNumber.isEmpty()) {
                rate = new BigDecimal(feeNumber).divide(BigDecimal.valueOf(gasLimit),
                        20, RoundingMode.HALF_UP);
            }
        } catch (Exception ignore) { }
        final BigDecimal feeRate = rate;
        limitField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                if (feeRate == null) return;
                try {
                    long lim = Long.parseLong(s.toString().trim());
                    if (lim < 0) return;
                    feeText.setText(GasFee.formatNumber(feeRate.multiply(BigDecimal.valueOf(lim))));
                } catch (Exception ignore) { }
            }
        });

        final AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setView(root)
                .setCancelable(true)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        cancel.setOnClickListener(v -> dialog.dismiss());
        ok.setOnClickListener(v -> {
            long lim;
            BigDecimal fee;
            try {
                lim = Long.parseLong(limitField.getText().toString().trim());
                fee = new BigDecimal(feeText.getText().toString().trim());
            } catch (Exception e) {
                lim = -1;
                fee = BigDecimal.ONE.negate();
            }
            if (lim <= 0 || fee.signum() < 0) {
                GlobalMethods.ShowErrorDialog(ctx, vm.getErrorTitleByLangValues(),
                        vm.err("invalidValue", "Invalid value"));
                return;
            }
            dialog.dismiss();
            if (onOk != null) onOk.onOk(lim, GasFee.formatNumber(fee));
        });
        dialog.show();
        limitField.requestFocus();
    }
}
