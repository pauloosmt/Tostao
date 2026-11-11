import react from '@vitejs/plugin-react'
import type { ProxyOptions } from 'vite'
import { defineConfig } from 'vitest/config'
import { VitePWA } from 'vite-plugin-pwa'

// A API só aceita a origem de CORS_ORIGIN (por padrão http://localhost:5173). O proxy local fala com ela por essa origem, qualquer que
// seja o endereço aberto no navegador (127.0.0.1, ou a porta 4173 do `vite preview`); sem isso a API responderia 403.
const API_ORIGIN = process.env.CORS_ORIGIN ?? 'http://localhost:5173'
const apiProxy: Record<string, ProxyOptions> = {
  '/api': {
    target: 'http://localhost:8080',
    configure: (proxy) => {
      proxy.on('proxyReq', (request) => request.setHeader('origin', API_ORIGIN))
    },
  },
}

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
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    restoreMocks: true,
    // O axe no jsdom é lento na primeira execução e, com os arquivos de teste em paralelo, passava dos 5 s padrão (falha intermitente).
    testTimeout: 30_000,
    // Cada arquivo de teste sobe um jsdom (~200 MB). Com 11 processos em paralelo (12 núcleos) e dezenas de arquivos, uma máquina com pouca
    // memória livre começava a paginar e os testes estouravam o tempo. Dois processos cabem na memória de uma máquina apertada e continuam rápidos o bastante.
    maxWorkers: 2,
  },
  // Em dev (`vite`) e no `vite preview` o navegador fala só com o Vite; /api vai para a API local,
  // espelhando o rewrite de /api/* que a Vercel faz em produção.
  server: { proxy: apiProxy },
  preview: { proxy: apiProxy },
})
