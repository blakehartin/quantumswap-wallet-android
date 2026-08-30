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
import com.quantumswap.app.utils.SwapApiConfig;
import com.quantumswap.app.utils.DexPayloads;
import com.quantumswap.app.bridge.BridgeCallback;

import org.json.JSONObject;
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
    private EditText apiUrlEditText;
    private EditText dexIdEditText;
    private TextView apiStatusTextView;
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
        apiUrlEditText = view.findViewById(R.id.editText_releases_api_url);
        dexIdEditText = view.findViewById(R.id.editText_releases_dex_id);
        apiStatusTextView = view.findViewById(R.id.textView_releases_api_status);
        statusTextView = view.findViewById(R.id.textView_releases_status);
        addButton = view.findViewById(R.id.button_releases_add);

        title.setText(jsonViewModel.lang("releases", "Releases"));
        addTitle.setText(jsonViewModel.lang("add-release", "Add release"));
        nameEditText.setHint(jsonViewModel.lang("release-name", "Name"));
        wqEditText.setHint(jsonViewModel.lang("release-wq", "WQ address"));
        factoryEditText.setHint(jsonViewModel.lang("release-factory", "Factory address"));
        routerEditText.setHint(jsonViewModel.lang("release-router", "Router address"));
        // Web app add-release form: the two Swap Read API fields are
        // optional and prefilled with the public defaults; clearing one
        // switches the API off for that release (RPC only).
        apiUrlEditText.setHint(jsonViewModel.lang("release-api-url", "Swap Read API URL"));
        dexIdEditText.setHint(jsonViewModel.lang("release-dex-id", "Swap Read API dexId"));
        apiUrlEditText.setText(SwapApiConfig.DEFAULT_API_URL);
        dexIdEditText.setText(SwapApiConfig.DEFAULT_DEX_ID);
        addButton.setText(jsonViewModel.lang("add-release", "Add release"));

        backArrow.setOnClickListener(v -> mListener.onReleasesCompleteByBackArrow());
        addButton.setOnClickListener(v -> startAdd());

        renderList();
        loadApiStatus();
    }

    /** Web app releases.ts status line: what the Swap Read API serves for
     *  the active release, or why the screens are on RPC. */
    private void loadApiStatus() {
        try {
            JSONObject payload = DexPayloads.base();
            KeyViewModel.getBridge().dexCallAsync("swapApiStatus", payload, new BridgeCallback() {
                @Override
                public void onResult(final String jsonResult) {
                    if (getActivity() == null) return;
                    getActivity().runOnUiThread(() -> {
                        try {
                            JSONObject root = new JSONObject(jsonResult);
                            JSONObject data = root.optJSONObject("data");
                            if (data == null) data = root;
                            showApiStatus(data);
                        } catch (Exception e) {
                            showApiStatus(null);
                        }
                    });
                }

                @Override
                public void onError(final String error) {
                    if (getActivity() == null) return;
                    getActivity().runOnUiThread(() -> showApiStatus(null));
                }
            });
        } catch (Exception e) {
            showApiStatus(null);
        }
    }

    private void showApiStatus(JSONObject data) {
        if (getView() == null) return;
        String status = data == null ? "unavailable" : data.optString("status", "unavailable");
        String text;
        if ("ok".equals(status)) {
            text = jsonViewModel.lang("swap-api-status-indexed",
                    "Swap Read API: indexed [PAIRS] pools · [TOKENS] tokens · block [BLOCK]")
                    .replace("[PAIRS]", String.valueOf(data.optLong("pairs", 0)))
                    .replace("[TOKENS]", String.valueOf(data.optLong("tokens", 0)))
                    .replace("[BLOCK]", String.valueOf(data.optLong("indexedBlock", 0)));
            long lag = data.optLong("lagBlocks", 0);
            if (lag > 0) {
                text += " " + jsonViewModel.lang("swap-api-status-behind", "([LAG] behind)")
                        .replace("[LAG]", String.valueOf(lag));
            }
        } else if ("disabled".equals(status)) {
            text = jsonViewModel.lang("swap-api-status-off",
                    "Swap Read API: off for this release (using RPC).");
        } else if ("no-dex".equals(status)) {
            text = jsonViewModel.lang("swap-api-status-not-served",
                    "Swap Read API: this dexId is not served for this factory (using RPC).");
        } else {
            text = jsonViewModel.lang("swap-api-status-unavailable",
                    "Swap Read API unavailable (using RPC).");
        }
        apiStatusTextView.setText(text);
        apiStatusTextView.setVisibility(View.VISIBLE);
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
            // Full addresses (not the short 0x1234...abcd form): the
            // release contracts are exactly what the user must be able
            // to verify byte-for-byte against a published release.
            String off = jsonViewModel.lang("release-api-off", "Off (using RPC)");
            detail.setText("WQ " + safeAddr(release.wq)
                    + "\nFactory " + safeAddr(release.factory)
                    + "\nRouter " + safeAddr(release.router)
                    + "\n" + jsonViewModel.lang("release-api-url", "Swap Read API URL") + " "
                    + (release.apiUrl.isEmpty() ? off : release.apiUrl)
                    + "\n" + jsonViewModel.lang("release-dex-id", "Swap Read API dexId") + " "
                    + (release.dexId.isEmpty() ? off : release.dexId));
            detail.setTextIsSelectable(true);
            detail.setTypeface(android.graphics.Typeface.MONOSPACE);
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
                loadApiStatus();
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
        final String apiUrlRaw = text(apiUrlEditText);
        final String dexIdRaw = text(dexIdEditText);

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

        // Optional API fields: "" switches the API off; anything else must
        // sanitise cleanly.
        final String apiUrl = apiUrlRaw.isEmpty() ? "" : SwapApiConfig.sanitizeUrl(apiUrlRaw);
        if (!apiUrlRaw.isEmpty() && apiUrl.isEmpty()) {
            GlobalMethods.ShowErrorDialog(getContext(),
                    jsonViewModel.getErrorTitleByLangValues(),
                    jsonViewModel.lang("release-invalid-api-url",
                            "Enter a valid http(s) URL for the Swap Read API (no credentials, query or fragment; max 200 characters)."));
            return;
        }
        if (!dexIdRaw.isEmpty() && !SwapApiConfig.isValidDexId(dexIdRaw)) {
            GlobalMethods.ShowErrorDialog(getContext(),
                    jsonViewModel.getErrorTitleByLangValues(),
                    jsonViewModel.lang("release-invalid-dex-id",
                            "Swap Read API dexId may only contain letters, digits, - and _ (max 64)."));
            return;
        }
        final ReleaseStore.Release release =
                new ReleaseStore.Release(name, wq, factory, router, false, apiUrl, dexIdRaw);
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
                apiUrlEditText.setText(SwapApiConfig.DEFAULT_API_URL);
                dexIdEditText.setText(SwapApiConfig.DEFAULT_DEX_ID);
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

    private static String safeAddr(String addr) {
        return addr == null ? "" : addr;
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
