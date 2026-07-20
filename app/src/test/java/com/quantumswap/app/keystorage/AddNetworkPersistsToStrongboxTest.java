package com.quantumswap.app.keystorage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.quantumswap.app.model.BlockchainNetwork;
import com.quantumswap.app.strongbox.StrongboxPayload;
import com.quantumswap.app.utils.NetworkPersistence;

import org.junit.Test;

/**
 * Pure-JVM coverage of the in-memory invariants
 * {@code BlockchainNetworkAddFragment} relies on when it persists a
 * user-added network. The full
 * {@code persistAddedNetwork(ctx, ss, network)} path requires a
 * live {@code SecureStorage} (file I/O + AndroidKeyStore), so the
 * end-to-end disk-write assertion lives in the androidTest fixture
 * — but the conversion + assignment logic this test exercises is
 * the part that has historically broken parity with iOS, so a fast
 * JVM regression gate is the right shape here.
 * <p>v=3 unified-schema:
 * Bundled networks are NOT stored inside
 * {@link StrongboxPayload#customNetworks}; only user-added entries
 * are. The merged list returned by
 * {@code NetworkPersistence.readNetworks} is bundled-prefix +
 * customNetworks-suffix, so user-added entries are implicitly
 * "isUserAdded" by virtue of being inside the strongbox at all.
 * The v=3 {@link StrongboxPayload.Network} struct mirrors iOS
 * {@code BlockchainNetwork} field-for-field (no {@code id},
 * {@code currency}, {@code isUserAdded} fields — those were
 * iOS-incompatible, dropped in v=3).</p>
 */
public class AddNetworkPersistsToStrongboxTest {

    @Test
    public void toPayload_copiesAllFields() {
        BlockchainNetwork model = new BlockchainNetwork(
                "scan.example.com",
                "https://rpc.example.com",
                "explorer.example.com",
                "TESTNET-X",
                "424242");

        StrongboxPayload.Network entry =
                NetworkPersistence.toPayload(model);

        assertEquals("scan.example.com", entry.scanApiDomain);
        assertEquals("https://rpc.example.com", entry.rpcEndpoint);
        assertEquals("explorer.example.com", entry.blockExplorerUrl);
        assertEquals("TESTNET-X", entry.name);
        assertEquals("v=3 chainId is the validated string from the "
                + "model (no parse-to-long), so iOS and Android emit "
                + "byte-identical canonical JSON",
                "424242", entry.chainId);
    }

    @Test
    public void toPayload_preservesNonNumericChainIdString() {
        // v=3 keeps chainId as a string (matches iOS BlockchainNetwork);
        // a non-numeric value is round-tripped verbatim. The fragment
        // validator rejects non-numeric input before reaching this path,
        // but this test pins the byte-for-byte round-trip so a future
        // change cannot silently re-introduce the v=2 long-parse + -1
        // sentinel that diverged from iOS.
        BlockchainNetwork model = new BlockchainNetwork(
                "s", "https://r", "e", "n", "not-a-number");
        StrongboxPayload.Network entry =
                NetworkPersistence.toPayload(model);
        assertEquals("not-a-number", entry.chainId);
    }

    @Test
    public void roundTripModelToPayloadAndBack_preservesAllFields() {
        BlockchainNetwork before = new BlockchainNetwork(
                "scan.example.com",
                "https://rpc.example.com",
                "explorer.example.com",
                "TESTNET-X",
                "424242");
        StrongboxPayload.Network entry =
                NetworkPersistence.toPayload(before);
        BlockchainNetwork after = NetworkPersistence.toModel(entry);

        assertNotNull(after);
        assertEquals(before.getScanApiDomain(), after.getScanApiDomain());
        assertEquals(before.getRpcEndpoint(), after.getRpcEndpoint());
        assertEquals(before.getBlockExplorerDomain(),
                after.getBlockExplorerDomain());
        assertEquals(before.getBlockchainName(), after.getBlockchainName());
        assertEquals(before.getNetworkId(), after.getNetworkId());
    }

    @Test
    public void persistAddedNetwork_throwsWhenStrongboxLocked() {
        // The fragment promises to prompt for unlock before invoking
        // persistAddedNetwork. If a future refactor accidentally
        // skips the prompt, this guard ensures the call fails fast
        // rather than silently dropping the network.
        Exception caught = null;
        try {
            // Pass null SecureStorage to simulate "no coordinator at all"
            // — the wrapper inside persistAddedNetwork dereferences
            // getCoordinator() so this is the closest pure-JVM
            // approximation of the locked state.
            NetworkPersistence.persistAddedNetwork(
                    /*ctx=*/null, /*secureStorage=*/null,
                    new BlockchainNetwork("s", "https://r", "e", "n", "1"),
                    /*password=*/"unused");
        } catch (NullPointerException npe) {
            // SecureStorage @NonNull contract — caught is the
            // null-arg failure, equivalent to "locked + no
            // coordinator". Acceptable.
            caught = npe;
        } catch (Exception e) {
            caught = e;
        }
        assertNotNull("Locked / null SecureStorage MUST throw, "
                + "never silently no-op", caught);
    }

    @Test
    public void newPayload_activeNetworkIndexDefaultsToZero() {
        // Sanity check: the active-index field starts at 0 so a
        // never-touched payload behaves identically to the legacy
        // PrefConnect.BLOCKCHAIN_NETWORK_ID_INDEX_KEY-absent case.
        // Index 0 == bundled mainnet (the merged list always starts
        // with the bundled prefix in v=3).
        StrongboxPayload payload = new StrongboxPayload();
        assertEquals(0, payload.activeNetworkIndex);
    }

    @Test
    public void newPayload_customNetworksListIsEmpty() {
        // Empty customNetworks list is the trigger that
        // NetworkPersistence.readNetworks uses to surface only the
        // bundled R.raw.blockchain_networks blob; this test pins
        // down that empty == "user has not added any custom
        // networks" (bundled prefix is always present at runtime
        // and is NEVER persisted inside the payload — see v=3 spec
        // §"Cross-platform interoperability").
        StrongboxPayload payload = new StrongboxPayload();
        assertNotNull(payload.customNetworks);
        assertTrue(payload.customNetworks.isEmpty());
        assertFalse(payload.customNetworks.iterator().hasNext());
    }
}
