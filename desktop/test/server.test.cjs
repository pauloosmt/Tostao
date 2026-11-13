'use strict'
const assert = require('node:assert/strict')
const fs = require('node:fs')
const http = require('node:http')
const os = require('node:os')
const path = require('node:path')
const { after, before, describe, it } = require('node:test')
const { createServer, listen, rewriteCookie } = require('../src/server.cjs')

let webDir
let upstream
let upstreamPort
let app
let appPort
let last // o último pedido que a "API" recebeu

const get = (port, pathname, options = {}) =>
  new Promise((resolve, reject) => {
    const request = http.request({ host: '127.0.0.1', port, path: pathname, method: options.method ?? 'GET', headers: options.headers }, (reply) => {
      const chunks = []
      reply.on('data', (chunk) => chunks.push(chunk))
      reply.on('end', () => resolve({ status: reply.statusCode, headers: reply.headers, body: Buffer.concat(chunks) }))
    })
    request.on('error', reject)
    request.end(options.body)
  })

before(async () => {
  webDir = fs.mkdtempSync(path.join(os.tmpdir(), 'tostao-web-'))
  fs.mkdirSync(path.join(webDir, 'assets'))
  fs.writeFileSync(path.join(webDir, 'index.html'), '<!doctype html><title>Tostão</title>')
  fs.writeFileSync(path.join(webDir, 'sw.js'), '// sw')
  fs.writeFileSync(path.join(webDir, 'assets', 'app-abc123.js'), 'console.log(1)')
  fs.writeFileSync(path.join(path.dirname(webDir), 'segredo.txt'), 'fora da pasta do front')

  // A "API": guarda o que recebeu e responde com um cookie de sessão como o da API de verdade em produção.
  upstream = http.createServer((request, response) => {
    const chunks = []
    request.on('data', (chunk) => chunks.push(chunk))
    request.on('end', () => {
      last = { method: request.method, url: request.url, headers: request.headers, body: Buffer.concat(chunks) }
      if (request.url.startsWith('/api/v1/derruba')) return request.socket.destroy()
      response.writeHead(201, {
        'content-type': 'application/json',
        'set-cookie': ['tostao_session=abc; Path=/; Domain=api.exemplo.com; Secure; HttpOnly; SameSite=Lax; Max-Age=3600'],
      })
      response.end(JSON.stringify({ ok: true, recebido: last.body.length }))
    })
  })
  await new Promise((resolve) => upstream.listen(0, '127.0.0.1', resolve))
  upstreamPort = upstream.address().port

  app = createServer({ webDir, apiUrl: () => `http://127.0.0.1:${upstreamPort}`, apiOrigin: () => 'http://localhost:5173' })
  appPort = await listen(app, 0)
})

after(() => {
  app.close()
  upstream.close()
  fs.rmSync(webDir, { recursive: true, force: true })
  fs.rmSync(path.join(path.dirname(webDir), 'segredo.txt'), { force: true })
})

describe('o front', () => {
  it('serve o index na raiz e nas rotas do aplicativo (fallback do SPA), sem guardar em cache', async () => {
    for (const route of ['/', '/lancamentos', '/contas/abc/fatura']) {
      const reply = await get(appPort, route)
      assert.equal(reply.status, 200, route)
      assert.match(reply.headers['content-type'], /text\/html/)
      assert.equal(reply.headers['cache-control'], 'no-cache')
      assert.match(reply.body.toString(), /Tostão/)
    }
  })

  it('arquivos de assets vêm com o tipo certo e cache longo; o service worker não é guardado', async () => {
    const asset = await get(appPort, '/assets/app-abc123.js')
    assert.match(asset.headers['content-type'], /javascript/)
    assert.match(asset.headers['cache-control'], /immutable/)
    assert.equal((await get(appPort, '/sw.js')).headers['cache-control'], 'no-cache')
  })

  it('um arquivo com extensão que não existe é 404; só GET e HEAD são aceitos', async () => {
    assert.equal((await get(appPort, '/assets/nao-existe.js')).status, 404)
    assert.equal((await get(appPort, '/', { method: 'HEAD' })).status, 200)
    assert.equal((await get(appPort, '/', { method: 'POST' })).status, 405)
  })

  it('nunca sai da pasta do front (.., codificado, caminho absoluto)', async () => {
    for (const evil of ['/../segredo.txt', '/%2e%2e/segredo.txt', '/..%2fsegredo.txt', '/assets/../../segredo.txt']) {
      const reply = await get(appPort, evil)
      assert.ok(!reply.body.toString().includes('fora da pasta'), evil)
    }
    assert.equal((await get(appPort, '/%00')).status, 400)
  })
})

describe('o proxy da API', () => {
  it('repassa método, caminho e consulta e o cookie; reescreve a Origin; nunca monta nem repassa autorização', async () => {
    const reply = await get(appPort, '/api/v1/ping?x=1&y=2', { method: 'POST', headers: { cookie: 'tostao_session=eu', origin: 'http://127.0.0.1:47321', 'content-type': 'application/json', authorization: 'Bearer nao-deve-passar' }, body: '{"a":1}' })

    assert.equal(reply.status, 201)
    assert.deepEqual(JSON.parse(reply.body.toString()), { ok: true, recebido: 7 })
    assert.equal(last.method, 'POST')
    assert.equal(last.url, '/api/v1/ping?x=1&y=2')
    assert.equal(last.headers.cookie, 'tostao_session=eu')
    assert.equal(last.headers.origin, 'http://localhost:5173') // a que a API aceita, não a do app
    assert.equal(last.headers.authorization, undefined)
    assert.equal(last.body.toString(), '{"a":1}')
  })

  it('o cookie volta sem Domain nem Secure (mantém HttpOnly, SameSite, Path e Max-Age)', async () => {
    const reply = await get(appPort, '/api/v1/ping')
    const cookie = reply.headers['set-cookie'][0]

    assert.ok(!/domain=/i.test(cookie) && !/;\s*secure/i.test(cookie), cookie)
    for (const kept of ['tostao_session=abc', 'Path=/', 'HttpOnly', 'SameSite=Lax', 'Max-Age=3600']) assert.ok(cookie.includes(kept), kept)
    assert.equal(rewriteCookie('a=b; Secure; Domain=x.com'), 'a=b')
  })

  it('um envio grande (extrato) passa em fluxo e chega inteiro', async () => {
    const big = Buffer.alloc(1_500_000, 'a')
    const reply = await get(appPort, '/api/v1/upload', { method: 'POST', headers: { 'content-type': 'application/octet-stream' }, body: big })

    assert.equal(JSON.parse(reply.body.toString()).recebido, big.length)
  })

  it('só "/api" e "/api/..." vão para a API; "/apix" é rota do aplicativo', async () => {
    last = undefined
    const reply = await get(appPort, '/apix')

    assert.match(reply.body.toString(), /Tostão/)
    assert.equal(last, undefined)
  })

  it('API fora do ar ou que derruba a conexão: 502 em problem+json, sem tela em branco', async () => {
    const down = createServer({ webDir, apiUrl: () => 'http://127.0.0.1:9', apiOrigin: () => 'http://localhost:5173' })
    const port = await listen(down, 0)
    const reply = await get(port, '/api/v1/health')
    down.close()

    assert.equal(reply.status, 502)
    assert.match(reply.headers['content-type'], /problem\+json/)
    assert.equal(JSON.parse(reply.body.toString()).status, 502)
    assert.equal((await get(appPort, '/api/v1/derruba')).status, 502)
  })

  it('a configuração pode mudar sem reiniciar o servidor', async () => {
    let target = `http://127.0.0.1:9`
    const dynamic = createServer({ webDir, apiUrl: () => target, apiOrigin: () => 'http://localhost:5173' })
    const port = await listen(dynamic, 0)

    assert.equal((await get(port, '/api/v1/health')).status, 502)
    target = `http://127.0.0.1:${upstreamPort}`
    assert.equal((await get(port, '/api/v1/health')).status, 201)
    dynamic.close()
  })
})

describe('a porta', () => {
  it('se a preferida está ocupada, escolhe uma livre', async () => {
    const other = createServer({ webDir, apiUrl: () => 'http://127.0.0.1:9', apiOrigin: () => 'http://localhost:5173' })
    const port = await listen(other, appPort) // a porta do `app`, já ocupada

    assert.notEqual(port, appPort)
    assert.ok(port > 0)
    other.close()
  })
})
