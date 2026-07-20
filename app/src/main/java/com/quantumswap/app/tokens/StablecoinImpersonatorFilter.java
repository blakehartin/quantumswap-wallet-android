package com.quantumswap.app.tokens;

import com.quantumswap.app.api.read.model.AccountTokenSummary;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Hard-suppression filter for tokens whose name or symbol mimics a
 * known stablecoin denomination ("USDT", "USDC", "Tether", "DAI",
 * etc.) but whose contract address is NOT explicitly recognized by
 * {@link RecognizedTokens}.
 *
 * <h3>What it closes</h3>
 * On chains where there is no native USD-pegged stablecoin (the case
 * for this network at time of writing), an attacker can deploy a
 * worthless contract and name it "USDT" / "Tether USD" / "USDC". A
 * naive wallet will surface that token in the user's account view
 * simply because the indexer reports it, and the user - trained by
 * years of seeing "USDT" mean "1 dollar" - will believe they have
 * received real value. The wallet vendor has no way to confirm or
 * deny the legitimacy of any third-party contract, so the safest
 * stance is: any token whose label IMPLIES a fiat peg gets
 * hard-suppressed unless the vendor has explicitly recognized its
 * contract address.
 *
 * <h3>Why this shape</h3>
 * <ul>
 *   <li>Substring match (case-insensitive) instead of exact-equality:
 *       real-world impersonators typically pad the symbol ("USDT.e",
 *       "USD-Tether", "USDT_v2") to slip exact-match filters.</li>
 *   <li>Match against BOTH symbol AND name: an attacker who only
 *       sets {@code name = "Tether USD"} while leaving
 *       {@code symbol = "XXX"} would still confuse the user inside
 *       any UI that renders the name.</li>
 *   <li>Single chokepoint exposed via {@link #filter(List)}. Every
 *       consumer (home and Send screens) calls this ONE function
 *       before partitioning into recognized / unrecognized buckets.
 *       There is no "show impersonators" toggle anywhere in the
 *       UI - the suppression is total.</li>
 *   <li>The escape hatch is {@link RecognizedTokens#ALL}: if a future
 *       chain DOES launch a real "USDT" with a known contract, the
 *       wallet vendor adds that contract to the recognized list and
 *       the filter steps aside for that specific contract.</li>
 * </ul>
 *
 * <h3>Tradeoffs</h3>
 * <ul>
 *   <li>False positives: a legitimate gaming token literally named
 *       "USD-Acres" or a meme token with "stable" in its name will
 *       be silently hidden until added to {@link RecognizedTokens}.
 *       Withholding is strictly safer than surfacing.</li>
 *   <li>Pattern list is hard-coded in the binary, not loaded from
 *       the network. A new fiat denomination would require a binary
 *       update to start matching, but that's the same trust boundary
 *       as {@link RecognizedTokens}.</li>
 * </ul>
 *
 * <h3>Cross-references</h3>
 * <ul>
 *   <li>iOS counterpart:
 *       {@code QuantumSwapWallet/Models/StablecoinImpersonatorFilter.swift}.</li>
 * </ul>
 */
public final class StablecoinImpersonatorFilter {

    /**
     * Lower-case substring patterns matched against {@code symbol}
     * AND {@code name}. Any match suppresses the token unless its
     * contract address is in {@link RecognizedTokens#ALL}.
     *
     * <p>The list spans US-dollar (e.g. {@code usd}, {@code dai},
     * {@code tether}, {@code stable}, {@code frax}, {@code fdusd},
     * {@code lusd}, {@code tusd}, {@code gusd}, {@code pyusd},
     * {@code dollar}), euro ({@code eurt}, {@code eurc},
     * {@code eurs}, {@code euro}), Japanese yen ({@code yen}),
     * Chinese yuan ({@code cny}), British pound ({@code gbpt}), and
     * Indian rupee ({@code inr}, {@code rupee}, {@code rupiah})
     * impersonators. The Indonesian rupiah three-letter ticker
     * {@code idr} is intentionally NOT in the list because it would
     * collide with legitimate tokens that happen to contain those
     * three letters as a substring (e.g. {@code Hidro}, {@code Idris});
     * the spelled-out word {@code rupiah} catches realistic IDR
     * impersonators (e.g. {@code IDRT} / {@code Rupiah Token}) without
     * that false-positive risk.
     *
     * <p>Stays byte-for-byte aligned with the iOS counterpart at
     * {@code QuantumSwapWallet/Models/StablecoinImpersonatorFilter.swift}.
     */
    public static final List<String> PATTERNS = Collections.unmodifiableList(
            Arrays.asList(
                    "usd", "dai", "tether", "stable", "stablecoin",
                    "frax", "fdusd", "lusd", "tusd", "gusd", "pyusd",
                    "eurt", "eurc", "eurs",
                    "dollar", "euro", "yen", "gbpt", "cny",
                    "inr", "rupee", "rupiah"));

    private StablecoinImpersonatorFilter() { }

    /**
     * @return {@code true} iff the supplied symbol or name contains
     *     any pattern in {@link #PATTERNS} (case-insensitive substring
     *     match). {@code null} and empty strings are non-matching.
     */
    public static boolean impersonatesStablecoin(String symbol, String name) {
        String s = symbol == null ? "" : symbol.toLowerCase(Locale.ROOT);
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (s.isEmpty() && n.isEmpty()) return false;
        for (String p : PATTERNS) {
            if (!s.isEmpty() && s.contains(p)) return true;
            if (!n.isEmpty() && n.contains(p)) return true;
        }
        return false;
    }

    /**
     * Single chokepoint used by every consumer. Returns the input
     * list with stablecoin-impersonator tokens removed, EXCEPT for
     * tokens whose contract address is in {@link RecognizedTokens#ALL}
     * (those pass through unchanged even if their name happens to
     * match a pattern).
     */
    public static List<AccountTokenSummary> filter(List<AccountTokenSummary> tokens) {
        if (tokens == null) return new ArrayList<>();
        List<AccountTokenSummary> out = new ArrayList<>(tokens.size());
        for (AccountTokenSummary tok : tokens) {
            if (tok == null) continue;
            if (RecognizedTokens.isRecognized(tok.getContractAddress())) {
                out.add(tok);
                continue;
            }
            if (!impersonatesStablecoin(tok.getSymbol(), tok.getName())) {
                out.add(tok);
            }
        }
        return out;
    }
}
