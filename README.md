# QuantumSwap Wallet — Android

[![Platform: Android 10+](https://img.shields.io/badge/platform-Android%2010%2B-green)](https://developer.android.com)
[![Java: 8](https://img.shields.io/badge/java-8-orange)](https://www.oracle.com/java/)
[![License: MIT](https://img.shields.io/badge/license-MIT-green)](LICENSE)

> **iOS counterpart:** the sibling [QuantumSwap iOS wallet](https://github.com/quantumcoinproject/quantumswap-wallet-ios)
> is kept feature-parity with this Android client. Both share the same
> JavaScript SDK bundle byte-for-byte and the same `en_us.json`
> localization catalog as the canonical reference; the Android-side
> `JsonInteractParityTest` keeps the key set and accessor surface in
> lockstep with the iOS catalog.

Native Android client for the [Quantum Coin](https://quantumcoin.org)
post-quantum blockchain. Quantum Coin is a Layer-1 quantum-resistant
blockchain that combines NIST-standardized post-quantum signature
schemes — **ML-DSA (FIPS 204)** and **SLH-DSA (FIPS 205)** — with
**ML-KEM (FIPS 203)** for node-to-node key establishment, all under
a deposit-weighted BFT consensus with immediate deterministic
finality. See the
[quantum-resistance whitepaper](https://quantumcoin.org/whitepapers/Quantum-Coin-Blockchain-Quantum-Resistance-Whitepaper.html)
and [consensus whitepaper](https://quantumcoin.org/whitepapers/Quantum-Coin-Blockchain-Consensus-Whitepaper.html)
for the protocol-level rationale.

This repository hosts the **Android** wallet. It is a feature-parity
port of the [Quantum Coin iOS wallet](https://github.com/quantumcoinproject/quantumswap-wallet-ios)
and shares the same JavaScript SDK bundle byte-for-byte, so every
signed transaction is reproducible across both clients.

> **Status:** beta. The mainnet RPC is configured at
> `https://public.rpc.quantumcoinapi.com` (chain id `123123`). See
> [`app/src/main/res/raw/blockchain_networks.json`](app/src/main/res/raw/blockchain_networks.json).

> **This software is not an investment opportunity, an investment
> contract, or a security of any type.** See the
> [Quantum Coin homepage](https://quantumcoin.org) for the project's
> charter and decentralization-first stance.

---

## Table of contents

- [Feature list](#feature-list)
- [Security & durability features](#security--durability-features)
- [Strongbox cryptographic specification](#strongbox-cryptographic-specification)
- [Cross-platform interoperability](#cross-platform-interoperability)
- [SDKs and dependencies](#sdks-and-dependencies)
- [Architecture overview](#architecture-overview)
- [Repository layout](#repository-layout)
- [Build and run](#build-and-run)
- [Testing](#testing)
- [Threat model & non-goals](#threat-model--non-goals)
- [License](#license)
- [Further reading](#further-reading)

---

## Feature list

### Wallet management

- **Multiple wallets per install.** Stored in a single
  layered-encrypted strongbox; each wallet is addressable via the
  Wallets screen
  ([`view/fragment/WalletsFragment.java`](app/src/main/java/com/quantumswap/app/view/fragment/WalletsFragment.java)).
- **New wallet creation** with a 32-word seed phrase
  (`QuantumSwapSDK.Wallet.createRandom` →
  `SeedWordsSDK.getWordListFromSeedArray`). Seed verification quiz
  is enforced before the wallet is persisted.
- **Restore from seed words** with BIP39-style prefix
  auto-completion (the word list is bundled and queried through
  the JS bridge so Android and iOS share one source of truth).
- **Restore from `.wallet` backup file** — single file or a folder
  via the Storage Access Framework; batched password prompt walks
  through every wallet in the picked location
  ([`backup/CloudBackupManager.java`](app/src/main/java/com/quantumswap/app/backup/CloudBackupManager.java)).
- **Reveal seed words** (gated by tap-to-reveal +
  `setImportantForAccessibility(NO_HIDE_DESCENDANTS)` so
  TalkBack never reads the words aloud, plus
  `WindowManager.LayoutParams.FLAG_SECURE` so the screen is
  excluded from screenshots and screen recordings).
- **Delete wallet / delete all** with explicit confirm dialogs.

### Sending and receiving

- **Send native QC** via the SDK's
  `wallet.sendTransaction({to, value, gasLimit, signingContext})`
  (matches iOS byte-for-byte; see
  [JS SDK boundaries](#sdks-and-dependencies) below).
- **Send tokens (ERC-20-style)** via
  `IERC20.connect(contract, wallet).transfer(...)`. Token list is
  partitioned into **Tokens** (recognized) and **Unrecognized
  Tokens** tabs; impersonator filter blocks any token whose
  symbol or name resembles a stablecoin unless the contract is
  on the recognized allow-list
  ([`tokens/RecognizedTokens.java`](app/src/main/java/com/quantumswap/app/tokens/RecognizedTokens.java),
  [`tokens/StablecoinImpersonatorFilter.java`](app/src/main/java/com/quantumswap/app/tokens/StablecoinImpersonatorFilter.java)).
- **Transaction review dialog** with checksum-cased addresses,
  fee summary, and explicit contract-address row for token sends
  ([`view/fragment/SendFragment.java`](app/src/main/java/com/quantumswap/app/view/fragment/SendFragment.java)).
- **QR-code scanning** for recipient addresses via CameraX +
  ML Kit Barcode Scanning (`android.permission.CAMERA` declared in
  `AndroidManifest.xml`).
- **Receive screen** with a `quantumcoin:` URI QR code and a
  centred copy-to-clipboard control with 30-second auto-clear
  ([`view/fragment/ReceiveFragment.java`](app/src/main/java/com/quantumswap/app/view/fragment/ReceiveFragment.java),
  [`utils/SecureClipboard.java`](app/src/main/java/com/quantumswap/app/utils/SecureClipboard.java)).

### Network configuration

- **Mainnet preconfigured** at chain id `123123` and the public
  RPC endpoint
  ([`app/src/main/res/raw/blockchain_networks.json`](app/src/main/res/raw/blockchain_networks.json)).
- **Custom network support** — the user can add and switch between
  networks; the active network is captured at "Review" time and
  re-asserted at "Submit" time via a `NetworkSnapshot` so a
  network switch in the middle of the signing flow aborts rather
  than producing a mis-bound transaction
  ([`networking/NetworkSnapshot.java`](app/src/main/java/com/quantumswap/app/networking/NetworkSnapshot.java)).
- **Live network-change notifications** via `LocalBroadcastManager`
  push the new active network into every visible Fragment without
  an Activity restart; the broadcaster is process-local by design
  so other apps cannot observe or forge network-state events
  ([`events/NetworkChangeBroadcaster.java`](app/src/main/java/com/quantumswap/app/events/NetworkChangeBroadcaster.java)).

### Backup and restore

- **File backup** via the Storage Access Framework
  (`Intent.ACTION_CREATE_DOCUMENT`) — wallet is re-encrypted
  under a user-supplied backup password (independent of the
  unlock password), then handed to the picker.
- **Restore from folder** — user picks a folder via the Storage
  Access Framework (`Intent.ACTION_OPEN_DOCUMENT_TREE`); the
  picker opens at the same remembered location used by the file
  backup save picker. All `.wallet` files in the picked folder are
  enumerated and run through the same batched-decrypt loop the
  single-file restore uses
  ([`backup/CloudBackupManager.java`](app/src/main/java/com/quantumswap/app/backup/CloudBackupManager.java)).
- **Android Auto-Backup gate** — wallet files are excluded from
  Auto-Backup by default
  ([`app/src/main/res/xml/backup_rules.xml`](app/src/main/res/xml/backup_rules.xml),
  [`app/src/main/res/xml/data_extraction_rules.xml`](app/src/main/res/xml/data_extraction_rules.xml)).
  A user-visible toggle in Settings opts files in; the toggle
  immediately calls `BackupManager.dataChanged()` so the include
  rule takes effect on the next backup cycle without waiting for
  the next strongbox write
  ([`backup/WalletBackupAgent.java`](app/src/main/java/com/quantumswap/app/backup/WalletBackupAgent.java)).
- **Cross-platform backup compatibility.** Per-wallet exported
  `.wallet` files are produced by the shared
  `quantumswap-bundle.js` `Wallet.encryptSync` call on both
  platforms, so a `.wallet` file written by the Android wallet
  can be restored by the iOS wallet (and vice versa) using the
  same backup password. The whole-strongbox slot
  (`getFilesDir()/strongbox/DP_QUANTUM_COIN_WALLET_APP_PREF.{A|B}.json`)
  is **also** portable in v=3 — copying it to the iOS app's
  `Application Support/` directory preserves the encrypted bytes,
  and the iOS wallet unlocks it with the same wallet password.
  See the [Cross-platform
  interoperability](#cross-platform-interoperability) section for
  the per-byte parity contract.

### Localization and accessibility

- English (`en_us`) localization with 230+ keys
  ([`app/src/main/res/raw/en_us.json`](app/src/main/res/raw/en_us.json),
  [`interact/JsonInteract.java`](app/src/main/java/com/quantumswap/app/interact/JsonInteract.java),
  [`viewmodel/JsonViewModel.java`](app/src/main/java/com/quantumswap/app/viewmodel/JsonViewModel.java)).
- TalkBack / accessibility deliberately suppressed on the four
  seed-handling surfaces (reveal, new-seed, verify, restore) via
  `setImportantForAccessibility(NO_HIDE_DESCENDANTS)` so the
  seed words are never read aloud.
- `passwordToggleContentDescription` on every `TextInputLayout`
  so the eye icon announces "Show password" / "Hide password" to
  screen readers; matching `show-password` / `hide-password`
  localization keys are mirrored in both Android and iOS
  `en_us.json` files.
- Dark mode (`values-night/`) with a small palette of semantic
  colors; primary buttons invert foreground in dark mode for
  contrast.

---

## Security & durability features

This is a high-value-asset wallet — every defense below has a
dedicated design-notes Javadoc block in its source file with the
threat it closes, the design rationale, and the tradeoff the team
accepted. The companion document
[`STORAGE_LAYERED_MODEL.md`](STORAGE_LAYERED_MODEL.md) gives a
byte-level walkthrough of the strongbox and the cross-platform
contract.

### Key material and signing

- **AES-256-GCM** for every encrypted-at-rest blob, in a single
  Java owner so there is one review surface for AEAD usage
  ([`keystorage/Aead.java`](app/src/main/java/com/quantumswap/app/keystorage/Aead.java)).
- **scrypt KDF** at `N=2^18, r=8, p=1, keyLen=32` — runs inside
  the shared JS bundle so the Android and iOS wallets derive
  identical keys for identical passwords. Min-bound enforced at
  the bridge boundary so a future debug-weakened call fails loud
  ([`bridge/QuantumSwapJSBridge.java`](app/src/main/java/com/quantumswap/app/bridge/QuantumSwapJSBridge.java),
  [`app/src/main/assets/bridge.html`](app/src/main/assets/bridge.html) `scryptDerive`).
- **Brute-force lockout** with a stair-step backoff
  (typo-tolerant for the first four attempts; 30 s, 60 s, 2 min,
  5 min cap; no permanent lockout so a typo-storm user is never
  bricked). Counter persists in a dedicated app-private
  `SharedPreferences` file
  (`qc_unlock_attempt_limiter_v2`) and uses
  `SystemClock.elapsedRealtimeNanos()` so the lockout window is
  immune to wall-clock writes from Settings AND counts time
  spent with the device asleep. Reboot detection (stored
  monotonic value larger than `now`) applies the maximum 5-min
  tier on the first post-reboot attempt to defeat the
  fail-N-times-then-reboot bypass. The limiter is fed from BOTH
  the dedicated strongbox-unlock dialogs AND the backup-restore
  flows in `HomeWalletFragment` (`STRONGBOX_UNLOCK` and
  `BACKUP_DECRYPT` channels) so an attacker cannot bypass the
  rate limit by mounting attempts through the restore dialog
  ([`security/UnlockAttemptLimiter.java`](app/src/main/java/com/quantumswap/app/security/UnlockAttemptLimiter.java),
  [`view/fragment/HomeWalletFragment.java`](app/src/main/java/com/quantumswap/app/view/fragment/HomeWalletFragment.java)
  `attemptBatchDecrypt` / `performRestoreFromUri`).
- **Tamper gate** — multi-signal root / debugger-attached / Frida
  detector. Requires ≥2 independent root signals before flagging;
  debugger-in-Release and runtime-instrumentation are hard
  signals. The signing chokepoint
  (`QuantumSwapJSBridge.sendTransaction`) calls
  `TamperGate.assertSafeToSign()` before the private key reaches
  the bridge
  ([`security/TamperGate.java`](app/src/main/java/com/quantumswap/app/security/TamperGate.java)).
- **JS bundle SHA-256 pin** — the bundle owns every signing
  primitive, so its bytes are hashed at build time by the
  `embedBundleHash` Gradle task, embedded in
  `GeneratedBundleHash.java` (which lives inside `classes.dex`
  and inherits the APK signature), re-hashed at runtime, and the
  bridge refuses to initialize on mismatch
  ([`app/build.gradle`](app/build.gradle) `embedBundleHash`,
  [`security/BundleIntegrity.java`](app/src/main/java/com/quantumswap/app/security/BundleIntegrity.java)).
- **Binary key channel** between Java and JS — private/public key
  bytes stage as `Uint8Array` via a synchronous custom-scheme
  pull rather than base64 strings, so JS can `.fill(0)` them
  after use (string-pool residency would otherwise prevent
  zeroization). The pull-channel design (`storePendingPayload` →
  `pullPayloadBinary`) is grep-guarded by a CI test that fails
  the build if a sensitive arg is ever passed inline through
  `evaluateJavascript`
  ([`bridge/QuantumSwapJSBridge.java`](app/src/main/java/com/quantumswap/app/bridge/QuantumSwapJSBridge.java),
  [`app/src/test/java/com/quantumswap/app/bridge/SendSurfaceLockdownTest.java`](app/src/test/java/com/quantumswap/app/bridge/SendSurfaceLockdownTest.java)).
- **Strict TLS 1.3-only floor on every Java/Kotlin TLS client.**
  `TlsPinning.applyTo(...)` installs an explicit
  `ConnectionSpec` built from `RESTRICTED_TLS` and narrowed to
  `TlsVersion.TLS_1_3` on every `OkHttpClient.Builder` the
  `ApiClient` hands out. There is no TLS 1.2 fallback and no
  `.compatible` chained spec — any server presenting a sub-1.3
  handshake fails `SSLHandshakeException` at connect time rather
  than silently downgrading. `minSdk = 29` (Android 10) is the
  floor that guarantees the platform `SSLEngine` can negotiate
  TLS 1.3 natively, so the strict requirement never starves a
  legitimate device
  ([`security/TlsPinning.java`](app/src/main/java/com/quantumswap/app/security/TlsPinning.java) `TLS_1_3_ONLY`,
  [`app/build.gradle`](app/build.gradle) `minSdk`).
- **Post-quantum hybrid key exchange (Android 14+).** The strict
  spec inherits the cipher-suite allow-list from `RESTRICTED_TLS`
  (the three TLS 1.3 AEAD suites are usable; the TLS 1.2 ECDHE
  carry-overs are unreachable because the version floor is
  pinned to TLS 1.3) and INTENTIONALLY does not restrict
  NamedGroups. NamedGroup negotiation lives at the platform /
  Conscrypt layer; on Android 14+ Conscrypt advertises the
  `X25519MLKEM768` hybrid group (ML-KEM-768 + X25519,
  IETF `draft-ietf-tls-hybrid-design`, NIST FIPS 203 final). When
  the server (e.g. Cloudflare, Google front-ends) also advertises
  the hybrid group it gets negotiated automatically and the
  handshake is harvest-now-decrypt-later resistant for all
  subsequent traffic on that connection. On Android 10-13 only
  classical groups (X25519, secp256r1) are available, so PQC is a
  runtime-dependent enhancement rather than a guarantee — the
  strict TLS 1.3 floor still applies regardless. Pinning TLS 1.3
  cipher suites does NOT inhibit PQC because PQC lives in the
  key-exchange layer, not the AEAD layer.
- **TLS pinning** on the centralized scan API only, via OkHttp's
  `CertificatePinner` populated from a per-host SPKI hash table.
  RPC endpoints are deliberately **not** pinned — the wallet is
  non-custodial and the user must be free to choose any RPC node
  (full node, Infura-class third party, community RPC). Pinning
  the RPC would hard-code centralization that the project
  explicitly rejects. Baseline TLS chain validation still applies
  on every endpoint
  ([`security/TlsPinning.java`](app/src/main/java/com/quantumswap/app/security/TlsPinning.java)).
- **Strict hostname verification** on every `ApiClient` HTTPS
  request. The `ApiClient.applySslSettings()` builder
  intentionally does NOT call `.hostnameVerifier(...)` — by
  OkHttp contract, `sslSocketFactory(SSLSocketFactory,
  X509TrustManager)` preserves the default
  `OkHostnameVerifier.INSTANCE` (strict RFC 2818 / RFC 6125
  hostname matching against SubjectAlternativeName entries). A
  Javadoc-grade audit comment in
  [`ApiClient.java`](app/src/main/java/com/quantumswap/app/api/read/ApiClient.java)
  documents the contract and bans any future
  `NoopHostnameVerifier`-style regression.
- **Network-security config** declares `usesCleartextTraffic=false`
  app-wide so any accidental `http://` URL fails at the platform
  layer
  ([`app/src/main/res/xml/network_security_config.xml`](app/src/main/res/xml/network_security_config.xml)).

### Storage durability

- **Two-slot rotating writer** for the strongbox file, so a power
  cut between rename and journal-flush still leaves a valid
  previous-good slot
  ([`storage/AtomicSlotWriter.java`](app/src/main/java/com/quantumswap/app/storage/AtomicSlotWriter.java)).
- **`FileChannel.force(true)`** on every persisted file (the
  Android equivalent of iOS `F_FULLFSYNC`) so bytes reach the
  storage media, not just the page cache. The parent directory
  is also `force(true)`-flushed after every rename so the
  rename's metadata is durable.
- **Verify-before-promote** — after `force(true)`, the writer
  re-reads the staged bytes uncached, hands them to a
  schema-aware deep-verify closure, and only then renames the
  `.tmp` into place. Catches NAND bit-flips, encoder bugs, and
  stale-key MAC mismatches.
- **File-level MAC** with a UI-block hash binding so an attacker
  who swaps slot files' UI prefs cannot re-bind them under the
  original MAC
  ([`strongbox/StrongboxFileCodec.java`](app/src/main/java/com/quantumswap/app/strongbox/StrongboxFileCodec.java)).
- **Anti-rollback generation counter** — every successful unlock
  validates a monotonic generation HMAC bound to an
  AndroidKeystore-resident key, so a slot file copied off the
  device and replayed is rejected at the next unlock
  ([`keystorage/MacUtil.java`](app/src/main/java/com/quantumswap/app/keystorage/MacUtil.java)).
- **App-private `Context.getFilesDir()/strongbox/`** for every
  slot file. Auto-Backup inclusion is governed explicitly by
  [`app/src/main/res/xml/backup_rules.xml`](app/src/main/res/xml/backup_rules.xml)
  and gated at runtime by `WalletBackupAgent` reading the
  `BACKUP_ENABLED_KEY` toggle.
- **4 MiB ISO/IEC 7816-4 padding** on the AEAD plaintext so the
  encrypted file size leaks no information about the wallet
  count. The bucket is sized to comfortably hold ≥256 wallets
  with PQC private/public key pairs (≈10 KiB each)
  ([`strongbox/StrongboxPadding.java`](app/src/main/java/com/quantumswap/app/strongbox/StrongboxPadding.java)).

### UI hardening

- **App-switcher snapshot redaction** — opaque branded
  `FrameLayout` overlay added in `Application.ActivityLifecycle
  Callbacks.onActivityPaused` / removed in `onActivityResumed`,
  so the system-captured Recents card never contains seed words,
  balances, or addresses
  ([`ux/SnapshotRedactor.java`](app/src/main/java/com/quantumswap/app/ux/SnapshotRedactor.java)).
- **`FLAG_SECURE` audit** across every Activity that may render a
  seed phrase, so the OS refuses to include the screen in
  screenshots, screen recordings, and casts
  ([`view/activities/HomeActivity.java`](app/src/main/java/com/quantumswap/app/view/activities/HomeActivity.java)).
- **Screen-capture observer** uses `DisplayManager` to detect
  secondary displays / mirroring and exposes an `isCapturing`
  signal that seed surfaces consult on every API level (no API
  33+ requirement)
  ([`security/ScreenCaptureGuard.java`](app/src/main/java/com/quantumswap/app/security/ScreenCaptureGuard.java)).
- **Clipboard auto-expiry** for copied seed phrases (30s
  countdown; cleared on view-disappear); copied addresses go
  through the same `SecureClipboard` facade so a future blanket
  policy change has one entry point
  ([`utils/SecureClipboard.java`](app/src/main/java/com/quantumswap/app/utils/SecureClipboard.java)).
- **Token impersonation defenses** — recognized-token allow-list
  by **contract address** (not name/symbol), plus a stablecoin
  impersonator hard-suppressor that blocks any token whose label
  resembles `USDT` / `USDC` / `Tether` / etc. unless its
  contract is on the allow-list
  ([`tokens/RecognizedTokens.java`](app/src/main/java/com/quantumswap/app/tokens/RecognizedTokens.java),
  [`tokens/StablecoinImpersonatorFilter.java`](app/src/main/java/com/quantumswap/app/tokens/StablecoinImpersonatorFilter.java)).
- **Network-snapshot capture at Review time** — the chain id and
  RPC endpoint the user confirmed are re-asserted at Submit time
  via `NetworkSnapshot`; a network switch mid-flight aborts
  rather than producing a mis-bound EIP-155 signature.
- **Logger facade** — every direct `android.util.Log.*` and
  `Timber.tag(...).*` call in security-sensitive files is routed
  through a single
  [`Logger`](app/src/main/java/com/quantumswap/app/Logger.java)
  facade. Release builds drop VERBOSE/DEBUG/INFO entirely and
  replace WARN/ERROR payload with an `evt` marker; debug builds
  apply address / tx-hash / long-hex / base64 redaction before
  the message reaches logcat. A CI grep guard
  ([`LoggerFacadeLockdownTest`](app/src/test/java/com/quantumswap/app/LoggerFacadeLockdownTest.java))
  fails the build if a stray direct `Log.*` call slips into a
  security-sensitive file.

### Password-manager (Autofill) integration

The wallet offers the device's Autofill provider to **generate, save,
and re-fill** the single **unlock password** and each **per-wallet
backup password**. All wiring is centralized in
[`security/CredentialIdentifier.java`](app/src/main/java/com/quantumswap/app/security/CredentialIdentifier.java).

- **The OS draws the password sheet, not the app.** The "Suggest strong
  password" / "Save password?" sheets are presented by the active
  Autofill provider; the app only marks fields so the provider can act.
  If no provider (or a non-generating one) is selected, no sheet appears.
- **Per-credential scoping via a synthetic username field.**
  `CredentialIdentifier.attachUsernameField(...)` injects a hidden
  username next to each password field so the provider stores each
  secret in its own slot instead of overwriting a single app-wide entry:
  - unlock: `QuantumSwap-<deviceSuffix>`
  - backup: `QuantumSwap-backup-<canonical-address>-<deviceSuffix>`

  The wallet address is canonicalized (lowercase, `0x` prefix) so the
  save site (create) and the suggest site (restore) produce an identical
  username on the same device; otherwise a saved backup password would
  never be suggested on restore. `<deviceSuffix>` is a per-install UUID
  that isolates credentials across devices.
- **The synthetic username field stays visible to autofill.** It is
  rendered imperceptibly (1dp tall, ~1% alpha) rather than fully
  transparent, because the framework treats a zero-alpha view as
  invisible and the provider will not pre-fill the username from an
  invisible field — leaving its save sheet asking the user to type one.
- **Finishing the autofill context differs by host.** The set-password
  screen is a fragment inside an Activity that is never finished, so it
  explicitly commits the autofill context once the password is accepted
  to trigger the save sheet. The backup dialogs instead let the context
  finish when the dialog is dismissed (its views become invisible); they
  must not commit early, or the pending save sheet is cancelled.
- **"Suggest strong password" uses the provider's own credential sheet.**
  Google's password-generation flow shows a dedicated save sheet that
  requires a username and ignores the app-supplied username value. The
  normal (manually typed) save sheet honors the synthetic username and
  pre-fills it.
- **No save prompt after an autofill is expected.** When a password box
  is filled from an already-saved credential, the OS suppresses the save
  sheet — there is nothing new to store.
- **Auto-presenting the saved password on unlock.** When the unlock
  dialog's password field gains focus, the app requests autofill so the
  saved password is offered immediately, without the user opening the
  keyboard's autofill menu.

Relevant files:
[`view/fragment/HomeWalletFragment.java`](app/src/main/java/com/quantumswap/app/view/fragment/HomeWalletFragment.java)
(set-password + post-create backup),
[`view/dialog/BackupPasswordDialog.java`](app/src/main/java/com/quantumswap/app/view/dialog/BackupPasswordDialog.java)
(backup create / restore),
[`view/activities/HomeActivity.java`](app/src/main/java/com/quantumswap/app/view/activities/HomeActivity.java)
(unlock dialog).

### Defense layering recap

Each layer below independently raises an attacker's cost; they
combine multiplicatively, not additively:

| Layer | Mechanism |
| --- | --- |
| Storage  | Two-slot rotation + `FileChannel.force(true)` + verify-before-promote |
| Schema   | File-level MAC binds wraps + payload + UI-block hash |
| Crypto   | AES-256-GCM AEAD + scrypt-derived 32-byte keys |
| Unlock   | scrypt cost + AndroidKeystore-bound brute-force lockout |
| Runtime  | Tamper gate + JS bundle SHA-256 pin |
| UI       | Snapshot redaction + `FLAG_SECURE` + clipboard expiry + impersonator filter |
| Network  | Strict TLS 1.3-only floor (PQC-hybrid auto-negotiated on Android 14+) + chain validation on all + SPKI pin on scan API |
| Logs     | Logger facade + release-mode payload drop + debug-mode redaction |

---

## Strongbox cryptographic specification

This section is the byte-level specification of the on-disk
wallet store. It documents the v=3 portable schema that Android
and iOS both write and read byte-for-byte. The authoritative
source is the code; every constant quoted below is a direct
citation from the cited files. A companion document
[`STORAGE_LAYERED_MODEL.md`](STORAGE_LAYERED_MODEL.md) gives the
prose narrative alongside the same constants.

### 1. Layered model

The strongbox is built as five disjoint layers. Each layer
knows about the layer immediately below it and about nothing
above it; cross-layer leakage is enforced by review and by
invariant tests.

| Layer | Responsibility | Source |
| --- | --- | --- |
| L1 — Storage | Two-slot atomic-rotate with deep verify | [`storage/AtomicSlotWriter.java`](app/src/main/java/com/quantumswap/app/storage/AtomicSlotWriter.java) |
| L2 — Schema | Outer file envelope, file-level MAC, padding | [`strongbox/StrongboxFileCodec.java`](app/src/main/java/com/quantumswap/app/strongbox/StrongboxFileCodec.java), [`strongbox/StrongboxPadding.java`](app/src/main/java/com/quantumswap/app/strongbox/StrongboxPadding.java) |
| L3 — Crypto | AES-256-GCM (JCE), HMAC-SHA-256 (JCE), HKDF-SHA-256 (BouncyCastle), scrypt (JS SDK) | [`keystorage/Aead.java`](app/src/main/java/com/quantumswap/app/keystorage/Aead.java), [`keystorage/MacUtil.java`](app/src/main/java/com/quantumswap/app/keystorage/MacUtil.java), bridge `scryptDerive` ([`assets/bridge.html`](app/src/main/assets/bridge.html)) |
| L4 — Unlock coordinator | scrypt → unwrap mainKey → install snapshot; re-derive per write; zeroize | [`keystorage/UnlockCoordinator.java`](app/src/main/java/com/quantumswap/app/keystorage/UnlockCoordinator.java) |
| L5 — Strongbox accessor | In-memory typed snapshot, inner checksum, per-wallet wire codec | [`keystorage/SecureStorage.java`](app/src/main/java/com/quantumswap/app/keystorage/SecureStorage.java), [`strongbox/StrongboxPayload.java`](app/src/main/java/com/quantumswap/app/strongbox/StrongboxPayload.java), [`strongbox/WalletEntryCodec.java`](app/src/main/java/com/quantumswap/app/strongbox/WalletEntryCodec.java) |

### 2. On-disk slot layout (L1)

| Item | Value |
| --- | --- |
| Number of slots | 2 (`AtomicSlotWriter.Slot.A`, `Slot.B`) |
| Directory | `Context.getFilesDir() + "/strongbox/"` |
| Filenames | `DP_QUANTUM_COIN_WALLET_APP_PREF.A.json`, `…B.json` |
| Staging filename | `…A.json.tmp` / `…B.json.tmp` |
| Persisted-data flush | `FileOutputStream.getFD().sync()` (closest Android equivalent of iOS `F_FULLFSYNC`; the platform exposes no `F_FULLFSYNC`-class barrier) |
| Parent-directory flush | `Os.fsync(dirFd)` after `File.renameTo` so the rename's metadata is durable on the storage media |
| Verify-before-promote | Re-read staged bytes; caller-supplied `verify` runs MAC verify, AEAD-open, depad, JSON decode, and a byte-by-byte canonical re-encode equality check before the rename |
| Winner selection on read | Both slots decoded; the highest `generation` integer that passes L2 verification AND the AndroidKeystore counter check (§9) wins; the loser remains as the rollback source |
| Cleanup | Stale `*.tmp` files in the slot directory are deleted on unlock (`AtomicSlotWriter.cleanupTempFiles`) |

### 3. Outer file envelope (L2)

The slot file is a single canonical UTF-8 JSON object built by
the hand-rolled `canonicalize` / `writeCanonical` serializer in
[`strongbox/StrongboxFileCodec.java`](app/src/main/java/com/quantumswap/app/strongbox/StrongboxFileCodec.java)
to guarantee the same sorted-key byte sequence iOS produces with
`JSONSerialization(opts:[.sortedKeys])`. There are no fixed
binary offsets at the slot layer.

```jsonc
{
  "v": 3,
  "generation": <Int>,                        // monotonic, +1 per write
  "kdf": {
    "algorithm": "scrypt",
    "salt": "<base64, 32 bytes>",             // generated at strongbox bootstrap
    "params": { "N": 262144, "r": 8, "p": 1, "keyLen": 32 }
  },
  "wrap": {
    "passwordWrap": {                         // AEAD-seal of mainKey under scrypt-derived KEK
      "alg": "AES-GCM",
      "iv":  "<base64, 12 bytes>",
      "ct":  "<base64, 32 bytes (mainKey ciphertext)>",
      "tag": "<base64, 16 bytes>"
    }
    // No `keystoreWrap` member is emitted on Android; the
    // AndroidKeystore binding lives in §9 (generation counter)
    // rather than as a second wrap of mainKey.
  },
  "strongbox": {                              // AEAD-seal of padded payload under mainKey
    "alg": "AES-GCM",
    "iv":  "<base64, 12 bytes>",
    "ct":  "<base64, 4 194 304 bytes>",      // exactly the padding bucket size
    "tag": "<base64, 16 bytes>"
  },
  "uiBlockHash": "<base64, 32 bytes>",        // SHA-256 of canonical(ui)
  "mac":         "<base64, 32 bytes>",        // HMAC-SHA-256 over MAC scope
  "ui": { /* opaque UI prefs, post-MAC, bound via uiBlockHash */ }
}
```

**MAC scope.** Let `M` = `canonicalize({v, generation, kdf,
wrap, strongbox, uiBlockHash})`. Then `mac = HMAC-SHA-256(macKey,
M)`. The `ui` key is **not** in `M`; its content is bound
indirectly via `uiBlockHash = SHA-256(canonical(ui))`.

### 4. Cryptographic primitives (L3)

| Primitive | Parameters | Source |
| --- | --- | --- |
| AEAD | AES-256-GCM via `javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")`. Nonce 12 bytes, random per seal (`SecureRandom.nextBytes`). Tag 16 bytes (128 bits). AAD: empty | [`keystorage/Aead.java`](app/src/main/java/com/quantumswap/app/keystorage/Aead.java) |
| KDF | scrypt with `N = 262144 (2^18)`, `r = 8`, `p = 1`, `dkLen = 32`. Salt 32 bytes generated by `SecureRandom.nextBytes` at strongbox bootstrap, persisted in `kdf.salt`, then reused for the lifetime of the strongbox. The scrypt implementation is the JS SDK's `scryptSync` invoked through the bridge | [`keystorage/UnlockCoordinator.java`](app/src/main/java/com/quantumswap/app/keystorage/UnlockCoordinator.java) `deriveKeyViaScrypt`, [`assets/bridge.html`](app/src/main/assets/bridge.html) `scryptDerive` |
| File MAC | HMAC-SHA-256 (32-byte tag) via JCE `javax.crypto.Mac` | [`keystorage/MacUtil.java`](app/src/main/java/com/quantumswap/app/keystorage/MacUtil.java) |
| MAC-key derivation | `macKey = HKDF-SHA-256(IKM = mainKey, salt = kdf.salt, info = "integrity-v2", L = 32)` via BouncyCastle `HKDFBytesGenerator` (RFC 5869 Extract+Expand). BouncyCastle is FIPS 140-2 certified and eliminates hand-rolled Extract+Expand loops. Byte-exact compatible with iOS CryptoKit `HKDF<SHA256>` | constant `MacUtil.INTEGRITY_INFO_LABEL` |

`mainKey` is a fresh 32-byte value produced once at strongbox
bootstrap by `new SecureRandom().nextBytes(new byte[32])`. It is
stored only inside the `passwordWrap` envelope. There is no
AndroidKeystore-resident copy.

### 5. Two-key hierarchy and key-lifetime contract

```
password ──scrypt(salt, N,r,p,32)──► derivedKey (32 B)  (KEK)
derivedKey ──AES-256-GCM-open(passwordWrap)──► mainKey (32 B)
mainKey ──HKDF(salt=kdf.salt, info="integrity-v2", L=32)──► macKey
mainKey ──HKDF(salt=nil, info="strongbox-payload-checksum-v3", L=32)──► checksumKey
mainKey ──AES-256-GCM-open(strongbox)──► paddedPlaintext (4 194 304 B)
unpad(7816-4) ──► canonicalJSON(StrongboxPayload)
```

**Lifetime invariants.**

- `derivedKey` is materialised inside every `unlock` and every
  `persist` call. It is wiped via
  `Arrays.fill(derivedKey, (byte) 0);` in a `finally` block in
  the same scope ([`UnlockCoordinator.java`](app/src/main/java/com/quantumswap/app/keystorage/UnlockCoordinator.java)).
- `mainKey` is materialised on each unlock and on each persist.
  It is wiped in the same `finally` block. It is **never**
  stored on a field after the function exits — the persist
  contract documents this explicitly so a future caller cannot
  introduce a long-lived in-process key.
- `passwordWrap` envelope is reused verbatim across writes — the
  user's password is not rotated by `persist`. (A password
  change goes through a separate dedicated path that re-seals
  `mainKey` under the new KEK and bumps `generation`.)
- `kdf.salt` is fixed for the lifetime of the strongbox.

### 6. Padding (L2)

| Item | Value |
| --- | --- |
| Bucket size | `StrongboxPadding.BUCKET_SIZE = 4_194_304` (4 MiB) |
| Scheme | ISO/IEC 7816-4: `plaintext ‖ 0x80 ‖ 0x00*` until the bucket is full |
| Pad precondition | `plaintext.length < BUCKET_SIZE` (one byte must remain for the `0x80` marker) |
| Unpad | Walk from the last byte skipping `0x00`; the next byte must equal `0x80` and is the boundary marker; the prefix is the original plaintext |

The 4 MiB bucket comfortably holds ≥256 wallets with
Dilithium-class private keys (≈7.5 KiB raw) and public keys
(≈2.5 KiB raw). Round-trip and bucket-fill invariants are
tested in
[`strongbox/StrongboxPaddingTest.java`](app/src/test/java/com/quantumswap/app/strongbox/StrongboxPaddingTest.java)
and the `WalletEntryCodecTest` 256-wallet packing test.

### 7. Plaintext payload (L5)

After AEAD-open and unpad, the inner buffer is a UTF-8 JSON
encoding of `StrongboxPayload` with the following normative
shape. The shape is unified across Android and iOS: same field
names, same ordering rules, same encoding of the per-wallet
binary blobs. Canonical sorted keys are used for the inner
checksum compute (`disableHtmlEscaping`, `serializeNulls` per
[`strongbox/StrongboxPayload.java`](app/src/main/java/com/quantumswap/app/strongbox/StrongboxPayload.java));
the AEAD seal does not require a canonical encoding because the
AEAD tag commits to the byte sequence.

```jsonc
{
  "v": 3,
  "wallets": {                                // String → String map
    "0": "<base64(WalletEntryCodec blob, see §8)>",
    "1": "<base64…>",
    …
  },
  "currentWalletIndex": <Int>,
  "customNetworks": [ /* user-added BlockchainNetwork rows */ ],
  "activeNetworkIndex": <Int>,
  "cloudBackupFolderUri": "<opaque platform-local string>",
  "advancedSigning": <Bool>,
  "cameraPermissionAskedOnce": <Bool>,
  "secureItems": { "<key>": "<opaque value>", … },
  "checksum": "<base64, 32 bytes (HMAC-SHA-256, see below)>"
}
```

**Backup-enabled toggle is OS-level, not part of the encrypted
payload.** The user-facing "Allow OS backup" preference lives in
`SharedPreferences` under `PrefConnect.BACKUP_ENABLED_KEY`, never
inside the strongbox. The OS backup agent (`WalletBackupAgent`)
runs **before** the wallet is unlocked, so the toggle must be
readable without the password. Storing it in
`SharedPreferences` is the canonical state — there is no mirror
in the encrypted payload to disagree with it.

**Inner checksum.** The Android inner checksum is keyed:

```
checksumKey = HKDF-SHA-256(IKM = mainKey, salt = nil,
                            info = "strongbox-payload-checksum-v3",
                            L = 32)
checksum    = base64( HMAC-SHA-256(checksumKey,
                                    canonicalBytesForChecksum()) )
```

The HMAC keying is intentional: an attacker who somehow recovers
`mainKey` already wins the AEAD layer, so keying the inner
checksum is defense-in-depth for partial-decrypt or
post-decrypt memory-corruption bugs and surfaces them as a
typed integrity failure rather than a silent corrupt snapshot.
Source: `StrongboxPayload.computeChecksum` (Android) /
`MacUtil.hkdfExtractAndExpand`. This matches iOS v=3 byte-for-byte.

### 8. Per-wallet wire (`WalletEntryCodec`, big-endian)

Each entry inside `wallets[<idxStr>]` is a base64-wrapped
length-prefixed binary blob. **All multi-byte integers are
big-endian.** This format is shared byte-for-byte with the iOS
wallet — see
[`strongbox/WalletEntryCodec.java`](app/src/main/java/com/quantumswap/app/strongbox/WalletEntryCodec.java)
and the round-trip + 256-wallet packing tests in
[`strongbox/WalletEntryCodecTest.java`](app/src/test/java/com/quantumswap/app/strongbox/WalletEntryCodecTest.java).

| Offset | Field | Width | Encoding |
| --- | --- | --- | --- |
| 0 | `WIRE_VERSION` | 1 byte (`u8`) | constant `0x01` |
| 1 | `flags` | 1 byte (`u8`) | bit 0 (`0x01`) = `FLAG_HAS_SEED`; remaining bits reserved (must be 0) |
| 2 | `addressLen` | 2 bytes (`u16` BE) | UTF-8 byte length of the address |
| 4 | `address` | `addressLen` bytes | UTF-8, EIP-55 mixed case, includes the leading `"0x"` |
| 4+addressLen | `privateKeyLen` | 4 bytes (`u32` BE) | byte length of the raw signing key |
| … | `privateKey` | `privateKeyLen` bytes | raw bytes (Dilithium-class, ≈7.5 KiB on Quantum Coin) |
| … | `publicKeyLen` | 4 bytes (`u32` BE) | byte length of the raw verifying key |
| … | `publicKey` | `publicKeyLen` bytes | raw bytes |
| … | `seedLen` | 4 bytes (`u32` BE) | UTF-8 byte length of the comma-joined seed phrase, or 0 |
| … | `seedWords` | `seedLen` bytes | UTF-8, comma-joined (`"abandon,ability,…"`); empty when `FLAG_HAS_SEED = 0` |

The whole blob is then `Base64.getEncoder().encodeToString(...)`-wrapped
so it can sit as a JSON string value inside `wallets`.

**Why a binary codec.** The earlier `wallets[idx] =
JSON({address, privateKey: hex, publicKey: hex, seedWords:
[...]})` shape exploded once Dilithium-class keys arrived (hex
doubles the byte count, JSON quoting plus base64 inside JSON
triples), and the strongbox bucket overflowed at modest wallet
counts. The binary codec shaves the per-entry overhead to one
base64 wrap and length-prefixes raw bytes directly.

### 9. Generation counter and rollback resistance

| Item | Value |
| --- | --- |
| In-slot field | `generation` integer in the outer JSON (incremented by exactly +1 per `writeNewGeneration`) |
| Out-of-band binding | HMAC-SHA-256 over the 8-byte big-endian counter under an AndroidKeystore-resident, non-extractable key (alias `qc-strongbox-generation-counter-hmac-v1`). The counter+tag pair is persisted as `"<counter>:<base64(tag)>"` in `SharedPreferences` file `qc_strongbox_counter_v1`, key `STRONGBOX_GENERATION_COUNTER` |
| Implementation | [`keystorage/AndroidKeystoreGenerationCounter.java`](app/src/main/java/com/quantumswap/app/keystorage/AndroidKeystoreGenerationCounter.java); StrongBox-backed when `setIsStrongBoxBacked(true)` succeeds (API ≥ 28), software-Keystore otherwise |
| On unlock | `UnlockCoordinator` rejects the slot if `slotGeneration < storedCounter` AFTER verifying the stored tag matches the stored counter under the Keystore key |
| Heal-forward | A first-launch device with no stored counter seeds it from the slot's `generation` so a fresh restore-from-Auto-Backup is accepted; subsequent rollback attempts are rejected. The pref file is excluded from Auto-Backup so a migrated device always re-seeds from the restored slot |

### 10. Brute-force lockout

State lives in a dedicated app-private `SharedPreferences` file
`qc_unlock_attempt_limiter_v2` with keys `count` and
`lastFailureMonotonicNanos`. The clock source is
`SystemClock.elapsedRealtimeNanos()` so the lockout window
counts time spent with the device asleep and is immune to wall-
clock writes from Settings. Reboot detection (stored monotonic
value larger than `now`) applies the maximum 5-minute tier on
the first post-reboot attempt to defeat the
fail-N-times-then-reboot bypass. Schedule: counts <5 → no
delay; 5 → 30 s; 6 → 60 s; 7 → 120 s; ≥8 capped at 300 s. The
limiter is consulted at strongbox-unlock and at backup-decrypt
on the same shared counter so the restore dialog cannot be used
to bypass the unlock-dialog rate limit. Source:
[`security/UnlockAttemptLimiter.java`](app/src/main/java/com/quantumswap/app/security/UnlockAttemptLimiter.java).

### 11. Out-of-strongbox metadata (`PrefConnect`)

The non-secret app preferences live in app-private
`SharedPreferences` file
`DP_QUANTUM_COIN_WALLET_APP_PREF.xml`, managed by
[`utils/PrefConnect.java`](app/src/main/java/com/quantumswap/app/utils/PrefConnect.java).
The allowlist:

| Key | Reason it is here, not in the strongbox |
| --- | --- |
| `WALLET_CURRENT_ADDRESS_INDEX_KEY` | Reads pre-unlock so the home screen can render the right wallet skeleton; mirrors the in-payload `currentWalletIndex` post-unlock |
| `BLOCKCHAIN_NETWORK_ID_INDEX_KEY` | Same rationale for the network strip |
| `BLOCKCHAIN_NETWORK_LIST` | Legacy fallback, migrated into the encrypted payload's `customNetworks` array post-unlock |
| `CLOUD_BACKUP_FOLDER_URI_KEY` | The persistable URI permission grant for the SAF folder picker; shared by the file backup save picker and the folder-restore picker so both open at the same remembered folder (read before any unlock) |
| `BACKUP_ENABLED_KEY` | Drives the runtime gate inside `WalletBackupAgent.onFullBackup` before any unlock |
| `ADVANCED_SIGNING_ENABLED_KEY` | Mirrors the payload field; used pre-unlock for the splash UI |
| `CAMERA_PERMISSION_ASKED_ONCE` | Idempotency flag for the Android permission dialog |
| `WALLET_HAS_SEED_KEY_PREFIX` (per-index) | Runtime mirror derived from `WalletEntryCodec` flags post-unlock for fast UI access without holding the encrypted snapshot in adapters |

Anything that names, locates, or enumerates wallets (the
`wallets` map, the `secureItems` map, the per-wallet seed flags
flags) lives only inside the encrypted payload.

In-memory caches (`PrefConnect.WALLET_ADDRESS_TO_INDEX_MAP`,
`WALLET_INDEX_TO_ADDRESS_MAP`, `WALLET_INDEX_HAS_SEED_MAP`) are
populated from the strongbox payload by
`SecureStorage.buildWalletMaps` after a successful unlock; they
are never persisted.

### 12. Auto-Backup and data-extraction policy

Both [`backup_rules.xml`](app/src/main/res/xml/backup_rules.xml)
and
[`data_extraction_rules.xml`](app/src/main/res/xml/data_extraction_rules.xml)
whitelist exactly the slot directory and the
`DP_QUANTUM_COIN_WALLET_APP_PREF.xml` prefs file via
path-specific `<include>` rules. Because the sharedpref include
names a single file, the
`qc_strongbox_counter_v1.xml` pref file is **excluded by
omission** — no explicit `<exclude>` is used (lint's
`FullBackupContent` check rejects `<exclude>` paths that are
not under any `<include>`, and an explicit exclude here would
be a redundant no-op). The counter pref must not travel because
its HMAC tag is signed by a non-extractable AndroidKeystore key
that does not survive a device migration; on the new device the
counter is heal-forwarded from the restored slot's `generation`
field.

The runtime gate in
[`backup/WalletBackupAgent.java`](app/src/main/java/com/quantumswap/app/backup/WalletBackupAgent.java)
inspects `BACKUP_ENABLED_KEY` before delegating to
`super.onFullBackup`, so even with `android:allowBackup="true"`
in the manifest nothing leaves the device unless the user
explicitly opted in from the in-app Settings UI.

### 13. Cloud `.wallet` per-wallet envelope (separate from the slot)

The user-facing **per-wallet backup file** (`UTC--<timestamp>--<addr>.wallet`)
is a different format. It is produced and consumed by the
shared `QuantumSwapSDK.Wallet.encryptSync` /
`Wallet.decryptSync` calls in `quantumswap-bundle.js`, called
from
[`bridge/QuantumSwapJSBridge.java`](app/src/main/java/com/quantumswap/app/bridge/QuantumSwapJSBridge.java).
It is a Web3-Secret-Storage-style JSON blob (scrypt KDF, AES +
MAC, address-bound) keyed by a user-supplied **backup
password** that is collected separately from the strongbox
password and may legitimately differ. The `.wallet` envelope is
byte-equivalent across iOS and Android (both call the same JS
SDK), which is why the per-wallet backup file format **is**
cross-platform restorable. See
[`backup/CloudBackupManager.java`](app/src/main/java/com/quantumswap/app/backup/CloudBackupManager.java)
for the file naming and folder enumeration; the JSON shape
itself is owned by the SDK package.

### 14. Cross-platform parity test vectors

The binding portability contract is the seeded vector suite:

- Android: `app/src/test/java/com/quantumswap/app/strongbox/StrongboxPortabilityVectorTest.java`
- iOS: `QuantumSwapWalletTests/StrongboxLayerTests.swift`
- Shared seed note: `tests/fixtures/strongbox-v3-vectors/INDEX.md`

The tests derive inputs at runtime with
`SHAKE256(seed || UTF8(label), outputLength)`, then assert the
same small expected outputs on both platforms. The suite covers
SHAKE-256 seed expansion, public RFC HMAC/HKDF vectors, SHA-256,
HMAC-SHA-256, HKDF null-salt behavior, fast scrypt, AES-256-GCM
with injected nonce, `WalletEntryCodec`, canonical
`StrongboxPayload` bytes, the keyed inner checksum, and 4 MiB
ISO/IEC 7816-4 padding. Large payload JSON and full slot blobs
are generated from the seed inside the tests rather than checked
into the repository.

---

## Cross-platform interoperability

Whole strongbox slot files are portable in v=3. Copying
`getFilesDir()/strongbox/DP_QUANTUM_COIN_WALLET_APP_PREF.{A|B}.json`
between this Android app and the iOS app's `Application Support/`
directory preserves the encrypted bytes; the receiver unlocks the
slot with the same wallet password and rebuilds its platform-local
state around it.

Per-wallet `.wallet` backup files (the cloud / file backup format
the user sees in the Backup screen) remain portable too, because
both apps call the same `QuantumSwapSDK.Wallet.encryptSync` /
`decryptSync` inside `quantumswap-bundle.js` under the same backup
password.

### How portability is enforced

The v=3 slot contract makes every byte-affecting choice match
across Android and iOS:

| Envelope element | Android value | iOS value | Match |
| --- | --- | --- | --- |
| Schema version | `v = 3` | `v = 3` | ✓ |
| Canonical JSON | sorted keys, UTF-8, no whitespace, no slash escaping | same | ✓ |
| AEAD | AES-256-GCM, 12-byte random nonce, 16-byte tag, no AAD | same | ✓ |
| AEAD `alg` string | `"AES-GCM"` | `"AES-GCM"` | ✓ |
| KDF | scrypt `N=262144, r=8, p=1, dkLen=32`, 32-byte salt | same | ✓ |
| KDF parameter floor | min-bound enforced at the codec layer (`StrongboxFileCodec.validateScryptParams`); a slot whose `kdf.params` undercuts the documented defaults is rejected at decode time | same (`StrongboxFileCodec.decodeOnly`) | ✓ |
| File MAC | HMAC-SHA-256, key = `HKDF-SHA-256(mainKey, salt=kdf.salt, info="integrity-v2", L=32)` | same | ✓ |
| MAC scope | `canonicalJSON({v, generation, kdf, wrap, strongbox, uiBlockHash})` | same | ✓ |
| `ui` binding | `uiBlockHash = SHA-256(canonical(ui))` | same | ✓ |
| Padding | ISO/IEC 7816-4 (`0x80 || 0x00*`) into a fixed 4 MiB bucket | same | ✓ |
| Inner checksum | `HMAC-SHA-256(HKDF(mainKey, salt=null, info="strongbox-payload-checksum-v3", L=32), canonical(payload-sans-checksum))` | same | ✓ |
| Payload schema | `v`, `wallets`, `currentWalletIndex`, `customNetworks`, `activeNetworkIndex`, `cloudBackupFolderUri`, `advancedSigning`, `cameraPermissionAskedOnce`, `secureItems`, `checksum` | same | ✓ |
| Per-wallet wire | `WalletEntryCodec` big-endian length-prefixed binary blobs (§8) | same | ✓ |
| Two-slot rotation | `…A.json` / `…B.json` + `.tmp` staging + verify-before-rename | same filename pattern, different parent directory | ✓ (modulo path) |

The portability contract is enforced by the seeded
`StrongboxPortabilityVectorTest` on Android and the matching
`StrongboxLayerTests` on iOS. Both expand the same 32-byte seed
with `SHAKE256(seed || UTF8(label), outputLength)` and assert the
same pinned outputs for SHAKE-256, HMAC-SHA-256, HKDF-SHA-256
(including null salt), SHA-256, fast scrypt, AES-256-GCM,
`WalletEntryCodec`, canonical `StrongboxPayload` bytes, the
keyed inner checksum, and 4 MiB ISO/IEC 7816-4 padding.

### Platform-local fields

Some bookkeeping is intentionally **not** portable because it
binds to a specific device or store:

- **`cloudBackupFolderUri`** is carried as an opaque platform-local
  string. Android stores a Storage Access Framework URI; iOS
  stores a security-scoped bookmark string. A cross-platform
  importer should clear or re-prompt for this value after reading
  the portable slot.
- **Generation counter** is device-bound on both platforms
  (Android: HMAC tag under an AndroidKeystore-resident
  non-extractable key, persisted in `qc_strongbox_counter_v1.xml`;
  iOS: Keychain generic-password item, `WhenUnlockedThisDeviceOnly`,
  non-synchronizable). Both platforms heal-forward when the
  counter is missing — a freshly-restored device adopts the
  slot's `generation` as its first authoritative value, and
  subsequent attempts to roll back to a lower value are rejected.
- **Brute-force lockout state** lives in a per-device store
  (Android: `qc_unlock_attempt_limiter_v2` SharedPreferences;
  iOS: Keychain). It does not migrate; the receiving device
  starts at zero, matching the security posture of a fresh
  install.
- **Out-of-strongbox UI preferences** (`PrefConnect`-managed)
  have overlapping but not identical key sets. None are required
  to recover wallet key material — but moving them increases the
  chance the user sees the home screen pointing at the right
  wallet on the first post-restore launch. The
  `BACKUP_ENABLED_KEY` toggle in particular is read pre-unlock by
  `WalletBackupAgent` and is the canonical store for the OS
  backup decision; there is no mirror inside the encrypted
  payload to disagree with it.

### v=2 historical note

Before v=3, raw slot-file copying did not work because the
plaintext payload schemas and inner-checksum schemes diverged
between Android and iOS, and the two apps used different
field-name sets inside the AEAD ciphertext. v=3 is a clean
cutover: the unified payload, the keyed
`strongbox-payload-checksum-v3` HMAC, the dropped
`maxWalletIndex` / `hasSeed` / `networks` legacy fields, and the
unified canonical-JSON rules together close the divergence and
the seeded vector suite pins it byte-for-byte.

---

## SDKs and dependencies

The Android wallet's runtime cryptography ships entirely in **a
single bundled JavaScript file** (`quantumswap-bundle.js`,
≈12.3 MiB, MIT-licensed) loaded into a `WebView`. The Java
dependencies are limited to standard AndroidX, Material
Components, OkHttp/Gson for the read-only scan API, ML Kit +
CameraX for QR scanning, and ZXing for QR generation.

The single JS file exposes **two** browser globals the bridge
consumes:

| Global | Purpose | Used in Android |
| --- | --- | --- |
| `QuantumSwapSDK` | Wallet construction, address helpers, JSON-RPC provider, IERC20 contract helper, scrypt KDF, AEAD wallet envelopes | [`app/src/main/assets/bridge.html`](app/src/main/assets/bridge.html) (~36 callsites) |
| `SeedWordsSDK` | BIP39-style seed-word lookup tables | [`app/src/main/assets/bridge.html`](app/src/main/assets/bridge.html) (4 callsites — `getWordListFromSeedArray`, `getAllSeedWords`, `doesSeedWordExist`) |

Both globals are produced upstream from two distinct SDK packages:

| Upstream SDK | Repository | Role in the bundle |
| --- | --- | --- |
| `quantumcoin.js` | <https://github.com/quantumcoinproject/quantumcoin.js> | The ethers.js-compatible wrapper that exposes the high-level `Wallet` / `JsonRpcProvider` / `IERC20` surface this wallet calls (`wallet.sendTransaction`, `token.transfer`, `wallet.getSigningContext`, `wallet.populateTransaction`). |
| `quantum-coin-js-sdk` | <https://github.com/quantumcoinproject/quantum-coin-js-sdk> | The lower-level Quantum Coin JS SDK (npm: `quantum-coin-js-sdk`) that `quantumcoin.js` builds on. Provides the chain-specific primitives (post-quantum signing, encrypted-wallet JSON envelope, scrypt KDF). |

The Android wallet only ever consumes the **curated
`quantumswap-bundle.js`** — **no Java code reaches into either
upstream package directly**. Adding a new SDK symbol means
re-exporting it from the bundle, not pulling an upstream package
into Java, so the SHA-256 pin and the Android-iOS parity contract
stay meaningful.

The bundle is built locally from
[`webview-sdk-bundle/`](webview-sdk-bundle/) via webpack
(`npm install && npx webpack`), and the resulting
`app/src/main/assets/quantumswap-bundle.js` is byte-identical to
the one shipped by the iOS wallet at
`QuantumSwapWallet/Resources/quantumswap-bundle.js`.

### Runtime libraries used

| Library | Used for |
| --- | --- |
| `androidx.appcompat` / `material` / `constraintlayout` / `recyclerview` / `cardview` | Every screen, every dialog |
| `androidx.webkit` + `WebView` | The single in-process `WebView` that hosts `bridge.html` ([`bridge/WebViewManager.java`](app/src/main/java/com/quantumswap/app/bridge/WebViewManager.java)) |
| Java `javax.crypto` (AES-GCM) + `java.security` (SHA-256, HMAC) | AES-256-GCM seal/open, hashing, file MAC ([`keystorage/Aead.java`](app/src/main/java/com/quantumswap/app/keystorage/Aead.java)) |
| BouncyCastle (`org.bouncycastle:bcprov-jdk18on`) | FIPS 140-2 certified HKDF-SHA-256 implementation (RFC 5869 Extract+Expand) for MAC-key derivation. Eliminates hand-rolled cryptography. Byte-exact compatible with iOS CryptoKit ([`keystorage/MacUtil.java`](app/src/main/java/com/quantumswap/app/keystorage/MacUtil.java)) |
| AndroidKeystore | Generation-counter HMAC key, brute-force-lockout key |
| `com.squareup.okhttp3` + `com.google.code.gson` + `io.gsonfire` + `io.swagger:swagger-annotations` | Read-only scan-API client (no signing primitives in Java) |
| `androidx.camera:camera-*` + `com.google.mlkit:barcode-scanning` | Camera-based QR scanning |
| `com.google.zxing:core` | QR generation (Receive screen) |
| `androidx.lifecycle:lifecycle-viewmodel` | Per-screen ViewModels |
| `androidx.localbroadcastmanager` | Process-local network-change pub/sub (no cross-app signal) |
| `androidx.documentfile` | Storage-Access-Framework backup folder enumeration |
| `com.jakewharton.timber` | Logger backend (wrapped behind the in-house [`Logger`](app/src/main/java/com/quantumswap/app/Logger.java) facade) |

### Build tooling

- **Gradle** 8.13 + **Android Gradle Plugin** 8.13.0
  ([`build.gradle`](build.gradle), [`app/build.gradle`](app/build.gradle)).
- **Strict dependency verification** — every Maven artifact is
  pinned by SHA-256 in
  [`gradle/verification-metadata.xml`](gradle/verification-metadata.xml).
  Adding a new dependency requires running
  `./gradlew --write-verification-metadata sha256 …` and
  reviewing the diff. Documentation-only artifacts
  (`*-javadoc.jar`, `*-sources.jar`) are accepted via a
  `<trusted-artifacts>` regex with a `reason` annotation, since
  they cannot influence the runtime classpath.
- **`embedBundleHash` Gradle task** — recomputes the SHA-256 of
  `app/src/main/assets/quantumswap-bundle.js` at build time and
  regenerates `GeneratedBundleHash.java` so the constant inside
  `classes.dex` stays in lockstep with the shipping bundle bytes.
  Wired as a `preBuild` dependency.
- **`syncIosImpersonatorFilter` Gradle task** — pulls a snapshot of
  the iOS `StablecoinImpersonatorFilter.swift` into
  `app/src/test/resources/code-snapshots/` before unit tests run so
  the Android-side parity test can byte-compare the two pattern
  lists in CI. Skip-on-missing for fresh checkouts.

---

## Architecture overview

```
┌───────────────────────────────────────────────────────────────────┐
│                  AppCompatActivity + Fragments                    │
│  HomeActivity ⇒ HomeStart / HomeMain / HomeWallet / Send /        │
│  Receive / Wallets / Settings / AccountTransactions /             │
│  RevealWallet / BlockchainNetwork(Add) / BackupOptions            │
└───────────────────────────┬───────────────────────────────────────┘
                            │
┌───────────────────────────▼───────────────────────────────────────┐
│                     Strongbox accessor (L5)                       │
│       keystorage/SecureStorage.java — single in-mem snapshot      │
└───────────────────────────┬───────────────────────────────────────┘
                            │
┌───────────────────────────▼───────────────────────────────────────┐
│                  Unlock coordinator (L4)                          │
│   scrypt → AEAD open → install snapshot;  password is never       │
│   cached — re-derived per write                                   │
└──────────────┬────────────────────────────────────┬───────────────┘
               │                                    │
┌──────────────▼─────────────────┐  ┌───────────────▼───────────────┐
│   Crypto primitives (L3)       │  │   Schema codec (L2)           │
│   keystorage/Aead, MacUtil,    │  │   strongbox/StrongboxFileCodec│
│   bridge.scryptDerive (JS)     │  │   StrongboxPadding            │
└──────────────┬─────────────────┘  └───────────────┬───────────────┘
               │                                    │
               │            ┌───────────────────────▼───────────────┐
               │            │   Storage primitive (L1)              │
               │            │   storage/AtomicSlotWriter            │
               │            │   (two-slot, FileChannel.force(true), │
               │            │    verify-before-promote)             │
               │            └───────────────────────────────────────┘
               │
┌──────────────▼─────────────────┐  ┌───────────────────────────────┐
│      JsBridge (Java)           │◄─┤    bridge.html (WebView)      │
│  bridge/QuantumSwapJSBridge,   │  │    quantumswap-bundle.js      │
│  bridge/WebViewManager,        │  │    (QuantumSwapSDK +          │
│  security/BundleIntegrity      │  │     SeedWordsSDK globals)     │
└────────────────────────────────┘  └───────────────────────────────┘
```

The strict layering is enforced by the storage / crypto / bridge
separation in code review and by invariant tests in
[`app/src/test/java/com/quantumswap/app/strongbox/StrongboxPaddingTest.java`](app/src/test/java/com/quantumswap/app/strongbox/StrongboxPaddingTest.java)
and the broader test suite. The only structurally-permitted
writers of wallet-meaningful state are the
`SecureStorage` accessor and the `UnlockCoordinator` re-encrypt
path; a stray `PrefConnect` / `SharedPreferences` write of a
wallet field is caught at code review time, with the byte-level
contract documented in
[`STORAGE_LAYERED_MODEL.md`](STORAGE_LAYERED_MODEL.md).

---

## Repository layout

```
.
├── LICENSE                              MIT
├── STORAGE_LAYERED_MODEL.md             Byte-level layered storage spec
├── build.gradle                         Top-level Gradle config
├── settings.gradle                      Module + dep-resolution rules
├── gradle.properties                    JVM opts, AGP flags
├── gradle/
│   └── verification-metadata.xml        Strict SHA-256 dep verification
├── webview-sdk-bundle/                  Webpack source for the JS bundle
│   ├── package.json
│   ├── webpack.config.js
│   └── src/                             Re-export glue around the upstream SDKs
└── app/
    ├── build.gradle                     App module + embedBundleHash + syncIosImpersonatorFilter tasks
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── assets/
        │   │   ├── bridge.html                       JS bridge (the only HTML the WebView loads)
        │   │   ├── quantumswap-bundle.js             The single JS SDK bundle (SHA-256 pinned)
        │   │   └── quantumswap-bundle.js.LICENSE.txt
        │   ├── res/
        │   │   ├── drawable/, drawable-v24/, font/, layout/, menu/
        │   │   ├── mipmap-*/                         Launcher icons
        │   │   ├── raw/
        │   │   │   ├── blockchain_networks.json      Bundled MAINNET network seed
        │   │   │   └── en_us.json                    230+ localization keys
        │   │   ├── values/, values-night/            Themes, colors, strings, dark mode
        │   │   └── xml/                              backup_rules, data_extraction_rules,
        │   │                                         network_security_config
        │   └── java/com/quantumswap/app/
        │       ├── App.java                          Application boot + tamper gate plant
        │       ├── Logger.java                       Logger facade (release-safe)
        │       ├── RedactingDebugTree.java           Timber tree with redaction regex set
        │       ├── api/                              Read-only scan-API client (Gson)
        │       ├── asynctask/                        Background workers
        │       ├── backup/                           CloudBackupManager + WalletBackupAgent
        │       ├── bridge/                           WebViewManager, QuantumSwapJSBridge, BridgeCallback
        │       ├── entity/                           POJO entities
        │       ├── events/                           NetworkChangeBroadcaster (LocalBroadcastManager)
        │       ├── interact/                         JsonInteract (localization accessors)
        │       ├── keystorage/                       Aead, MacUtil, SecureStorage, UnlockCoordinator
        │       ├── model/                            Domain models
        │       ├── networking/                       UrlBuilder, NetworkSnapshot, ApiClient
        │       ├── security/                         TamperGate, BundleIntegrity, TlsPinning,
        │       │                                     ScreenCaptureGuard, UnlockAttemptLimiter,
        │       │                                     generated/GeneratedBundleHash.java (auto)
        │       ├── services/                         Background services
        │       ├── storage/                          AtomicSlotWriter (L1)
        │       ├── strongbox/                        StrongboxFileCodec, StrongboxPayload,
        │       │                                     StrongboxPadding, StrongboxRedundancyState
        │       ├── tokens/                           RecognizedTokens, StablecoinImpersonatorFilter
        │       ├── utils/                            Constants, CoinUtils, SecureClipboard, PrefConnect
        │       ├── ux/                               SnapshotRedactor
        │       ├── view/
        │       │   ├── activities/HomeActivity.java
        │       │   ├── adapter/                      RecyclerView adapters
        │       │   ├── dialog/                       Dialog fragments
        │       │   ├── fragment/                     13 top-level Fragments
        │       │   └── widget/                       Custom views
        │       └── viewmodel/                        Per-screen ViewModels
        └── test/java/com/quantumswap/app/      29 test classes, 183 tests
```

Counts (at the time of writing): 113 Java source files in
`app/src/main`, 29 test classes (183 unit tests), 833-line
`bridge.html`, 12 MiB `quantumswap-bundle.js`, 230+ localization
keys.

---

## Build and run

### Prerequisites

- **JDK 17** (Android Gradle Plugin 8.13 requires it). The
  source / target language level for the app itself is Java 8
  with desugaring, so the produced bytecode runs on
  `minSdk = 29` (Android 10). The `minSdk` floor is set by the
  strict TLS 1.3-only enforcement in `TlsPinning` — Android 10
  was the first release to ship native TLS 1.3 in the platform
  `SSLEngine`, and the wallet refuses to fall back to TLS 1.2.
- **Android Studio Hedgehog (2023.1.1) or newer** — or just the
  Android command-line tools if you prefer the CLI.
- **Android SDK Platform 35** (`compileSdk = 35`,
  `targetSdk = 35`). Install via `sdkmanager` or via Android
  Studio's SDK Manager.

The project includes the Gradle wrapper (`gradlew`); you do not
need a system-wide Gradle install.

### Generate, build, run

```bash
git clone https://github.com/quantumcoinproject/quantumswap-wallet-android.git
cd quantum-coin-wallet-android

# Optional but recommended: rebuild the JS bundle from source so
# its SHA-256 matches what you see in webview-sdk-bundle/.
( cd webview-sdk-bundle && npm install && npx webpack )

# Debug build (assemble the APK; embedBundleHash runs as preBuild
# and regenerates GeneratedBundleHash.java automatically).
./gradlew :app:assembleDebug

# Install on a connected device or emulator.
./gradlew :app:installDebug
```

The first build runs the `embedBundleHash` Gradle task, which
writes
[`app/src/main/java/com/quantumswap/app/security/generated/GeneratedBundleHash.java`](app/src/main/java/com/quantumswap/app/security/generated/GeneratedBundleHash.java)
so the SHA-256 of `quantumswap-bundle.js` is embedded in
`classes.dex`. **The generated file IS committed** so the constant
can be diffed alongside the bundle bytes in the same PR; the build
fails loudly if the file is stale relative to the shipping bundle.

### Release build

Release signing is sourced **exclusively from environment
variables** so the keystore + credentials never live in the repo
(see [`app/build.gradle`](app/build.gradle)):

```bash
export ANDROID_KEYSTORE_FILE=/path/to/release.keystore
export ANDROID_KEYSTORE_PASSWORD=…
export ANDROID_KEY_ALIAS=…
export ANDROID_KEY_PASSWORD=…
./gradlew :app:assembleRelease
```

Release builds:

- Disable debugging (`debuggable false`).
- Run R8 (`minifyEnabled true`,
  [`proguard-rules.pro`](app/proguard-rules.pro)).
- Shrink resources (`shrinkResources true`).
- Sign with v1+v2+v3 schemes for broad compatibility and modern
  APK verification.

The output APK is named
`QuantumSwap-wallet-<versionName>-<buildType>.apk` so debug and
release variants don't collide on CI and the artifact is
self-identifying when downloaded off a release.

### Updating the JS bundle

`quantumswap-bundle.js` is built from the
[`webview-sdk-bundle/`](webview-sdk-bundle/) module via webpack
with the upstream `quantumcoin.js` and `quantum-coin-js-sdk`
packages. After rebuilding:

```bash
cd webview-sdk-bundle
npm install
npx webpack
# webpack writes ../app/src/main/assets/quantumswap-bundle.js
cd ..
./gradlew :app:assembleDebug
```

The next build regenerates `GeneratedBundleHash.java`
automatically. Verify the new hex matches the iOS sibling repo's
[`QuantumSwapWallet/Generated/BundleHash_Generated.swift`](https://github.com/quantumcoinproject/quantumswap-wallet-ios/blob/main/QuantumSwapWallet/Generated/BundleHash_Generated.swift)
to confirm cross-platform bundle parity in the same commit.

---

## Testing

```bash
./gradlew :app:testDebugUnitTest
```

The test target lives in
[`app/src/test/java/com/quantumswap/app/`](app/src/test/java/com/quantumswap/app).
It contains 29 test classes and 183 unit tests:

| Suite | Coverage |
| --- | --- |
| `backup/BackupExecutorExportPathTest` | Pins the file export → verify-by-readback → user-feedback flow for SAF writes, and the SAF authority detection helper |
| `backup/CloudBackupManagerScanTest` | Bounded SAF folder-restore scan — per-file size cap, total-folder candidate cap, lazy ciphertext loading; pins the scan outcome enum |
| `bridge/SendBridgeContractTest` | Pins the `sendTransactionAsync` / `sendTokenTransactionAsync` payload shape AND the `bridge.html` success-envelope shape byte-for-byte against the iOS contract |
| `bridge/SendSurfaceLockdownTest` | CI grep guard — fails if any forbidden low-level signing primitive (`signRawTransaction`, `signSendCoinTransaction(`, raw `provider.sendTransaction()`) appears outside the documented rationale block |
| `interact/JsonInteractParityTest` | Localization-key presence, accessor wiring, OS-specific divergence wording (root vs jailbreak, Play Store vs App Store, Android Auto Backup vs iCloud) |
| `keystorage/AddNetworkPersistsToStrongboxTest` | Pins that adding a custom network goes through the strongbox `customNetworks` field (not `SharedPreferences`) and survives a relock/unlock round-trip |
| `keystorage/MacUtilTest` | HMAC + HKDF primitives used by the generation-counter, brute-force-lockout binders, and the v=3 file MAC / inner checksum |
| `networking/UrlBuilderHostInvariantTest` | Post-substitution URL host MUST equal the configured `BlockchainNetwork.blockExplorerDomain` (anti-host-pivot) |
| `networking/UrlBuilderLockdownTest` | CI grep guard — fails if a naive `replace("{address}"…)` or `replace("{txhash}"…)` call site reappears anywhere |
| `networking/UrlBuilderTest` | Strict regex acceptance + percent-encoding behavior |
| `security/UnlockAttemptLimiterTest` | Stair-step backoff schedule, 5-minute cap, reboot-bypass defense |
| `security/BackupRestoreLimiterRecordingTest` | Pins the `UnlockAttemptLimiter` wiring in `HomeWalletFragment` so a future refactor cannot silently drop a brute-force-limiter call across the backup-restore failure paths |
| `security/CredentialIdentifierUsernameParityTest` | Pins the per-platform Credential Manager username string used during unlock so the autofill provider sees the same identifier on Android and iOS |
| `security/WalletCreationPasswordWhitespaceTest` | Pins the `passwordSpace` localization key + Android↔iOS wording parity + the `JsonInteract` / `JsonViewModel` accessor surface used by the wallet-creation whitespace-rejection guard |
| `security/TlsPinningConnectionSpecTest` | Pins the strict TLS 1.3-only `ConnectionSpec` (version-only TLS 1.3, three TLS 1.3 AEAD suites present, TLS extensions enabled) and that `applyTo(...)` installs exactly that spec — no implicit "compatible" fallback |
| `strongbox/DeterministicSecureRandomSourceTest` | Pins the seeded `SecureRandomSource` test seam used by the cross-platform vector suite so the same SHAKE-256 expansion produces the same bytes on Android and iOS |
| `strongbox/StrongboxFileCodecScryptValidationTest` | scrypt KDF parameter min-bound validation at the codec layer; rejects any v=3 slot whose `kdf.params` undercut the documented defaults (`N≥262144, r≥8, p≥1, keyLen≥32`) |
| `strongbox/StrongboxPaddingTest` | ISO/IEC 7816-4 padding round-trip + 4 MiB bucket invariant |
| `strongbox/StrongboxPayloadV3Test` | v=3 `StrongboxPayload` field-set lockdown — `wallets`, `currentWalletIndex`, `customNetworks`, `activeNetworkIndex`, `cloudBackupFolderUri`, `advancedSigning`, `cameraPermissionAskedOnce`, `secureItems`, `checksum`; explicitly asserts `backupEnabled` is NOT in the canonical JSON |
| `strongbox/StrongboxPortabilityVectorTest` | Cross-platform parity vectors driven by a hardcoded SHAKE-256 seed; covers SHAKE-256, HMAC/HKDF/SHA-256, fast scrypt, AES-256-GCM with injected nonce, `WalletEntryCodec`, canonical payload bytes, the keyed inner checksum, and 4 MiB padding (mirrored on iOS in `StrongboxLayerTests`) |
| `strongbox/WalletEntryCodecTest` | Big-endian length-prefixed wire format for per-wallet entries; round-trip + 256-wallet packing + exact-byte vector matching the iOS `WalletEntryCodecTests.testWireFormatSpecExactBytes` |
| `utils/CoinUtilsCapsTest` | DoS caps on `formatUnits` / `parseUnits` / `parseEther` / `hexToDecimalString` (oversized decimals, oversized hex, overflow sentinel) |
| `utils/SecureClipboardSeedGateLockdownTest` | Pins the seed-clipboard hardening gate — `SecureClipboard.isSeedClipboardCopyHardened()` is a strict `SDK_INT >= TIRAMISU` (API 33) comparison, both `RevealWalletFragment` and `HomeWalletFragment` wrap their seed-copy `setOnClickListener` wiring in the gate, and no other file binds the seed-copy click listener identifiers (so the gate cannot be quietly bypassed by a future refactor) |
| `view/fragment/HomeWalletFragmentRestoreConfirmTest` | Confirm-Wallet acceptance suite (idempotency guard, balance format byte-match, tap-to-copy, network-name caption) |
| `LoggerFacadeLockdownTest` | CI grep guard — fails if any direct `Log.*` or `Timber.tag(...).*` call appears outside the allow-list |
| `RedactingDebugTreeTest` | Pins the address / tx-hash / long-hex / base64 redaction regex set |
| `api/read/ApiDecodingTest` | Scan-API JSON shapes against captured fixtures with custom Gson builders |
| `ExampleUnitTest` | Sanity check |

Tests run on the JVM (no Android device/emulator required) and
deliberately avoid Robolectric. Where Android SDK classes would
otherwise stub-out on the JVM (e.g. `android.net.Uri.encode`,
`org.json.JSONObject.put`), the production code routes through
pure-Java helpers and the tests use custom JSON parsers, so the
suite stays fast and reproducible.

### Instrumented tests

`./gradlew :app:assembleDebugAndroidTest` builds the
`androidTest` APK (no instrumented tests are shipped today; the
target exists so the `debugAndroidTestCompileClasspath`
configuration stays resolvable in IDE syncs).

---

## Threat model & non-goals

### In scope

- **Lost or stolen device.** App-private storage, no-Backup
  files dir, brute-force lockout (AndroidKeystore-bound),
  snapshot redaction, clipboard expiry, idle relock.
- **Hostile RPC.** Local-first transaction signing inside the
  bundled SDK; the user can verify the locally-derived hash on
  any block explorer.
- **Token impersonation.** Recognized-contract allow-list +
  stablecoin-name hard-suppressor.
- **JS bundle tamper / re-sign.** Build-time SHA-256 pin
  embedded in the code-signed `classes.dex`; runtime re-hash;
  refuse-to-init on mismatch.
- **Root / debugger / Frida-class instrumentation.**
  Multi-signal tamper gate at the signing chokepoint.
- **Power loss / sudden app kill mid-write.** Two-slot
  rotation, `FileChannel.force(true)`, parent-dir flush,
  verify-before-promote.
- **Slot-level rollback** (re-mounting an older slot file).
  AndroidKeystore-bound generation counter validated on every
  unlock.
- **Other apps reading wallet bytes via Auto-Backup.**
  Excluded by default in `backup_rules.xml`; opt-in toggle in
  Settings.

### Explicit non-goals

- **TLS pinning on RPC.** The wallet is non-custodial and the
  user picks the RPC. SPKI pinning RPC traffic would impose
  centralization that the project explicitly rejects. The
  strict TLS 1.3-only floor + baseline TLS chain validation
  + strict default hostname verification still apply on RPC
  traffic (so a downgrade-to-TLS-1.2 attacker is rejected even
  on user-chosen RPC endpoints). See
  [`security/TlsPinning.java`](app/src/main/java/com/quantumswap/app/security/TlsPinning.java)
  for the full coverage map.
- **Defending an unlocked, rooted, attacker-owned device with a
  Frida-class hook injected before the wallet process loads.**
  The tamper gate raises cost; it does not claim to be
  impassable.
- **Custodial recovery.** There is no remote escrow of seed
  phrases or unlock passwords. Lost seed = lost wallet. The
  backup flow (file backup / folder restore) is the only
  recovery path.
- **Remote-signed metadata channels.** The recognized-token
  list is intentionally hard-coded in the app binary rather
  than fetched from a server. The wallet targets a
  post-quantum chain and pinning the authenticity of any
  shipping list to a classical signature scheme would
  contradict the project's cryptographic posture; see the
  Javadoc on
  [`tokens/RecognizedTokens.java`](app/src/main/java/com/quantumswap/app/tokens/RecognizedTokens.java)
  for the rationale.
- **Investment, custody, or financial advice of any kind.**

---

## License

[MIT](LICENSE) — see the file for details.

The bundled `quantumswap-bundle.js` and its embedded third-party
libraries are MIT-licensed (see
[`app/src/main/assets/quantumswap-bundle.js.LICENSE.txt`](app/src/main/assets/quantumswap-bundle.js.LICENSE.txt)).

---

## Further reading

- **Project home:** <https://quantumcoin.org>
- **FAQ:** <https://quantumcoin.org/faq.html>
- **Quantum-resistance whitepaper:**
  <https://quantumcoin.org/whitepapers/Quantum-Coin-Blockchain-Quantum-Resistance-Whitepaper.html>
- **Consensus whitepaper:**
  <https://quantumcoin.org/whitepapers/Quantum-Coin-Blockchain-Consensus-Whitepaper.html>
- **Quantum Coin Go node (open source):**
  <https://github.com/quantumcoinproject/quantum-coin-go>
- **iOS wallet (parity reference):**
  <https://github.com/quantumcoinproject/quantumswap-wallet-ios>
- **`quantumcoin.js` (ethers.js-compatible wrapper SDK):**
  <https://github.com/quantumcoinproject/quantumcoin.js>
- **`quantum-coin-js-sdk` (lower-level upstream SDK, npm package):**
  <https://github.com/quantumcoinproject/quantum-coin-js-sdk>
- **Block explorer:** <https://quantumscan.com>
- **JSON-RPC API docs:** <https://apidoc.quantumcoin.org>
- **Community:** <https://discord.gg/bbbMPyzJTM>
