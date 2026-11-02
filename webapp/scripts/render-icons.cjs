// Genera le icone PNG dall'SVG (da rilanciare solo se l'icona cambia): node scripts/render-icons.cjs
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

(async () => {
  const dir = path.join(__dirname, '..', 'public', 'icons');
  const svg = fs.readFileSync(path.join(dir, 'icon.svg'), 'utf8');
  const browser = await chromium.launch();
  const page = await browser.newPage();
  for (const [name, size] of [['apple-touch-icon.png', 180], ['icon-192.png', 192], ['icon-512.png', 512]]) {
    await page.setViewportSize({ width: size, height: size });
    await page.setContent(`<style>html,body{margin:0}</style>${svg.replace('width="512" height="512"', `width="${size}" height="${size}"`)}`);
    await page.screenshot({ path: path.join(dir, name), omitBackground: false });
  }
  await browser.close();
})();
