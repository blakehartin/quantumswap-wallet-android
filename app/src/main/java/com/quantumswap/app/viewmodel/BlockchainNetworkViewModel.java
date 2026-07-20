package com.quantumswap.app.viewmodel;

import android.content.Context;

import androidx.lifecycle.ViewModel;

import com.quantumswap.app.interact.BlockchainNetworkInteract;
import com.quantumswap.app.utils.GlobalMethods;

import org.json.JSONException;

import timber.log.Timber;

//@HiltViewModel
public class BlockchainNetworkViewModel extends ViewModel{
  //@Inject
    private BlockchainNetworkInteract _blockchainNetworkInteract;

    //@Inject
    public BlockchainNetworkViewModel(Context context) {

    }

    public String getNetWorks() {
        try {
            return _blockchainNetworkInteract.getNetWorks();
        } catch (JSONException e) {
            Timber.w(e, "blockchain network lookup failed");
        }
        return null;
    }
    public String getScanApiDomain() {
        try {
            return _blockchainNetworkInteract.getScanApiDomain();
        } catch (JSONException e) {
            Timber.w(e, "blockchain network lookup failed");
        }
        return null;
    }
    public String getRpcEndpoint() {
        try {
            return _blockchainNetworkInteract.getRpcEndpoint();
        } catch (JSONException e) {
            Timber.w(e, "blockchain network lookup failed");
        }
        return null;
    }
    public String getBlockExplorerDomain() {
        try {
            return _blockchainNetworkInteract.getBlockExplorerDomain();
        } catch (JSONException e) {
            Timber.w(e, "blockchain network lookup failed");
        }
        return null;
    }
    public String getBlockchainName() {
        try {
            return _blockchainNetworkInteract.getBlockchainName();
        } catch (JSONException e) {
            Timber.w(e, "blockchain network lookup failed");
        }
        return null;
    }
    public String getNetworkId() {
        try {
            return _blockchainNetworkInteract.getNetworkId();
        } catch (JSONException e) {
            Timber.w(e, "blockchain network lookup failed");
        }
        return null;
    }
}
