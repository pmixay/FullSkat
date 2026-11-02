#!/usr/bin/env node
/**
 * Renders watch-screen mockups (HTML) to circular PNGs the size of the target display.
 *
 *   node tools/render/render.js <input.html|dir> [more ...] [--out dir] [--size 432] [--scale 2]
 *
 * Target device: Galaxy Watch7 40 mm (SM-L305F), 1.3" round AMOLED, 432 x 432 px. At Wear OS's 2x
 * density that is a 216 x 216 dp circle, so a mockup is laid out in a 216 x 216 CSS-px viewport and
 * rendered at deviceScaleFactor 2. The square capture is then cut to a circle of the full diameter:
 * everything outside it is transparent.
 *
 * Optional per-file variants: a mockup may read the `?state=` query, so `file.html?state=listening`
 * renders `file__listening.png`. Without a query the file name alone is used.
 */
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const args = process.argv.slice(2);
const opt = (name, fallback) => {
  const i = args.indexOf(`--${name}`);
  if (i < 0) return fallback;
  const [, value] = args.splice(i, 2);
  return value;
};
const out = path.resolve(opt('out', 'design/renders'));
const px = Number(opt('size', 432)); // physical diameter in pixels
const scale = Number(opt('scale', 2)); // device pixel ratio (Wear OS watches are ~2x)
const css = px / scale; // layout viewport in CSS px (dp)
const inputs = args.filter((a) => !a.startsWith('--'));
if (!inputs.length) {
  console.error('usage: render.js <input.html|dir>... [--out dir] [--size 432] [--scale 2]');
  process.exit(2);
}

const expand = (input) => {
  const [file, query] = input.split('?');
  if (fs.existsSync(file) && fs.statSync(file).isDirectory()) {
    return fs.readdirSync(file).filter((f) => f.endsWith('.html')).sort().map((f) => ({ file: path.join(file, f), query: '' }));
  }
  return [{ file, query: query || '' }];
};

(async () => {
  fs.mkdirSync(out, { recursive: true });
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: css, height: css }, deviceScaleFactor: scale });
  const maskPage = await context.newPage();
  let count = 0;
  for (const { file, query } of inputs.flatMap(expand)) {
    const page = await context.newPage();
    await page.goto(`file://${path.resolve(file)}${query ? `?${query}` : ''}`);
    await page.evaluate(() => document.fonts && document.fonts.ready);
    const state = new URLSearchParams(query).get('state');
    const name = path.basename(file, '.html') + (state ? `__${state}` : '') + '.png';
    const shot = await page.screenshot({ clip: { x: 0, y: 0, width: css, height: css } });
    // Cut the square capture to the round display: everything outside the circle becomes transparent.
    const round = await maskPage.evaluate(
      ({ data, diameter }) =>
        new Promise((resolve) => {
          const img = new Image();
          img.onload = () => {
            const canvas = document.createElement('canvas');
            canvas.width = canvas.height = diameter;
            const ctx = canvas.getContext('2d');
            ctx.beginPath();
            ctx.arc(diameter / 2, diameter / 2, diameter / 2, 0, Math.PI * 2);
            ctx.clip();
            ctx.drawImage(img, 0, 0, diameter, diameter);
            resolve(canvas.toDataURL('image/png').split(',')[1]);
          };
          img.src = `data:image/png;base64,${data}`;
        }),
      { data: shot.toString('base64'), diameter: px },
    );
    fs.writeFileSync(path.join(out, name), Buffer.from(round, 'base64'));
    await page.close();
    console.log(`${path.join(out, name)}  (${px}x${px}px circle)`);
    count++;
  }
  await browser.close();
  if (!count) process.exit(1);
})();
