package com.quantumswap.app.bridge;

public interface BridgeCallback {
    void onResult(String jsonResult);
    void onError(String error);
}
