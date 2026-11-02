import { defineConfig } from 'vite';
import { VitePWA } from 'vite-plugin-pwa';

// Pubblicata su GitHub Pages in https://<utente>.github.io/NoteSpese/
const base = process.env.WEBAPP_BASE ?? '/NoteSpese/';

export default defineConfig({
  base,
  define: {
    'import.meta.env.VITE_APP_VERSION': JSON.stringify(process.env.GITHUB_SHA?.slice(0, 7) ?? 'dev')
  },
  plugins: [
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['icons/icon.svg', 'icons/apple-touch-icon.png'],
      workbox: {
        // Tutto in cache: l'app funziona anche offline (serve la rete solo per Dropbox)
        globPatterns: ['**/*.{js,css,html,wasm,svg,png}'],
        maximumFileSizeToCacheInBytes: 5 * 1024 * 1024
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
