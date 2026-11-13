// Confere o que o build produz (roda depois do `vite build`): o PWA instalável, a rede, a hospedagem e o orçamento de tamanho.
import assert from 'node:assert/strict'
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { describe, it } from 'node:test'
import { BUDGET_BYTES, initialScripts, measureInitialJs } from './check-budget.mjs'

const root = join(import.meta.dirname, '..')
const dist = join(root, 'dist')
const read = (file) => readFileSync(join(dist, file), 'utf8')

assert.ok(existsSync(join(dist, 'index.html')), 'dist/ não existe: rode `npm run build` antes deste teste')

describe('o PWA instalável', () => {
  const manifest = JSON.parse(read('manifest.webmanifest'))

  it('descreve o Tostão em português, instalável como aplicativo', () => {
    assert.equal(manifest.name, 'Tostão')
    assert.equal(manifest.short_name, 'Tostão')
    assert.equal(manifest.lang, 'pt-BR')
    assert.equal(manifest.display, 'standalone')
    assert.equal(manifest.theme_color, '#0f5f63')
    assert.equal(manifest.background_color, '#f6f4ef')
    assert.equal(manifest.start_url, '/')
  })

  it('todos os ícones existem no build, com os tamanhos 192 e 512 e um maskable', () => {
    const sizes = manifest.icons.map((icon) => icon.sizes)
    assert.ok(sizes.includes('192x192') && sizes.includes('512x512'))
    assert.ok(manifest.icons.some((icon) => icon.purpose === 'maskable'))
    for (const icon of manifest.icons) {
      assert.ok(existsSync(join(dist, icon.src)), `ícone ausente no build: ${icon.src}`)
    }
  })

  it('o index.html aponta o manifest, o ícone da Apple e o favicon', () => {
    const html = read('index.html')
    assert.match(html, /rel="manifest"/)
    assert.match(html, /rel="apple-touch-icon"/)
    assert.match(html, /rel="icon"/)
    assert.ok(existsSync(join(dist, 'apple-touch-icon.png')))
  })
})

describe('o service worker', () => {
  const sw = read('sw.js')
  const precached = [...sw.matchAll(/url:"([^"]+)"/g)].map((match) => match[1])

  it('pré-carrega o app (a página, os scripts e as fontes) e nada de /api', () => {
    assert.ok(precached.includes('index.html'))
    assert.ok(precached.some((url) => url.endsWith('.js')))
    assert.ok(precached.some((url) => url.endsWith('.woff2')))
    const api = precached.filter((url) => /^\/?api(\/|$)/.test(url))
    assert.deepEqual(api, [], 'rotas de /api/ no pré-cache')
  })

  it('a navegação do app não responde por /api/ (nunca devolve a página no lugar da API)', () => {
    assert.match(sw, /denylist:\[\/\^\\\/api\\\/\/\]/)
  })

  it('tudo o que pré-carrega existe no build', () => {
    for (const url of precached) assert.ok(existsSync(join(dist, url)), `pré-cache aponta para arquivo ausente: ${url}`)
  })
})

describe('nenhum recurso vem de outro domínio', () => {
  const strict = ['index.html', 'manifest.webmanifest', 'registerSW.js', 'sw.js']
  const external = /https?:\/\/[^\s"'`<>)\\;,]+/g

  it('página, manifest e service worker não citam nenhuma origem externa', () => {
    const assets = readdirSync(join(dist, 'assets')).filter((file) => file.endsWith('.css'))
    for (const file of [...strict, ...assets.map((name) => `assets/${name}`)]) {
      assert.deepEqual(read(file).match(external) ?? [], [], `origem externa em dist/${file}`)
    }
  })

  it('no JavaScript, só as URLs que são texto (namespaces e mensagens), nunca uma origem buscada', () => {
    // Os namespaces do W3C (SVG, MathML), os links das mensagens de erro do React e do roteador, e a base `localhost` do `new URL`.
    const text = /^https?:\/\/(?:www\.w3\.org|react\.dev|reactrouter\.com|localhost)(?:[/:]|$)/
    for (const file of readdirSync(join(dist, 'assets')).filter((name) => name.endsWith('.js'))) {
      const strange = (read(`assets/${file}`).match(external) ?? []).filter((url) => !text.test(url))
      assert.deepEqual(strange, [], `origem externa em dist/assets/${file}`)
    }
  })
})

describe('a hospedagem (Vercel)', () => {
  const config = JSON.parse(readFileSync(join(root, 'vercel.json'), 'utf8'))
  const rule = config.rewrites[0]
  // A Vercel casa `source` no caminho inteiro.
  const matches = (path) => new RegExp(`^${rule.source}$`).test(path)

  it('recarregar uma rota do app devolve o index.html, em vez de dar 404', () => {
    assert.equal(rule.destination, '/index.html')
    for (const path of ['/perfil', '/entrar', '/cadastro', '/uma/rota/qualquer']) assert.ok(matches(path), path)
  })

  it('não toca em /api/: a API nunca devolve a página do app', () => {
    for (const path of ['/api/v1/me', '/api/v1/auth/login', '/api/']) assert.ok(!matches(path), path)
  })

  it('encaminha /api/* ao Render por https, sem expor outra URL', () => {
    const api = config.rewrites.find((r) => r.source.startsWith('/api/'))
    assert.match(api.destination, /^https:\/\/[^/]+\/api\/:path\*$/)
    assert.equal(config.rewrites.length, 2)
    assert.equal(JSON.stringify(config).match(/https?:\/\//g).length, 1)
  })
})

describe('o orçamento de tamanho', () => {
  it('o JavaScript inicial do build cabe em 150 KB com gzip', () => {
    const { files, total } = measureInitialJs(dist)
    assert.ok(files.length > 0, 'nenhum script inicial no index.html')
    assert.ok(total <= BUDGET_BYTES, `JavaScript inicial com ${(total / 1024).toFixed(1)} KB gzip, acima de 150 KB`)
  })

  describe('a medição em si (num build de mentira)', () => {
    const fake = (jsContent) => {
      const dir = mkdtempSync(join(tmpdir(), 'tostao-budget-'))
      mkdirSync(join(dir, 'assets'))
      writeFileSync(
        join(dir, 'index.html'),
        '<script type="module" src="/assets/app.js"></script><link rel="modulepreload" href="/assets/lib.js"><link rel="stylesheet" href="/assets/a.css">',
      )
      writeFileSync(join(dir, 'assets', 'app.js'), jsContent)
      writeFileSync(join(dir, 'assets', 'lib.js'), 'x')
      writeFileSync(join(dir, 'assets', 'lazy.js'), 'y'.repeat(10_000))
      return dir
    }

    it('conta o script e o modulepreload, não o estilo nem o que carrega depois', () => {
      const dir = fake('console.log(1)')
      try {
        assert.deepEqual(initialScripts(dir), ['assets/app.js', 'assets/lib.js'])
        assert.equal(measureInitialJs(dir).files.length, 2)
      } finally {
        rmSync(dir, { recursive: true, force: true })
      }
    })

    it('um script que não comprime (conteúdo aleatório) estoura o orçamento', async () => {
      const { randomBytes } = await import('node:crypto')
      const dir = fake(randomBytes(BUDGET_BYTES + 1024))
      try {
        assert.ok(measureInitialJs(dir).total > BUDGET_BYTES)
      } finally {
        rmSync(dir, { recursive: true, force: true })
      }
    })
  })
})
