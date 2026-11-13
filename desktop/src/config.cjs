'use strict'
// A configuração do app desktop: onde está a API e qual origem ela aceita. Vive num arquivo do usuário (nunca no código do front) e pode
// ser sobreposta por variáveis de ambiente. Os padrões são os da produção (Render e Vercel); para o Docker Compose do projeto use
// http://127.0.0.1:8080 e http://localhost:5173 em Arquivo → Configurar servidor.
const fs = require('node:fs')
const path = require('node:path')

const DEFAULTS = Object.freeze({ apiUrl: 'https://tostao-api.onrender.com', apiOrigin: 'https://tostao-roan.vercel.app' })

/** Só aceita um endereço http ou https, sem usuário e senha, sem caminho, consulta ou âncora; devolve a origem normalizada. */
function validateUrl(text, label) {
  const raw = String(text ?? '').trim()
  if (raw === '') return { ok: false, error: `Informe ${label}.` }
  let url
  try {
    url = new URL(raw)
  } catch {
    return { ok: false, error: `${label[0].toUpperCase()}${label.slice(1)} não é um endereço válido (exemplo: http://127.0.0.1:8080).` }
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') return { ok: false, error: `${label[0].toUpperCase()}${label.slice(1)} precisa começar com http:// ou https://.` }
  if (url.username !== '' || url.password !== '') return { ok: false, error: 'O endereço não pode ter usuário nem senha.' }
  if ((url.pathname !== '/' && url.pathname !== '') || url.search !== '' || url.hash !== '') return { ok: false, error: 'Use só o endereço do servidor, sem caminho, consulta ou âncora.' }
  return { ok: true, value: url.origin }
}

/** O que está no arquivo (se houver e for legível), com as variáveis de ambiente por cima e os padrões por baixo. */
function load(file, env = process.env) {
  let stored = {}
  try {
    stored = JSON.parse(fs.readFileSync(file, 'utf8'))
  } catch {
    stored = {}
  }
  const pick = (value, fallback, label) => {
    const checked = typeof value === 'string' ? validateUrl(value, label) : { ok: false }
    return checked.ok ? checked.value : fallback
  }
  const fromFile = {
    apiUrl: pick(stored.apiUrl, DEFAULTS.apiUrl, 'o endereço da API'),
    apiOrigin: pick(stored.apiOrigin, DEFAULTS.apiOrigin, 'a origem aceita pela API'),
  }
  return {
    apiUrl: pick(env.TOSTAO_API_URL, fromFile.apiUrl, 'o endereço da API'),
    apiOrigin: pick(env.TOSTAO_API_ORIGIN, fromFile.apiOrigin, 'a origem aceita pela API'),
  }
}

/** Valida os dois endereços e, só se ambos servem, grava. Devolve `{ ok: true, config }` ou `{ ok: false, errors }` (por campo). */
function save(file, input) {
  const apiUrl = validateUrl(input?.apiUrl, 'o endereço da API')
  const apiOrigin = validateUrl(input?.apiOrigin, 'a origem aceita pela API')
  if (!apiUrl.ok || !apiOrigin.ok) {
    return { ok: false, errors: { ...(apiUrl.ok ? {} : { apiUrl: apiUrl.error }), ...(apiOrigin.ok ? {} : { apiOrigin: apiOrigin.error }) } }
  }
  const config = { apiUrl: apiUrl.value, apiOrigin: apiOrigin.value }
  fs.mkdirSync(path.dirname(file), { recursive: true })
  fs.writeFileSync(file, `${JSON.stringify(config, null, 2)}\n`)
  return { ok: true, config }
}

module.exports = { DEFAULTS, validateUrl, load, save }
