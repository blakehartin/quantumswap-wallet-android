package com.quantumswap.app.view.fragment;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.quantumswap.app.R;
import com.quantumswap.app.utils.PrefConnect;
import com.quantumswap.app.viewmodel.JsonViewModel;

public class SettingsFragment extends Fragment  {

    private static final String TAG = "SettingsFragment";

    private LinearLayout linerLayoutOffline;
    private ImageView imageViewRetry;
    private TextView textViewTitleRetry;
    private TextView textViewSubTitleRetry;


    private OnSettingsCompleteListener mSettingsListener;

    private JsonViewModel cachedJsonViewModel;

    public static SettingsFragment newInstance() {
        SettingsFragment fragment = new SettingsFragment();
        return fragment;
    }

    public SettingsFragment() {

    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.settings_fragment, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        String languageKey = getArguments().getString("languageKey");

        JsonViewModel jsonViewModel = new JsonViewModel(getContext(), languageKey);

        ImageButton backArrowImageButton = (ImageButton) getView().findViewById(R.id.imageButton_setting_back_arrow);
        TextView settings = (TextView) getView().findViewById(R.id.textview_settings_langValues_settings);
        Button buttonNetworks = (Button) getView().findViewById(R.id.button_settings_langValues_networks);
        Button buttonReleases = (Button) getView().findViewById(R.id.button_settings_langValues_releases);
        Button buttonAdvanced = (Button) getView().findViewById(R.id.button_settings_langValues_advanced);
        Button buttonSigning = (Button) getView().findViewById(R.id.button_settings_langValues_signing);
        Button buttonBackup = (Button) getView().findViewById(R.id.button_settings_langValues_backup);

        settings.setText(jsonViewModel.getSettingsByLangValues());
        buttonNetworks.setText(jsonViewModel.getNetworksByLangValues());
        buttonReleases.setText(jsonViewModel.lang("releases", "Releases"));
        buttonAdvanced.setText(jsonViewModel.lang("advanced", "Advanced"));
        buttonSigning.setText(jsonViewModel.getSigningByLangValues());
        buttonBackup.setText(jsonViewModel.getBackupByLangValues());

        linerLayoutOffline = (LinearLayout) getView().findViewById(R.id.linerLayout_setting_offline);
        imageViewRetry = (ImageView) getView().findViewById(R.id.image_retry);
        textViewTitleRetry = (TextView) getView().findViewById(R.id.textview_title_retry);
        textViewSubTitleRetry = (TextView) getView().findViewById(R.id.textview_subtitle_retry);
        Button buttonRetry = (Button) getView().findViewById(R.id.button_retry);

        backArrowImageButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mSettingsListener.onSettingsCompleteCompleteByBackArrow();
            }
        });

        buttonNetworks.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v) {
                mSettingsListener.onSettingsCompleteByNetwork();
            }
        });

        buttonReleases.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v) {
                mSettingsListener.onSettingsCompleteByReleases();
            }
        });

        buttonAdvanced.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v) {
                mSettingsListener.onSettingsCompleteByAdvanced();
            }
        });

        buttonSigning.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v) {
                showAdvancedSigningDialog(jsonViewModel);
            }
        });

        buttonBackup.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v) {
                showBackupDialog(jsonViewModel);
            }
        });

        buttonRetry.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v) {
                mSettingsListener.onSettingsCompleteCompleteByBackArrow();
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onStop(){
        super.onStop();
    }

    private void showAdvancedSigningDialog(JsonViewModel jsonViewModel) {
        boolean currentValue = PrefConnect.readBoolean(getContext(), PrefConnect.ADVANCED_SIGNING_ENABLED_KEY, false);

        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 0);

        TextView description = new TextView(getContext());
        description.setText(jsonViewModel.getAdvancedSigningDescriptionByLangValues());
        description.setPadding(0, 0, 0, 24);
        layout.addView(description);

        RadioGroup radioGroup = new RadioGroup(getContext());
        radioGroup.setOrientation(RadioGroup.VERTICAL);

        RadioButton radioEnabled = new RadioButton(getContext());
        radioEnabled.setId(View.generateViewId());
        radioEnabled.setText(jsonViewModel.getEnabledByLangValues());
        radioGroup.addView(radioEnabled);

        RadioButton radioDisabled = new RadioButton(getContext());
        radioDisabled.setId(View.generateViewId());
        radioDisabled.setText(jsonViewModel.getDisabledByLangValues());
        radioGroup.addView(radioDisabled);

        if (currentValue) {
            radioEnabled.setChecked(true);
        } else {
            radioDisabled.setChecked(true);
        }

        layout.addView(radioGroup);

        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(jsonViewModel.getSigningByLangValues());
        builder.setView(layout);
        builder.setPositiveButton(jsonViewModel.getOkByLangValues(), (dialog, which) -> {
            boolean enabled = radioEnabled.isChecked();
            PrefConnect.writeBoolean(getContext(), PrefConnect.ADVANCED_SIGNING_ENABLED_KEY, enabled);
            dialog.dismiss();
        });
        builder.setNegativeButton(jsonViewModel.getCancelByLangValues(), (dialog, which) -> {
            dialog.dismiss();
        });
        builder.show();
    }

    private void showBackupDialog(final JsonViewModel jsonViewModel) {
        this.cachedJsonViewModel = jsonViewModel;
        final int pad = dp(16);

        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, 0);

        TextView phoneHeader = new TextView(getContext());
        phoneHeader.setText(safe(jsonViewModel.getPhoneBackupByLangValues(), "Phone Backup"));
        phoneHeader.setTypeface(null, android.graphics.Typeface.BOLD);
        phoneHeader.setPadding(0, 0, 0, dp(8));
        root.addView(phoneHeader);

        TextView phoneDesc = new TextView(getContext());
        phoneDesc.setText(safe(jsonViewModel.getBackupDescriptionByLangValues(), ""));
        phoneDesc.setPadding(0, 0, 0, dp(8));
        root.addView(phoneDesc);

        final RadioGroup phoneGroup = new RadioGroup(getContext());
        phoneGroup.setOrientation(RadioGroup.VERTICAL);
        final RadioButton phoneEnabled = new RadioButton(getContext());
        phoneEnabled.setId(View.generateViewId());
        phoneEnabled.setText(safe(jsonViewModel.getEnabledByLangValues(), "Enabled"));
        phoneGroup.addView(phoneEnabled);
        final RadioButton phoneDisabled = new RadioButton(getContext());
        phoneDisabled.setId(View.generateViewId());
        phoneDisabled.setText(safe(jsonViewModel.getDisabledByLangValues(), "Disabled"));
        phoneGroup.addView(phoneDisabled);
        boolean phoneCurrent = PrefConnect.readBoolean(getContext(), PrefConnect.BACKUP_ENABLED_KEY, false);
        if (phoneCurrent) phoneEnabled.setChecked(true); else phoneDisabled.setChecked(true);
        root.addView(phoneGroup);

        final ScrollViewWrapper scroll = ScrollViewWrapper.wrap(getContext(), root);

        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(jsonViewModel.getBackupByLangValues());
        builder.setView(scroll.view);
        builder.setPositiveButton(jsonViewModel.getOkByLangValues(), (dialog, which) -> {
            // (Android, mirrors iOS BackupExclusion.applyToStrongboxFiles):
            // (a) write the new pref value synchronously so the
            //     WalletBackupAgent's gate sees the updated state on
            //     the very next backup attempt;
            // (b) tell Android the app's backup data has changed so
            //     it re-evaluates the gate immediately rather than
            //     waiting for the system's lazy schedule.
            // The actual file-level exclusion is wired through
            // data_extraction_rules.xml + the agent gate; the user-
            // facing promise is "immediate" because the next backup
            // attempt (system-scheduled or manual) consults the gate.
            PrefConnect.writeBoolean(getContext(), PrefConnect.BACKUP_ENABLED_KEY, phoneEnabled.isChecked());
            try {
                new android.app.backup.BackupManager(getContext().getApplicationContext()).dataChanged();
                com.quantumswap.app.Logger.i("SettingsFragment",
                        "BACKUP_ENABLED toggled -> " + phoneEnabled.isChecked()
                                + "; BackupManager.dataChanged() called");
            } catch (Throwable t) {
                com.quantumswap.app.Logger.w("SettingsFragment",
                        "BackupManager.dataChanged() failed (toggle still recorded)", t);
            }
            dialog.dismiss();
        });
        builder.setNegativeButton(jsonViewModel.getCancelByLangValues(), (dialog, which) -> dialog.dismiss());
        final AlertDialog dialog = builder.create();

        dialog.show();
    }

    private static class ScrollViewWrapper {
        final View view;
        ScrollViewWrapper(View view) { this.view = view; }
        static ScrollViewWrapper wrap(Context ctx, View content) {
            android.widget.ScrollView sv = new android.widget.ScrollView(ctx);
            sv.addView(content);
            return new ScrollViewWrapper(sv);
        }
    }

    private void markActivityUnlocked() {
        try {
            android.app.Activity act = getActivity();
            if (act instanceof com.quantumswap.app.view.activities.HomeActivity) {
                ((com.quantumswap.app.view.activities.HomeActivity) act).markUnlockedNow();
            }
        } catch (Exception ignore) { }
    }

    private void setSuppressNextResumeLock(boolean suppress) {
        try {
            android.app.Activity act = getActivity();
            if (act instanceof com.quantumswap.app.view.activities.HomeActivity) {
                ((com.quantumswap.app.view.activities.HomeActivity) act).setSuppressNextResumeLock(suppress);
            }
        } catch (Exception ignore) { }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private static String safe(String v, String fallback) {
        return (v == null || v.isEmpty()) ? fallback : v;
    }

    public static interface OnSettingsCompleteListener {
        public abstract void onSettingsCompleteCompleteByBackArrow();
        public abstract void onSettingsCompleteByNetwork();
        public abstract void onSettingsCompleteByReleases();
        public abstract void onSettingsCompleteByAdvanced();
    }

    public void onAttach(Context context) {
        super.onAttach(context);
        try {
            this.mSettingsListener = (OnSettingsCompleteListener)context;
        }
        catch (final ClassCastException e) {
            throw new ClassCastException(context.toString() + " ");
        }
    }
}
