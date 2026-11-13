'use strict'
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { describe, it } = require('node:test')
const { DEFAULTS, load, save, validateUrl } = require('../src/config.cjs')

const tmp = () => path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'tostao-cfg-')), 'dados', 'config.json')

describe('o endereço', () => {
  it('aceita http e https, normaliza para a origem e tira a barra do fim', () => {
    assert.deepEqual(validateUrl('http://127.0.0.1:8080/', 'x'), { ok: true, value: 'http://127.0.0.1:8080' })
    assert.deepEqual(validateUrl('  https://api.tostao.app  ', 'x'), { ok: true, value: 'https://api.tostao.app' })
  })

  it('recusa vazio, texto que não é endereço, outro protocolo, usuário e senha, caminho, consulta e âncora', () => {
    for (const bad of ['', '   ', 'api', 'ftp://x.com', 'file:///c:/x', 'http://u:p@x.com', 'http://x.com/api', 'http://x.com/?a=1', 'http://x.com/#a']) {
      const checked = validateUrl(bad, 'o endereço da API')
      assert.equal(checked.ok, false, bad)
      assert.ok(checked.error.length > 0)
    }
  })
})

describe('carregar e gravar', () => {
  it('sem arquivo, valem os padrões do Docker Compose do projeto', () => {
    assert.deepEqual(load(tmp(), {}), DEFAULTS)
    assert.deepEqual(DEFAULTS, { apiUrl: 'http://127.0.0.1:8080', apiOrigin: 'http://localhost:5173' })
  })

  it('grava só se os dois endereços servem; depois carrega o que foi gravado', () => {
    const file = tmp()
    const saved = save(file, { apiUrl: 'https://api.tostao.app/', apiOrigin: 'https://tostao.app' })

    assert.equal(saved.ok, true)
    assert.deepEqual(load(file, {}), { apiUrl: 'https://api.tostao.app', apiOrigin: 'https://tostao.app' })
  })

  it('um valor inválido não grava nada e aponta o campo', () => {
    const file = tmp()
    const refused = save(file, { apiUrl: 'ftp://x', apiOrigin: 'http://localhost:5173' })

    assert.equal(refused.ok, false)
    assert.deepEqual(Object.keys(refused.errors), ['apiUrl'])
    assert.equal(fs.existsSync(file), false)
  })

  it('as variáveis de ambiente valem sobre o arquivo; um arquivo quebrado ou com valor ruim volta aos padrões', () => {
    const file = tmp()
    save(file, { apiUrl: 'https://api.tostao.app', apiOrigin: 'https://tostao.app' })

    assert.equal(load(file, { TOSTAO_API_URL: 'http://10.0.0.5:8080' }).apiUrl, 'http://10.0.0.5:8080')
    assert.equal(load(file, { TOSTAO_API_URL: 'lixo' }).apiUrl, 'https://api.tostao.app') // ambiente inválido é ignorado
    fs.writeFileSync(file, '{ não é json')
    assert.deepEqual(load(file, {}), DEFAULTS)
    fs.writeFileSync(file, JSON.stringify({ apiUrl: 'ftp://x', apiOrigin: 5 }))
    assert.deepEqual(load(file, {}), DEFAULTS)
  })
})
