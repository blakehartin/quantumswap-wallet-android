package com.quantumswap.app.view.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.fragment.app.DialogFragment;

import com.quantumswap.app.R;
import com.quantumswap.app.utils.GlobalMethods;
import com.quantumswap.app.viewmodel.JsonViewModel;

public class MessageInformationDialogFragment extends DialogFragment  {

    private static final String TAG = "MessageDialogFragment";


    public static MessageInformationDialogFragment newInstance() {
        MessageInformationDialogFragment fragment = new MessageInformationDialogFragment();
        return fragment;
    }

    /**
     * Convenience factory that flips the dialog into
     * "error" mode (orange warning triangle) so callers can clearly
     * distinguish recoverable info from a true failure, mirroring the
     * iOS UIAlertController.error(...) surface. The caller still
     * passes the message via the existing arguments bundle.
     */
    public static MessageInformationDialogFragment error() {
        MessageInformationDialogFragment fragment = new MessageInformationDialogFragment();
        Bundle args = new Bundle();
        args.putBoolean("isError", true);
        fragment.setArguments(args);
        return fragment;
    }

    public MessageInformationDialogFragment() {

    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.message_information_dialog_fragment, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        try {
            JsonViewModel jsonViewModel = new JsonViewModel(getContext(), getArguments().getString("languageKey"));

            TextView messageTextView = (TextView) getView().findViewById(R.id.textView_message_information_dialog_description);
            TextView closeTextView = (TextView) getView().findViewById(R.id.textView_message_information_dialog_close);
            ImageView iconView = (ImageView) getView().findViewById(R.id.imageView_message_information_dialog_icon);

            String message = getArguments().getString("message");
            messageTextView.setText(message);

            // Swap to the orange error triangle when the
            // caller invoked MessageInformationDialogFragment.error().
            // Falls back to the default information glyph otherwise so
            // existing callers (and the OK/Close text below) keep their
            // current visual contract.
            boolean isError = getArguments().getBoolean("isError", false);
            if (iconView != null) {
                iconView.setImageResource(isError ? R.drawable.img_error : R.drawable.img_information);
            }

            closeTextView.setText(jsonViewModel.getCloseByLangValues());

            closeTextView.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    getDialog().dismiss();
                }
            });

            getDialog().getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);

        } catch (Exception e) {
            GlobalMethods.ExceptionError(getContext(), TAG, e);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onStop(){
        super.onStop();
    }

}
