import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'
import { VitePWA } from 'vite-plugin-pwa'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['favicon.svg', 'favicon.ico', 'apple-touch-icon.png', 'icons/favicon-32.png'],
      manifest: {
        name: 'Tostão',
        short_name: 'Tostão',
        description: 'Finanças pessoais',
        lang: 'pt-BR',
        start_url: '/',
        scope: '/',
        // as cores da marca (tokens do design system, tema claro)
        theme_color: '#0f5f63',
        background_color: '#f6f4ef',
        display: 'standalone',
        icons: [
          { src: 'icons/icon-192.png', sizes: '192x192', type: 'image/png', purpose: 'any' },
          { src: 'icons/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'any' },
          { src: 'icons/icon-maskable-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
        ],
      },
      // Chamadas à API nunca passam pelo cache do service worker
      // (as fontes woff2 entram no pré-cache para o aplicativo abrir igual sem rede)
      workbox: { navigateFallbackDenylist: [/^\/api\//], globPatterns: ['**/*.{js,css,html,woff2}'] },
    }),
  ],
  server: {
    // Em dev o navegador fala só com o Vite; /api vai para a API local,
    // espelhando o rewrite /api/* da Vercel em produção.
    proxy: { '/api': 'http://localhost:8080' },
  },
})
