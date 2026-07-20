package com.quantumswap.app.interact;

import org.json.JSONException;
import org.json.JSONObject;

public class BlockchainNetworkInteract {

    private JSONObject jsonObject;

    private static final String data_networks = "networks";
    private static final String data_scanApiDomain = "scanApiDomain";
    private static final String data_rpcEndpoint = "rpcEndpoint";
    private static final String data_blockExplorerDomain = "blockExplorerDomain";
    private static final String data_blockchainName = "blockchainName";
    private static final String data_networkId = "networkId";

    public BlockchainNetworkInteract() throws JSONException {

    }
    public String getNetWorks() throws JSONException{
        return data_networks;
    }
    public String getScanApiDomain() throws JSONException{
        return data_scanApiDomain;
    }
    public String getRpcEndpoint() throws JSONException{
        return data_rpcEndpoint;
    }
    public String getBlockExplorerDomain() throws JSONException{
        return data_blockExplorerDomain;
    }
    public String getBlockchainName() throws JSONException{
        return data_blockchainName;
    }
    public String getNetworkId() throws JSONException{
        return data_networkId;
    }
}
