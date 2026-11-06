import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'
import { VitePWA } from 'vite-plugin-pwa'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: 'autoUpdate',
      manifest: {
        name: 'Tostão',
        short_name: 'Tostão',
        description: 'Finanças pessoais',
        lang: 'pt-BR',
        theme_color: '#0f766e',
        background_color: '#ffffff',
        display: 'standalone',
        icons: [{ src: 'favicon.svg', sizes: 'any', type: 'image/svg+xml', purpose: 'any' }],
      },
      // Chamadas à API nunca passam pelo cache do service worker
      workbox: { navigateFallbackDenylist: [/^\/api\//] },
    }),
  ],
  server: {
    // Em dev o navegador fala só com o Vite; /api vai para a API local,
    // espelhando o rewrite /api/* da Vercel em produção.
    proxy: { '/api': 'http://localhost:8080' },
  },
})
