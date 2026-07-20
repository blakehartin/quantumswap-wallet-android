package com.quantumswap.app.view.fragment;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.quantumswap.app.R;
import com.quantumswap.app.keystorage.SecureStorage;
import com.quantumswap.app.utils.GlobalMethods;
import com.quantumswap.app.utils.ReleaseStore;
import com.quantumswap.app.view.dialog.DexUnlockPrompt;
import com.quantumswap.app.viewmodel.JsonViewModel;
import com.quantumswap.app.viewmodel.KeyViewModel;

import java.util.List;

/**
 * Releases screen (under Settings): view the built-in Beta2 contract
 * set, select the active release, and add user-defined releases.
 */
public class ReleasesFragment extends Fragment {

    private OnReleasesCompleteListener mListener;

    private JsonViewModel jsonViewModel;
    private RadioGroup listGroup;
    private EditText nameEditText;
    private EditText wqEditText;
    private EditText factoryEditText;
    private EditText routerEditText;
    private TextView statusTextView;
    private Button addButton;

    public static ReleasesFragment newInstance() {
        return new ReleasesFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.releases_fragment, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        String languageKey = getArguments().getString("languageKey");
        jsonViewModel = new JsonViewModel(getContext(), languageKey);

        ImageButton backArrow = view.findViewById(R.id.imageButton_releases_back_arrow);
        TextView title = view.findViewById(R.id.textView_releases_title);
        TextView addTitle = view.findViewById(R.id.textView_releases_add_title);
        listGroup = view.findViewById(R.id.layout_releases_list);
        nameEditText = view.findViewById(R.id.editText_releases_name);
        wqEditText = view.findViewById(R.id.editText_releases_wq);
        factoryEditText = view.findViewById(R.id.editText_releases_factory);
        routerEditText = view.findViewById(R.id.editText_releases_router);
        statusTextView = view.findViewById(R.id.textView_releases_status);
        addButton = view.findViewById(R.id.button_releases_add);

        title.setText(jsonViewModel.lang("releases", "Releases"));
        addTitle.setText(jsonViewModel.lang("add-release", "Add release"));
        nameEditText.setHint(jsonViewModel.lang("release-name", "Name"));
        wqEditText.setHint(jsonViewModel.lang("release-wq", "WQ address"));
        factoryEditText.setHint(jsonViewModel.lang("release-factory", "Factory address"));
        routerEditText.setHint(jsonViewModel.lang("release-router", "Router address"));
        addButton.setText(jsonViewModel.lang("add-release", "Add release"));

        backArrow.setOnClickListener(v -> mListener.onReleasesCompleteByBackArrow());
        addButton.setOnClickListener(v -> startAdd());

        renderList();
    }

    private void renderList() {
        listGroup.removeAllViews();
        SecureStorage secureStorage = KeyViewModel.getSecureStorage();
        List<ReleaseStore.Release> releases = ReleaseStore.readAll(secureStorage);
        ReleaseStore.Release active = ReleaseStore.readActive(secureStorage);

        for (final ReleaseStore.Release release : releases) {
            RadioButton rb = new RadioButton(getContext());
            rb.setId(View.generateViewId());
            String label = release.name + (release.builtin
                    ? " (" + jsonViewModel.lang("builtin", "built-in") + ")"
                    : "");
            rb.setText(label);
            rb.setTextColor(getResources().getColor(R.color.colorCommon6));
            if (release.name.equals(active.name)) {
                rb.setChecked(true);
            }
            rb.setOnClickListener(v -> selectRelease(release));
            listGroup.addView(rb);

            TextView detail = new TextView(getContext());
            detail.setText("WQ " + shortAddr(release.wq)
                    + "\nFactory " + shortAddr(release.factory)
                    + "\nRouter " + shortAddr(release.router));
            detail.setTextSize(11);
            detail.setTextColor(getResources().getColor(R.color.colorCommon3));
            detail.setPadding(dp(32), 0, 0, dp(10));
            listGroup.addView(detail);
        }
    }

    private void selectRelease(final ReleaseStore.Release release) {
        SecureStorage secureStorage = KeyViewModel.getSecureStorage();
        ReleaseStore.Release active = ReleaseStore.readActive(secureStorage);
        if (release.name.equals(active.name)) return;

        DexUnlockPrompt.show(getActivity(), jsonViewModel, password -> {
            try {
                ReleaseStore.persistActiveRelease(
                        getActivity().getApplicationContext(),
                        secureStorage, release.name, password);
                setStatus(jsonViewModel.lang("release-active",
                        "Active release updated.") + " " + release.name);
                renderList();
            } catch (Exception e) {
                fail(e.getMessage());
            }
        });
    }

    private void startAdd() {
        final String name = text(nameEditText);
        final String wq = text(wqEditText);
        final String factory = text(factoryEditText);
        final String router = text(routerEditText);

        if (!ReleaseStore.isValidName(name)
                || !ReleaseStore.isValidAddress(wq)
                || !ReleaseStore.isValidAddress(factory)
                || !ReleaseStore.isValidAddress(router)) {
            GlobalMethods.ShowErrorDialog(getContext(),
                    jsonViewModel.getErrorTitleByLangValues(),
                    jsonViewModel.lang("invalid-release",
                            "Enter a valid name and three 0x… 64-hex addresses."));
            return;
        }

        final ReleaseStore.Release release =
                new ReleaseStore.Release(name, wq, factory, router, false);
        DexUnlockPrompt.show(getActivity(), jsonViewModel, password -> {
            try {
                SecureStorage secureStorage = KeyViewModel.getSecureStorage();
                ReleaseStore.persistAddRelease(
                        getActivity().getApplicationContext(),
                        secureStorage, release, password);
                nameEditText.setText("");
                wqEditText.setText("");
                factoryEditText.setText("");
                routerEditText.setText("");
                setStatus(jsonViewModel.lang("release-added", "Release added.") + " " + name);
                renderList();
            } catch (Exception e) {
                fail(e.getMessage());
            }
        });
    }

    private void setStatus(String message) {
        statusTextView.setText(message);
        statusTextView.setVisibility(View.VISIBLE);
    }

    private void fail(String error) {
        if (error != null && !error.isEmpty() && getContext() != null) {
            GlobalMethods.ShowErrorDialog(getContext(),
                    jsonViewModel.getErrorTitleByLangValues(),
                    jsonViewModel.getErrorOccurredByLangValues() + sanitizeError(error));
        }
        renderList();
    }

    private static String text(EditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }

    private static String shortAddr(String addr) {
        if (addr == null) return "";
        return addr.length() > 14
                ? addr.substring(0, 8) + "..." + addr.substring(addr.length() - 4) : addr;
    }

    private static String sanitizeError(String s) {
        if (s == null) return "";
        String cleaned = s.replaceAll("[\\p{Cntrl}]", " ");
        return cleaned.length() > 300 ? cleaned.substring(0, 300) : cleaned;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    public interface OnReleasesCompleteListener {
        void onReleasesCompleteByBackArrow();
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        try {
            this.mListener = (OnReleasesCompleteListener) context;
        } catch (final ClassCastException e) {
            throw new ClassCastException(context.toString() + " ");
        }
    }
}
