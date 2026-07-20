package com.quantumswap.app.api.read;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.quantumswap.app.api.read.model.AccountPendingTransactionSummaryResponse;
import com.quantumswap.app.api.read.model.AccountTokenListResponse;
import com.quantumswap.app.api.read.model.AccountTokenSummary;
import com.quantumswap.app.api.read.model.AccountTransactionSummary;
import com.quantumswap.app.api.read.model.AccountTransactionSummaryResponse;
import com.quantumswap.app.api.read.model.BalanceResponse;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

/**
 * Golden-fixture decoder tests (Android, mirrors iOS
 * {@code QuantumSwapWalletTests/ApiDecodingTests.swift}).
 * <p>Each test loads a captured-from-live-API JSON fixture under
 * {@code app/src/test/resources/api-fixtures/} and decodes it with
 * the same {@link Gson} configuration the production
 * {@link com.quantumswap.app.api.read.ApiClient} uses. The goal
 * is to lock in two preserved API quirks that have bitten the wallet
 * before:
 * <ol>
 *   <li>The transactions endpoints return {@code "items"} in the wire
 *       payload but the model class exposes the field as
 *       {@code result} via {@code @SerializedName("items")}. A
 *       reviewer renaming the SerializedName to "result" silently
 *       breaks decoding without compile error.</li>
 *   <li>The balance endpoint nests the value under a {@code "balance"}
 *       key but the Java field is {@code _Balance} (a
 *       SerializedName mismatch fix that has happened before).
 *       Same regression class.</li>
 * </ol>
 * <p>we use the {@link Class#getResource}
 * loader instead of {@link Bundle}-equivalent paths so the fixtures
 * live next to the test, version-controlled as part of the same PR
 * that touches a model field. iOS and Android share the exact same
 * fixture shape (the iOS suite uses {@code Bundle.url(forResource:
 * withExtension:)}); keep them in lock-step when a new API field
 * lands.
 * <p>Skip-if-missing pattern: tests {@link org.junit.Assume} out if
 * the fixture file is absent, so an out-of-band fixture removal
 * does not red-bar the suite. The presence test
 * {@link #fixturesShipWithTestResources()} guarantees we still notice
 * if the fixtures are deleted entirely.
 */
public class ApiDecodingTest {

    private static final String[] REQUIRED_FIXTURES = new String[] {
            "account_balance.json",
            "account_transactions.json",
            "account_pending_transactions.json",
            "account_tokens.json",
    };

     /**
     * Build a Gson configured the same way the production
     * {@link com.quantumswap.app.api.read.ApiClient} does PLUS a
     * {@link OffsetDateTime} type adapter. The production client runs
     * on Android (where the strict-reflection-on-JDK-internals issue
     * does not apply); on the JVM 21 unit-test classpath we register
     * the adapter explicitly so a model field of type
     * {@code OffsetDateTime} does not blow up the reflective binder.
     * The adapter accepts an ISO-8601 string or null.
     */
    private static Gson gson() {
        JsonDeserializer<OffsetDateTime> odtDe = (json, typeOfT, ctx) -> {
            if (json == null || json.isJsonNull()) return null;
            String s = json.getAsString();
            return (s == null || s.isEmpty()) ? null : OffsetDateTime.parse(s);
        };
        return new GsonBuilder()
                .registerTypeAdapter(OffsetDateTime.class, odtDe)
                .create();
    }

    @Test
    public void fixturesShipWithTestResources() {
        for (String name : REQUIRED_FIXTURES) {
            assertNotNull("Missing fixture " + name + " under app/src/test/resources/api-fixtures/. " +
                            "Either restore the file or remove it from REQUIRED_FIXTURES.",
                    loadFixtureBytes(name));
        }
    }

    @Test
    public void accountTransactionsDecodes_itemsKey_intoResultField() {
        byte[] data = loadFixtureBytes("account_transactions.json");
        org.junit.Assume.assumeNotNull((Object) data);
        AccountTransactionSummaryResponse resp =
                gson().fromJson(new String(data, StandardCharsets.UTF_8),
                        AccountTransactionSummaryResponse.class);
        assertNotNull(resp);
        assertNotNull("result list (wire 'items') must decode", resp.getResult());
        assertFalse(resp.getResult().isEmpty());
        AccountTransactionSummary first = resp.getResult().get(0);
        assertNotNull(first);
    }

    @Test
    public void pendingTransactionsDecodes_itemsKey_intoResultField() {
        byte[] data = loadFixtureBytes("account_pending_transactions.json");
        org.junit.Assume.assumeNotNull((Object) data);
        AccountPendingTransactionSummaryResponse resp =
                gson().fromJson(new String(data, StandardCharsets.UTF_8),
                        AccountPendingTransactionSummaryResponse.class);
        assertNotNull(resp);
        assertNotNull(resp.getResult());
        assertFalse(resp.getResult().isEmpty());
    }

    @Test
    public void accountBalanceDecodes_balanceKey_into_BalanceField() {
        byte[] data = loadFixtureBytes("account_balance.json");
        org.junit.Assume.assumeNotNull((Object) data);
        BalanceResponse resp = gson().fromJson(new String(data, StandardCharsets.UTF_8),
                BalanceResponse.class);
        assertNotNull(resp);
        assertNotNull(resp.getResult());
        assertNotNull("balance value must decode under @SerializedName(\"balance\")",
                resp.getResult().getBalance());
    }

    @Test
    public void accountTokensDecodes() {
        byte[] data = loadFixtureBytes("account_tokens.json");
        org.junit.Assume.assumeNotNull((Object) data);
        AccountTokenListResponse resp = gson().fromJson(new String(data, StandardCharsets.UTF_8),
                AccountTokenListResponse.class);
        assertNotNull(resp);
        assertNotNull(resp.getItems());
        assertFalse(resp.getItems().isEmpty());
        AccountTokenSummary first = resp.getItems().get(0);
        assertNotNull(first.getContractAddress());
        assertEquals("HEISEN", first.getSymbol());
        assertEquals(Integer.valueOf(18), first.getDecimals());
    }

     /**
     * Loads the fixture from the test classpath. Returns null if the
     * fixture is absent so callers can {@link org.junit.Assume} out
     * gracefully.
     */
    private static byte[] loadFixtureBytes(String name) {
        try (InputStream is = ApiDecodingTest.class.getClassLoader()
                .getResourceAsStream("api-fixtures/" + name)) {
            if (is == null) return null;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
