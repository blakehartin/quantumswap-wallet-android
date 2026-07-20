// Node harness: loads the shipping quantumswap-bundle.js plus the inline
// bridge.html script blocks and exercises the pure DEX helpers plus a
// staged-payload bridge call end-to-end (no network). Run manually with:
//   node bridge-smoke-test.js [path-to-bridge.html]
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const bridgeHtmlPath = process.argv[2] ||
    path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'bridge.html');
const bundlePath = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'quantumswap-bundle.js');

const results = [];
const pendingPayloads = new Map();

const sandbox = {
    console, TextEncoder, TextDecoder, URL, URLSearchParams,
    setTimeout, clearTimeout, setInterval, clearInterval,
    atob: (b64) => Buffer.from(b64, 'base64').toString('binary'),
    btoa: (bin) => Buffer.from(bin, 'binary').toString('base64'),
    fetch: globalThis.fetch,
    crypto: globalThis.crypto,
    performance: globalThis.performance,
    // Web-streams / fetch-adjacent globals the SDK + seed-words use
    // (available in WebView / WKWebView; mapped from Node here).
    DecompressionStream: globalThis.DecompressionStream,
    CompressionStream: globalThis.CompressionStream,
    ReadableStream: globalThis.ReadableStream,
    WritableStream: globalThis.WritableStream,
    TransformStream: globalThis.TransformStream,
    Response: globalThis.Response,
    Request: globalThis.Request,
    Headers: globalThis.Headers,
    Blob: globalThis.Blob,
    AbortController: globalThis.AbortController,
    queueMicrotask: globalThis.queueMicrotask,
    structuredClone: globalThis.structuredClone,
    AndroidBridge: {
        isDebug: () => false,
        onResult: (requestId, json) => results.push({ requestId, ...JSON.parse(json) }),
        getPendingPayload: (requestId) => {
            const v = pendingPayloads.get(requestId) || null;
            pendingPayloads.delete(requestId);
            return v;
        },
    },
};
sandbox.window = sandbox;
sandbox.globalThis = sandbox;
vm.createContext(sandbox);

new vm.Script(fs.readFileSync(bundlePath, 'utf8'), { filename: 'quantumswap-bundle.js' }).runInContext(sandbox);
const html = fs.readFileSync(bridgeHtmlPath, 'utf8');
const re = /<script>([\s\S]*?)<\/script>/g;
let m;
while ((m = re.exec(html))) {
    new vm.Script(m[1], { filename: 'bridge-inline' }).runInContext(sandbox);
}

let failures = 0;
function check(name, cond, detail) {
    if (cond) { console.log('PASS', name); }
    else { failures++; console.error('FAIL', name, detail || ''); }
}

(async () => {
    check('QuantumSwapSDK defined', typeof sandbox.QuantumSwapSDK !== 'undefined');
    check('bridge object defined', typeof sandbox.bridge === 'object');
    for (const fn of ['swapGetTokenMetadata', 'swapCheckPairExists', 'swapGetAmountsOut',
        'swapGetAmountsIn', 'swapEstimateGas', 'swapCheckAllowance', 'swapEstimateApproveGas',
        'swapSubmitApproval', 'swapSubmitSwap', 'liquidityListPools', 'liquidityListPositions',
        'liquidityGetPairInfo', 'liquidityCheckAllowance', 'liquiditySubmitApprove',
        'liquiditySubmitAdd', 'liquiditySubmitRemove', 'poolsSubmitCreatePair']) {
        check('bridge.' + fn, typeof sandbox.bridge[fn] === 'function');
    }

    // BFS route search: direct pair missing, path exists via hop 1.
    const nodes = ['A', 'H1', 'H2', 'B'];
    const edges = new Set(['A|H1', 'H1|B']);
    const pairExists = async (x, y) => edges.has(x + '|' + y) || edges.has(y + '|' + x);
    const p1 = await sandbox.dexSearchSwapPath(nodes, pairExists);
    check('BFS multi-hop route', JSON.stringify(p1) === JSON.stringify(['A', 'H1', 'B']), JSON.stringify(p1));
    const p2 = await sandbox.dexSearchSwapPath(['A', 'B'], async () => false);
    check('BFS no-route returns null', p2 === null);

    // Slippage math parity with desktop minWeiWithSlippage.
    check('slippage 1% of 10000', sandbox.dexMinWeiWithSlippage(10000n, 1) === 9900n);
    check('slippage 0% identity', sandbox.dexMinWeiWithSlippage(12345n, 0) === 12345n);

    // Release resolution: defaults + overrides.
    await sandbox.QuantumSwapSDK.Initialize(new sandbox.QuantumSwapSDK.Config());
    const rel = sandbox.dexResolveRelease({});
    check('builtin release wq', rel.wq === '0x45BD01BE5EF8509D9dA183689eA7Faf647331c54c7C9801dE54c9EDE9Ac44D92');
    // getAddress canonicalizes to lowercase; compare case-insensitively.
    const rel2 = sandbox.dexResolveRelease({ releaseRouter: rel.wq });
    check('release router override',
        rel2.router.toLowerCase() === rel.wq.toLowerCase() && rel2.factory === rel.factory);

    // Add-liquidity call building: token/token and native/token.
    const fakeProvider = { getBlock: async () => ({ timestamp: 1000000n }) };
    const T1 = '0xe8ea8beb86e714ef2bde0afac17d6e45d1c35e48f312d6dc12c4fdb90d9e8a3d';
    const T2 = '0xa8036870874fbed790ed4d3bbd41b2f390b9858ff021f2993e90c6d1cbb167c7';
    const OWNER = rel.factory;
    const callTT = await sandbox.dexBuildAddLiquidityCall({
        tokenAValue: T1, tokenBValue: T2, amountA: '1', amountB: '2',
        decimalsA: 18, decimalsB: 18, slippagePercent: 1, ownerAddress: OWNER,
    }, rel, fakeProvider);
    check('addLiquidity token/token method', callTT.method === 'addLiquidity');
    check('addLiquidity deadline from chain time', callTT.args[7] === 1000000n + 1200n, String(callTT.args[7]));
    const callNT = await sandbox.dexBuildAddLiquidityCall({
        tokenAValue: 'Q', tokenBValue: T2, amountA: '1', amountB: '2',
        decimalsA: 18, decimalsB: 18, slippagePercent: 1, ownerAddress: OWNER,
    }, rel, fakeProvider);
    check('addLiquidityETH for native side', callNT.method === 'addLiquidityETH');
    check('addLiquidityETH value is native amount', callNT.value === 10n ** 18n, String(callNT.value));

    // Remove-liquidity call building: WQ side pays out native.
    const callRM = await sandbox.dexBuildRemoveLiquidityCall({
        tokenAAddress: rel.wq, tokenBAddress: T2, liquidityWei: '1000',
        amountAMinWei: '10', amountBMinWei: '20', ownerAddress: OWNER,
    }, rel, fakeProvider);
    check('removeLiquidityETH for WQ side', callRM.method === 'removeLiquidityETH');
    check('removeLiquidityETH token arg', callRM.args[0] === sandbox.QuantumSwapSDK.getAddress(T2));

    // Staged-payload bridge call (offline path): swapCheckAllowance with a
    // missing owner address must produce a structured error envelope.
    pendingPayloads.set('r1', JSON.stringify({ chainId: 123123, rpcEndpoint: 'http://127.0.0.1:1' }));
    await sandbox.bridge.swapCheckAllowance('r1');
    const r1 = results.find((r) => r.requestId === 'r1');
    check('bridge error envelope', r1 && r1.success === false && /Owner address required/.test(r1.error), JSON.stringify(r1));

    console.log(failures === 0 ? 'ALL PASS' : failures + ' FAILURES');
    process.exit(failures === 0 ? 0 : 1);
})().catch((e) => { console.error('HARNESS ERROR', e); process.exit(1); });
