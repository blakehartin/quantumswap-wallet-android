const path = require('path');
const webpack = require('webpack');

// quantumcoin 8.x / quantumswap 1.x / seed-words 1.1.x are browser-clean:
// randomness and hashing go through WebCrypto and the SDK's self-contained
// WASM (shipped as base64 inside quantum-coin-js-sdk), so no Node-builtin
// polyfills, resolve fallbacks, or module replacements are configured —
// same pattern as the desktop wallet. The single stub below is the one
// exception: quantumcoin's optional IPC socket provider lazily requires
// `node:net` (guarded by try/catch at runtime), and webpack 5 treats the
// node: scheme as unresolvable in web targets (neither the SDK's own
// `browser: { "node:net": false }` field nor resolve.fallback intercepts
// a schemed request), so the plugin strips the prefix and the fallback
// maps the result to an empty module. Any future dependency that pulls
// in another Node builtin will fail this build loudly.
module.exports = {
  entry: './src/index.js',
  output: {
    filename: 'quantumswap-bundle.js',
    path: path.resolve(__dirname, '..', 'app', 'src', 'main', 'assets'),
    library: {
      name: 'QuantumSwapSDK',
      type: 'var',
    },
  },
  target: 'web',
  plugins: [
    new webpack.NormalModuleReplacementPlugin(/^node:net$/, (resource) => {
      resource.request = 'net';
    }),
  ],
  resolve: {
    fallback: {
      net: false,
    },
  },
  performance: {
    maxAssetSize: 16 * 1024 * 1024,
    maxEntrypointSize: 16 * 1024 * 1024,
  },
};
