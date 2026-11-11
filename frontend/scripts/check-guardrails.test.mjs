import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { after, test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { findViolations } from './check-guardrails.mjs'

const created = []
after(() => created.forEach((dir) => rmSync(dir, { recursive: true, force: true })))

/** Um projeto temporário com os arquivos dados ({ 'src/a.ts': '...', 'index.html': '...' }). */
function project(files) {
  const root = mkdtempSync(join(tmpdir(), 'guardrails-'))
  created.push(root)
  mkdirSync(join(root, 'src'))
  for (const [path, content] of Object.entries(files)) {
    mkdirSync(dirname(join(root, path)), { recursive: true })
    writeFileSync(join(root, path), content)
  }
  return root
}

test('um projeto limpo passa', () => {
  const root = project({
    'src/a.ts': "fetch('/api/v1/me')\n",
    'src/style.css': 'body { color: red; background: url("/img/a.png") }\n',
    'index.html': '<link rel="icon" href="/favicon.svg" />\n',
  })
  assert.deepEqual(findViolations(root), [])
})

test('o header Authorization manual é proibido, em qualquer caixa e em .ts e .tsx', () => {
  const root = project({
    'src/a.ts': "headers.set('Authorization', x)\n",
    'src/b.tsx': "const h = { AUTHORIZATION: 'x' }\n",
    'src/c.js': "const authorization = 1\n",
  })
  const found = findViolations(root)
  assert.equal(found.length, 3)
  assert.ok(found.every((v) => v.msg.includes('Authorization')))
})

test('token, senha e código na query string são proibidos', () => {
  for (const url of ['/x?token=1', '/x?a=1&password=2', '/x?code=3', '/x?jwt=4', '/x?senha=5']) {
    const root = project({ 'src/a.ts': `fetch('${url}')\n` })
    assert.equal(findViolations(root).length, 1, url)
  }
})

test('palavras parecidas, sem a forma de query string, não são violação', () => {
  const root = project({ 'src/a.ts': "const tokenizer = 1; const codes = []; const x = 'a=b'\n" })
  assert.deepEqual(findViolations(root), [])
})

test('origem externa é proibida em código, estilo e página', () => {
  const root = project({
    'src/a.ts': "import('https://cdn.example.com/x.js')\n",
    'src/b.tsx': "<img src=\"http://example.com/a.png\" />\n",
    'src/c.css': '@import url("https://fonts.googleapis.com/css2?family=X");\n',
    'src/d.css': 'a { background: url(https://example.com/a.png) }\n',
    'index.html': '<script src="https://cdn.example.com/a.js"></script>\n',
  })
  const found = findViolations(root)
  assert.equal(found.length, 5)
  assert.ok(found.every((v) => v.msg.includes('origem externa')))
})

test('localhost, 127.0.0.1 e os namespaces do W3C são permitidos', () => {
  const root = project({
    'src/a.ts': "const dev = 'http://localhost:8080'; const ip = 'http://127.0.0.1:8080/api'\n",
    'src/Icon.tsx': '<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" />\n',
  })
  assert.deepEqual(findViolations(root), [])
})

test('um domínio que só começa com localhost continua sendo externo', () => {
  const root = project({ 'src/a.ts': "fetch('http://localhost.evil.com/a')\nfetch('https://127.0.0.1.evil.com/b')\n" })
  assert.equal(findViolations(root).length, 2)
})

test('a violação diz o arquivo e a linha', () => {
  const root = project({ 'src/pasta/a.ts': "const ok = 1\nfetch('https://x.example.com')\n" })
  const [violation] = findViolations(root)
  assert.ok(violation.file.endsWith('a.ts'))
  assert.equal(violation.line, 2)
})

test('subpastas são varridas e arquivos que não são código nem estilo, ignorados', () => {
  const root = project({
    'src/a/b/c/deep.ts': "fetch('https://x.example.com')\n",
    'src/readme.md': 'veja https://example.com e Authorization\n',
    'src/data.json': '{"url":"https://example.com"}\n',
  })
  const found = findViolations(root)
  assert.equal(found.length, 1)
  assert.ok(found[0].file.endsWith('deep.ts'))
})

test('o projeto de verdade está limpo', () => {
  const real = join(dirname(fileURLToPath(import.meta.url)), '..')
  assert.deepEqual(findViolations(real), [])
})
