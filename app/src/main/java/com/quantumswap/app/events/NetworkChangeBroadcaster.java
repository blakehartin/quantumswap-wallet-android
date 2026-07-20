package com.quantumswap.app.events;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

/**
 * In-process broadcaster for network-related state changes
 * (active network selection, network list edits).
 *
 * <h3>Why LocalBroadcastManager and not the global broadcast bus?</h3>
 * These events MUST stay inside the wallet process. Network identity is
 * a privacy and security signal: another app on the device should not
 * be able to learn which RPC endpoint, scan API, or chain ID the wallet
 * is currently talking to, nor when the user changes it. A global
 * sendBroadcast would leak that to any registered receiver and would
 * also let a malicious app forge a network-changed event to nudge the
 * wallet into refreshing against a stale state. LocalBroadcastManager
 * is process-local: it never crosses the IPC boundary, so the events
 * are unforgeable from outside the app and invisible to other apps.
 *
 * <h3>Why broadcast at all instead of an activity restart?</h3>
 * Earlier behavior was to recreate the host activity after a network
 * switch. That worked but flashed the user's UI, destroyed transient
 * dialog state (e.g. a half-typed send), and made the active-network
 * label momentarily render the wrong color. iOS uses NotificationCenter
 * to push a single change event; subscribers refresh in place. We
 * mirror that with LocalBroadcastManager so the Home, Send, and
 * AccountTransactions screens can re-fetch balances and tokens without
 * tearing down their views.
 *
 * <h3>Threading</h3>
 * LocalBroadcastManager dispatches on the registering thread (which in
 * our case is always the main thread, because every consumer registers
 * from a Fragment/Activity lifecycle method). Receivers therefore run
 * on the main thread and may touch UI without additional posting.
 */
public final class NetworkChangeBroadcaster {

    /**
     * Sent when the user switches the active network (top-right menu)
     * or when the active network entry was edited in place. Receivers
     * should refresh balances, tokens, transactions, and any UI that
     * mirrors the active chain identity.
     */
    public static final String ACTION_ACTIVE_NETWORK_CHANGED =
            "com.quantumswap.app.NETWORK_ACTIVE_CHANGED";

    /**
     * Sent when the user added, edited, or removed a network from the
     * blockchain-networks list. Receivers should re-read the network
     * list (e.g. for menu dropdowns) and may also need to refresh if
     * the active network's metadata changed.
     */
    public static final String ACTION_NETWORK_LIST_CHANGED =
            "com.quantumswap.app.NETWORK_LIST_CHANGED";

    private NetworkChangeBroadcaster() { }

    public static void broadcastActiveNetworkChanged(Context ctx) {
        LocalBroadcastManager.getInstance(ctx.getApplicationContext())
                .sendBroadcast(new Intent(ACTION_ACTIVE_NETWORK_CHANGED));
    }

    public static void broadcastNetworkListChanged(Context ctx) {
        LocalBroadcastManager.getInstance(ctx.getApplicationContext())
                .sendBroadcast(new Intent(ACTION_NETWORK_LIST_CHANGED));
    }

    /**
     * Convenience subscriber: registers the given receiver for both
     * ACTIVE and LIST changes. Returns the same receiver so the caller
     * can store it for later unregister.
     */
    public static BroadcastReceiver registerAll(Context ctx, BroadcastReceiver receiver) {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_ACTIVE_NETWORK_CHANGED);
        filter.addAction(ACTION_NETWORK_LIST_CHANGED);
        LocalBroadcastManager.getInstance(ctx.getApplicationContext())
                .registerReceiver(receiver, filter);
        return receiver;
    }

    public static void unregister(Context ctx, BroadcastReceiver receiver) {
        if (receiver == null) return;
        try {
            LocalBroadcastManager.getInstance(ctx.getApplicationContext())
                    .unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignore) {
            // Receiver was not registered; safe to ignore.
        }
    }
}
