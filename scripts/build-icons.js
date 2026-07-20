/**
 * Build the QuantumSwap mobile icon sets from the desktop vector mark.
 *
 * Modeled on quantumswap-wallet-desktop/scripts/build-icons.js, but the
 * mobile outputs are SQUARE and FULLY OPAQUE (per the rebrand decision):
 * the ring mark is flattened onto the #0b0614 brand background that the
 * desktop SVG itself declares.
 *
 * Outputs
 *   Android (app/src/main/res):
 *     mipmap-{m,h,x,xx,xxx}dpi/ic_launcher.png        48..192 opaque
 *     mipmap-{m,h,x,xx,xxx}dpi/ic_launcher_round.png  48..192 opaque
 *     mipmap-{m,h,x,xx,xxx}dpi/ic_launcher_foreground.png
 *                                108..432 transparent, mark in safe zone
 *     drawable-v24/logo.png                           400 opaque banner logo
 *   iOS (../quantumswap-wallet-ios/QuantumCoinWallet):
 *     Assets.xcassets/AppIcon.appiconset/icon-*.png   all slots, opaque
 *     Assets.xcassets/Logo.imageset/logo@3x.png       opaque banner logo
 *
 * Usage: node build-icons.js   (from this scripts/ directory)
 */

const fs = require("fs");
const path = require("path");
const sharp = require("sharp");

const ROOT = path.join(__dirname, "..", "..");
const SVG_PATH = path.join(
  ROOT,
  "quantumswap-wallet-desktop",
  "public",
  "assets",
  "svg",
  "quantumswap.svg"
);
const ANDROID_RES = path.join(ROOT, "quantumswap-wallet-android", "app", "src", "main", "res");
const IOS_ASSETS = path.join(
  ROOT,
  "quantumswap-wallet-ios",
  "QuantumCoinWallet",
  "Assets.xcassets"
);

const BG = "#0b0614"; // background rect color inside the desktop SVG

// Remove the background rect so the mark renders on transparency; the
// opaque variants below re-flatten it onto BG at the target size.
function svgWithTransparentBackground(svgBuffer) {
  const svg = svgBuffer.toString("utf8");
  return Buffer.from(
    svg.replace(
      /<rect width="100%" height="100%" fill="#0b0614"\/>/,
      "<!-- background removed for transparency -->"
    ),
    "utf8"
  );
}

async function main() {
  const svgTransparent = svgWithTransparentBackground(fs.readFileSync(SVG_PATH));

  // High-res transparent mark, trimmed and padded onto a square
  // transparent canvas so all downstream resizes stay centered.
  const trimmed = await sharp(svgTransparent, { density: 600 })
    .png()
    .trim()
    .toBuffer();
  const meta = await sharp(trimmed).metadata();
  const side = Math.max(meta.width, meta.height);
  const markSquare = await sharp({
    create: { width: side, height: side, channels: 4, background: { r: 0, g: 0, b: 0, alpha: 0 } },
  })
    .composite([
      {
        input: trimmed,
        left: Math.floor((side - meta.width) / 2),
        top: Math.floor((side - meta.height) / 2),
      },
    ])
    .png()
    .toBuffer();

  // markScale: fraction of the canvas edge the mark spans.
  async function opaqueIcon(size, outPath, markScale = 0.78) {
    const markSize = Math.round(size * markScale);
    const mark = await sharp(markSquare).resize(markSize, markSize).png().toBuffer();
    const off = Math.floor((size - markSize) / 2);
    await sharp({
      create: { width: size, height: size, channels: 4, background: BG },
    })
      .composite([{ input: mark, left: off, top: off }])
      .flatten({ background: BG })
      .png()
      .toFile(outPath);
    console.log("  ", path.relative(ROOT, outPath), size + "x" + size, "(opaque)");
  }

  async function transparentIcon(size, outPath, markScale) {
    const markSize = Math.round(size * markScale);
    const mark = await sharp(markSquare).resize(markSize, markSize).png().toBuffer();
    const off = Math.floor((size - markSize) / 2);
    await sharp({
      create: { width: size, height: size, channels: 4, background: { r: 0, g: 0, b: 0, alpha: 0 } },
    })
      .composite([{ input: mark, left: off, top: off }])
      .png()
      .toFile(outPath);
    console.log("  ", path.relative(ROOT, outPath), size + "x" + size, "(transparent fg)");
  }

  // ---- Android ----
  console.log("Android launcher icons:");
  const densities = { mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 };
  for (const [dpi, size] of Object.entries(densities)) {
    const dir = path.join(ANDROID_RES, "mipmap-" + dpi);
    await opaqueIcon(size, path.join(dir, "ic_launcher.png"));
    await opaqueIcon(size, path.join(dir, "ic_launcher_round.png"));
    // Adaptive foreground: 108dp canvas, 66dp safe zone -> mark at ~55%
    // of the canvas so launcher masks never clip the ring.
    await transparentIcon(Math.round(size * 2.25), path.join(dir, "ic_launcher_foreground.png"), 0.55);
  }

  console.log("Android banner logo:");
  await opaqueIcon(400, path.join(ANDROID_RES, "drawable-v24", "logo.png"), 0.86);

  // ---- iOS ----
  console.log("iOS AppIcon set:");
  const appIconDir = path.join(IOS_ASSETS, "AppIcon.appiconset");
  const iosIcons = {
    "icon-20.png": 20,
    "icon-20@2x.png": 40,
    "icon-20-ipad@2x.png": 40,
    "icon-20@3x.png": 60,
    "icon-29.png": 29,
    "icon-29@2x.png": 58,
    "icon-29-ipad@2x.png": 58,
    "icon-29@3x.png": 87,
    "icon-40.png": 40,
    "icon-40@2x.png": 80,
    "icon-40-ipad@2x.png": 80,
    "icon-40@3x.png": 120,
    "icon-60@2x.png": 120,
    "icon-60@3x.png": 180,
    "icon-76.png": 76,
    "icon-76@2x.png": 152,
    "icon-83.5@2x.png": 167,
    "icon-1024.png": 1024,
  };
  for (const [file, size] of Object.entries(iosIcons)) {
    await opaqueIcon(size, path.join(appIconDir, file));
  }

  console.log("iOS banner logo:");
  await opaqueIcon(400, path.join(IOS_ASSETS, "Logo.imageset", "logo@3x.png"), 0.86);

  console.log("Done.");
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
