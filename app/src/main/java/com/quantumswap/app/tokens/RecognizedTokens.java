package com.quantumswap.app.tokens;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Allow-list of token contract addresses that this wallet build will
 * mark as "recognized" (anti-impersonation / anti-phishing gate).
 *
 * <h3>What it closes</h3>
 * The scan API ({@code listAccountTokens}) returns every ERC-20-style
 * token the indexer has ever seen credited to the account. Without an
 * allow-list, an attacker who deploys a contract named "Heisen" or
 * symbol "HSN" on the same chain would surface alongside the genuine
 * token in the home screen and the Send picker, and the user would
 * have no easy way to tell them apart at the icon / row level.
 *
 * <h3>How it is used</h3>
 * <ol>
 *   <li>The home-screen token list is split into two tabs ("Tokens"
 *       vs "Unrecognized Tokens") via the segmented control on the
 *       home screen.</li>
 *   <li>The Send picker hides "Unrecognized Tokens" by default; a
 *       user-visible toggle ("Show Unrecognized Tokens") brings them
 *       back.</li>
 *   <li>The transaction-confirmation dialog surfaces the contract
 *       address explicitly so the user sees the bytes they are
 *       signing about.</li>
 * </ol>
 * Recognition is keyed strictly by contract address (not by name or
 * symbol) because the contract address is the only field a MitM-able
 * RPC indexer cannot mint copies of.
 *
 * <h3>Why this shape</h3>
 * <ul>
 *   <li>Hard-coded {@code static} constants in the app binary. The
 *       set of recognized contracts is part of the trust boundary of
 *       this release; it must not be rewritable from disk, from the
 *       network, or from the JS bundle.</li>
 *   <li>Lower-cased once at class-init time. Hex-string contract
 *       addresses are case-insensitive on chain; lower-casing the
 *       constants once means every {@link #isRecognized(String)} call
 *       is a single lower-case + set-membership test.</li>
 *   <li>{@link Set#contains} lookup is O(1) and avoids any iteration
 *       cost even if the recognized list grows to dozens of
 *       contracts.</li>
 * </ul>
 *
 * <h3>Tradeoffs</h3>
 * <ul>
 *   <li>This list ships in the binary and can only change with a new
 *       app release; if a new genuine token launches on this chain
 *       between releases, users will see it in the "Unrecognized
 *       Tokens" tab until the next update. This is the safer default:
 *       silent inclusion of an attacker-controlled contract would be
 *       far worse than a one-release delay for a real new token.</li>
 *   <li>Recognition does NOT mean "this token has been independently
 *       reviewed" - the wallet still has no way to enforce solvency,
 *       redemption, or honest balance reporting on a third-party
 *       contract. It means only "the wallet vendor vouches that this
 *       contract address is the real one for the listed name/symbol".</li>
 *   <li>The recognized list is intentionally <strong>not</strong>
 *       sourced from a remote-signed manifest. The wallet targets a
 *       post-quantum chain and pinning the authenticity of the
 *       recognized list to a classical signature scheme would
 *       contradict the project's cryptographic posture. If a future
 *       need emerges to revoke or add a recognized contract without
 *       an app release, the right answer is a deliberately-designed
 *       post-quantum signature scheme (Dilithium / SPHINCS+ / etc.).</li>
 * </ul>
 *
 * <h3>Cross-references</h3>
 * <ul>
 *   <li>{@link StablecoinImpersonatorFilter}: hard-suppresses tokens
 *       whose name/symbol mimic stablecoins UNLESS their contract
 *       address is in this allow-list.</li>
 *   <li>iOS counterpart: {@code QuantumSwapWallet/Models/RecognizedTokens.swift}.
 *       Both files MUST stay byte-aligned on the address constants
 *       so a wallet restored across platforms classifies the same
 *       contract identically.</li>
 * </ul>
 */
public final class RecognizedTokens {

    /**
     * Heisen (HSN). Hard-coded contract address for the genuine
     * Heisen token on this chain. Any other contract that happens
     * to use the name "Heisen" or symbol "HSN" will be classified
     * as Unrecognized.
     */
    public static final String HEISEN =
            "0xe8ea8beb86e714ef2bde0afac17d6e45d1c35e48f312d6dc12c4fdb90d9e8a3d";

    /**
     * Y2Q (Year-2-Quantum). Hard-coded contract address for the
     * genuine Y2Q token on this chain. Any other contract that
     * happens to use the name "Y2Q" or symbol "Y2Q" will be
     * classified as Unrecognized.
     */
    public static final String Y2Q =
            "0xa8036870874fbed790ed4d3bbd41b2f390b9858ff021f2993e90c6d1cbb167c7";

    /** Lion (Lio). */
    public static final String LION =
            "0x4015b40b181f2415003f24118b215ce04f276509176eccb10e0c4a9ccbd458d2";

    /** Tiger (tig). */
    public static final String TIGER =
            "0x6ff70c260458c9f448ec7aab008f1611456d58edb12e7795bf88735e1986a6ad";

    /** Cat (cat). */
    public static final String CAT =
            "0x592a8abb1de07bc3797bc3c592fc74c099c5a311ba856fc66fb6d4cfc18c728d";

    /** panther (pant). */
    public static final String PANTHER =
            "0x05fe2265b69d0c70a24075180242736c7389876b8917f38400e6540519e663df";

    /** Wrapped Q (WQ, Beta2 release). */
    public static final String WRAPPED_Q =
            "0x45bd01be5ef8509d9da183689ea7faf647331c54c7c9801de54c9ede9ac44d92";

    /**
     * Lower-cased, immutable set of all recognized contract
     * addresses; computed once at class-init time so each
     * {@link #isRecognized(String)} call is a single Set lookup.
     * Mirrors desktop src/lib/tokenfilter.ts
     * RECOGNIZED_TOKEN_ADDRESSES (7 entries).
     */
    public static final Set<String> ALL = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    HEISEN.toLowerCase(Locale.ROOT),
                    Y2Q.toLowerCase(Locale.ROOT),
                    LION.toLowerCase(Locale.ROOT),
                    TIGER.toLowerCase(Locale.ROOT),
                    CAT.toLowerCase(Locale.ROOT),
                    PANTHER.toLowerCase(Locale.ROOT),
                    WRAPPED_Q.toLowerCase(Locale.ROOT))));

    /**
     * Recognized contract addresses in a stable, deliberate display
     * order. Used by pickers that surface the full allow-list even
     * when the account holds none of the tokens (e.g. the Swap "To"
     * picker, where the user is acquiring a token they do not own
     * yet). {@link #ALL} stays the membership-test structure; this
     * list exists only so the UI ordering is not left to hash order.
     */
    public static final List<String> LISTED = Collections.unmodifiableList(
            Arrays.asList(HEISEN, Y2Q, LION, TIGER, CAT, PANTHER, WRAPPED_Q));

    /**
     * Tokens that burn or tax on transfer. The router has no fee-safe
     * exact-output form, so the swap screen keeps pairs touching one of
     * these exact-in (mirrors bridge.html
     * FEE_ON_TRANSFER_TOKEN_CONTRACT_ADDRESSES). Lower-case entries;
     * the membership test lower-cases its input.
     */
    private static final Set<String> FEE_ON_TRANSFER = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(HEISEN.toLowerCase(Locale.ROOT), Y2Q.toLowerCase(Locale.ROOT))));

    /** {@code true} iff the contract is a listed fee-on-transfer token;
     *  {@code null}, empty and the native sentinel return {@code false}. */
    public static boolean isFeeOnTransfer(String contract) {
        if (contract == null || contract.isEmpty()) return false;
        return FEE_ON_TRANSFER.contains(contract.toLowerCase(Locale.ROOT));
    }

    private RecognizedTokens() { }

    /**
     * Wallet-vouched ticker for a recognized contract, for rows the
     * scan API has no entry for (the account holds none of the token
     * so {@code listAccountTokens} never returned its symbol). These
     * strings ship in the binary next to the address constants and are
     * display-only: amount math still uses bridge-resolved decimals,
     * never anything derived from the symbol.
     *
     * @return the ticker ("HSN", "Y2Q"), or {@code null} when the
     *     address is not in the allow-list.
     */
    public static String displaySymbol(String contract) {
        if (contract == null) return null;
        if (contract.equalsIgnoreCase(HEISEN)) return "HSN";
        if (contract.equalsIgnoreCase(Y2Q)) return "Y2Q";
        if (contract.equalsIgnoreCase(LION)) return "Lio";
        if (contract.equalsIgnoreCase(TIGER)) return "tig";
        if (contract.equalsIgnoreCase(CAT)) return "cat";
        if (contract.equalsIgnoreCase(PANTHER)) return "pant";
        if (contract.equalsIgnoreCase(WRAPPED_Q)) return "WQ";
        return null;
    }

    /**
     * @return {@code true} iff the given contract address is in the
     *     recognized allow-list. {@code null} and empty inputs return
     *     {@code false} (native coin sends carry no contract; the
     *     native coin row is surfaced via a separate "QC native"
     *     affordance, NOT through this allow-list).
     */
    public static boolean isRecognized(String contract) {
        if (contract == null || contract.isEmpty()) return false;
        return ALL.contains(contract.toLowerCase(Locale.ROOT));
    }
}
