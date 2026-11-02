import { defineConfig } from 'vite';
import { VitePWA } from 'vite-plugin-pwa';
import { cpSync, mkdirSync, existsSync } from 'node:fs';
import path from 'node:path';

// Pubblicata su GitHub Pages in https://<utente>.github.io/NoteSpese/
const base = process.env.WEBAPP_BASE ?? '/NoteSpese/';
const nm = (p) => path.resolve('node_modules', p);

/**
 * File copiati così come sono nella build: motore OCR e lingua italiana, risorse di pdf.js,
 * modello Excel standard. Vengono scaricati solo quando servono e poi restano in cache.
 */
const STATIC_COPIES = [
  [nm('tesseract.js/dist/worker.min.js'), 'ocr/worker.min.js'],
  [nm('tesseract.js-core/tesseract-core-lstm.wasm.js'), 'ocr/tesseract-core-lstm.wasm.js'],
  [nm('tesseract.js-core/tesseract-core-simd-lstm.wasm.js'), 'ocr/tesseract-core-simd-lstm.wasm.js'],
  [nm('tesseract.js-core/tesseract-core-relaxedsimd-lstm.wasm.js'), 'ocr/tesseract-core-relaxedsimd-lstm.wasm.js'],
  [nm('@tesseract.js-data/ita/4.0.0_best_int/ita.traineddata.gz'), 'ocr/ita.traineddata.gz'],
  [nm('pdfjs-dist/cmaps'), 'pdfjs/cmaps'],
  [nm('pdfjs-dist/standard_fonts'), 'pdfjs/standard_fonts'],
  [nm('pdfjs-dist/wasm'), 'pdfjs/wasm'],
  [path.resolve('../modello/Note spese 2026.xlsx'), 'modello/Note spese 2026.xlsx']
];

function staticCopies() {
  let outDir;
  return {
    name: 'notespese-static-copies',
    configResolved(config) {
      outDir = path.resolve(config.root, config.build.outDir);
    },
    writeBundle() {
      for (const [from, to] of STATIC_COPIES) {
        if (!existsSync(from)) throw new Error(`File mancante per la build: ${from}`);
        const dest = path.join(outDir, to);
        mkdirSync(path.dirname(dest), { recursive: true });
        cpSync(from, dest, { recursive: true });
      }
    }
  };
}

export default defineConfig({
  base,
  define: {
    'import.meta.env.VITE_APP_VERSION': JSON.stringify(process.env.GITHUB_SHA?.slice(0, 7) ?? 'dev')
  },
  build: {
    target: ['es2022', 'safari16']
  },
  plugins: [
    staticCopies(),
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['icons/icon.svg', 'icons/apple-touch-icon.png'],
      workbox: {
        // App sempre disponibile offline; OCR, risorse PDF e modello in cache al primo uso
        globPatterns: ['**/*.{js,mjs,css,html,wasm,svg,png}'],
        globIgnores: ['ocr/**', 'pdfjs/**', 'modello/**'],
        maximumFileSizeToCacheInBytes: 8 * 1024 * 1024,
        runtimeCaching: [
          {
            urlPattern: ({ url }) => /\/(ocr|pdfjs|modello)\//.test(url.pathname),
            handler: 'CacheFirst',
            options: { cacheName: 'notespese-risorse', expiration: { maxEntries: 400 } }
          }
        ]
      },
      manifest: {
        name: 'Note Spese – PerExpenseReport',
        short_name: 'Note Spese',
        description: "Note spese con backup condivisi su Dropbox con l'app Android.",
        lang: 'it',
        display: 'standalone',
        start_url: base,
        scope: base,
        theme_color: '#2E7D32',
        background_color: '#ffffff',
        icons: [
          { src: 'icons/icon-192.png', sizes: '192x192', type: 'image/png' },
          { src: 'icons/icon-512.png', sizes: '512x512', type: 'image/png' },
          { src: 'icons/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' }
        ]
      }
    })
  ]
});
