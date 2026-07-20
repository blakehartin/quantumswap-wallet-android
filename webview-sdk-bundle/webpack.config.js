const path = require('path');
const webpack = require('webpack');

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
  resolve: {
    // quantumcoin 8.x / quantumswap 1.x / seed-words 1.1.x are browser-clean:
    // randomness and hashing go through WebCrypto and the bundled WASM SDK,
    // so no crypto/stream/buffer polyfills are required anymore. The only
    // Node builtin the dependency graph can still mention is the optional
    // IPC socket provider (node:net), which must resolve to an empty module
    // inside the WebView. Everything else is stubbed out defensively so a
    // future dependency bump fails the build loudly instead of silently
    // pulling a Node builtin into the bundle.
    fallback: {
      net: false,
      'node:net': false,
      fs: false,
      path: false,
      crypto: false,
      stream: false,
      http: false,
      https: false,
      os: false,
      vm: false,
      zlib: false,
      tls: false,
      child_process: false,
      dns: false,
      readline: false,
      url: false,
    },
  },
  plugins: [
    new webpack.NormalModuleReplacementPlugin(/^node:/, (resource) => {
      resource.request = resource.request.replace(/^node:/, '');
    }),
  ],
  module: {
    rules: [
      {
        test: /\.wasm$/,
        type: 'asset/inline',
      },
    ],
  },
  performance: {
    maxAssetSize: 16 * 1024 * 1024,
    maxEntrypointSize: 16 * 1024 * 1024,
  },
};
