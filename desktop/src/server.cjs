'use strict'
// O servidor local do app desktop: serve o front compilado (com o fallback de rota do SPA) e repassa `/api` à API configurada, com a `Origin`
// que ela aceita (o mesmo que o proxy do `vite dev` faz). Escuta só em 127.0.0.1. Nunca monta cabeçalho de autorização: repassa o cookie
// que a janela mandou, e devolve o cookie sem `Domain` nem `Secure` (o salto até a janela é pelo próprio computador).
const fs = require('node:fs')
const http = require('node:http')
const https = require('node:https')
const path = require('node:path')

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.txt': 'text/plain; charset=utf-8',
  '.map': 'application/json; charset=utf-8',
}

// O que o proxy não repassa da janela para a API.
const DROP_REQUEST = new Set(['host', 'origin', 'referer', 'connection', 'proxy-connection', 'keep-alive', 'upgrade', 'authorization'])

/** Tira `Domain` e `Secure` de um `Set-Cookie`; o resto (Path, HttpOnly, SameSite, Max-Age) fica. */
function rewriteCookie(cookie) {
  return cookie
    .split(';')
    .map((part) => part.trim())
    .filter((part) => !/^domain=/i.test(part) && !/^secure$/i.test(part))
    .join('; ')
}

function problem(response, status, title, detail) {
  const body = JSON.stringify({ title, status, detail })
  response.writeHead(status, { 'content-type': 'application/problem+json', 'content-length': Buffer.byteLength(body) })
  response.end(body)
}

function proxy(request, response, apiUrl, apiOrigin) {
  const target = new URL(request.url, apiUrl)
  const headers = {}
  for (const [name, value] of Object.entries(request.headers)) if (!DROP_REQUEST.has(name)) headers[name] = value
  headers.origin = apiOrigin
  const client = target.protocol === 'https:' ? https : http
  const upstream = client.request(target, { method: request.method, headers }, (reply) => {
    const outgoing = { ...reply.headers }
    if (reply.headers['set-cookie']) outgoing['set-cookie'] = reply.headers['set-cookie'].map(rewriteCookie)
    response.writeHead(reply.statusCode ?? 502, outgoing)
    reply.pipe(response)
  })
  upstream.setTimeout(120_000, () => upstream.destroy(Object.assign(new Error('tempo esgotado'), { code: 'ETIMEDOUT' })))
  upstream.on('error', (error) => {
    if (response.headersSent) return response.destroy()
    if (error.code === 'ETIMEDOUT') return problem(response, 504, 'Servidor demorou demais', 'A API não respondeu a tempo.')
    return problem(response, 502, 'Servidor indisponível', 'Não foi possível falar com a API. Confira o endereço em Arquivo → Configurar servidor.')
  })
  request.on('aborted', () => upstream.destroy())
  request.pipe(upstream)
}

function serveFile(request, response, file) {
  const extension = path.extname(file).toLowerCase()
  const stat = fs.statSync(file)
  const immutable = extension !== '.html' && /[\\/]assets[\\/]/.test(file)
  response.writeHead(200, {
    'content-type': TYPES[extension] ?? 'application/octet-stream',
    'content-length': stat.size,
    'cache-control': immutable ? 'public, max-age=31536000, immutable' : 'no-cache',
    'x-content-type-options': 'nosniff',
    'referrer-policy': 'no-referrer',
  })
  if (request.method === 'HEAD') return response.end()
  fs.createReadStream(file).pipe(response)
}

function serveStatic(request, response, webDir) {
  if (request.method !== 'GET' && request.method !== 'HEAD') {
    response.writeHead(405, { allow: 'GET, HEAD' })
    return response.end()
  }
  let pathname
  try {
    pathname = decodeURIComponent(new URL(request.url, 'http://local').pathname)
  } catch {
    return problem(response, 400, 'Endereço inválido', 'O caminho não pôde ser lido.')
  }
  if (pathname.includes('\0')) return problem(response, 400, 'Endereço inválido', 'O caminho não pôde ser lido.')
  const root = path.resolve(webDir)
  const wanted = path.resolve(root, `.${pathname}`)
  // Nunca sai da pasta do front (`..`, caminho absoluto, ligação para fora).
  if (wanted !== root && !wanted.startsWith(root + path.sep)) return problem(response, 403, 'Proibido', 'Esse caminho não é servido.')
  try {
    if (fs.statSync(wanted).isFile()) return serveFile(request, response, wanted)
  } catch {
    // não existe: cai no fallback abaixo
  }
  // Rota do aplicativo (sem extensão): o SPA decide. Arquivo com extensão que não existe é 404 de verdade.
  if (path.extname(pathname) === '') return serveFile(request, response, path.join(root, 'index.html'))
  response.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' })
  response.end('Não encontrado')
}

/**
 * Cria o servidor. `apiUrl` e `apiOrigin` são funções, para a configuração poder mudar sem reiniciar o programa.
 * `/api` e `/api/...` vão para a API; o resto é o front.
 */
function createServer({ webDir, apiUrl, apiOrigin }) {
  return http.createServer((request, response) => {
    const pathname = (request.url ?? '/').split('?')[0]
    if (pathname === '/api' || pathname.startsWith('/api/')) return proxy(request, response, apiUrl(), apiOrigin())
    return serveStatic(request, response, webDir)
  })
}

/** Escuta em 127.0.0.1 na porta preferida e, se estiver ocupada, numa livre. Devolve a porta escolhida. */
function listen(server, preferredPort) {
  const attempt = (port) =>
    new Promise((resolve, reject) => {
      const onError = (error) => {
        server.off('listening', onListening)
        reject(error)
      }
      const onListening = () => {
        server.off('error', onError)
        resolve(server.address().port)
      }
      server.once('error', onError)
      server.once('listening', onListening)
      server.listen(port, '127.0.0.1')
    })
  return attempt(preferredPort).catch((error) => {
    if (error.code === 'EADDRINUSE' && preferredPort !== 0) return attempt(0)
    throw error
  })
}

module.exports = { createServer, listen, rewriteCookie }
