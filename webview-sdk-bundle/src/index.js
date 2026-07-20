const quantumcoin = require('quantumcoin');
const quantumswap = require('quantumswap');
const seedWords = require('seed-words');

// The webpack library output assigns module.exports to window.QuantumSwapSDK.
// We intentionally keep seed-words OUT of that namespace and expose it as its
// own separate global so the module boundary is explicit.
if (typeof window !== 'undefined') {
  window.SeedWordsSDK = seedWords;
}

module.exports = {
  ...quantumcoin,
  ...quantumswap,
};
