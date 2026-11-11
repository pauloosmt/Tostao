// O orçamento de tamanho: o JavaScript que a primeira tela carrega (os scripts do index.html e os pré-carregados), somado em gzip,
// não passa de 150 KB. Roda depois do build; um app que ultrapassa o orçamento falha o `verify` em vez de ficar lento no celular.
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { gzipSync } from 'node:zlib'

export const BUDGET_BYTES = 150 * 1024

/** Os arquivos .js que o index.html carrega de início (script e modulepreload), na ordem em que aparecem. */
export function initialScripts(distDir) {
  const html = readFileSync(join(distDir, 'index.html'), 'utf8')
  const found = new Set()
  for (const tag of html.match(/<(?:script|link)\b[^>]*>/gi) ?? []) {
    const isScript = /^<script\b/i.test(tag) || /\brel=["']?modulepreload/i.test(tag)
    const url = /\b(?:src|href)=["']([^"']+)["']/i.exec(tag)?.[1]
    if (isScript && url && /\.m?js$/i.test(url.split('?')[0])) found.add(url.replace(/^\//, ''))
  }
  return [...found]
}

/** O total, em bytes comprimidos com gzip, do JavaScript inicial. */
export function measureInitialJs(distDir) {
  const files = initialScripts(distDir).map((file) => {
    const gzipped = gzipSync(readFileSync(join(distDir, file))).length
    return { file, gzipped }
  })
  return { files, total: files.reduce((sum, item) => sum + item.gzipped, 0) }
}

const isMain = process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]
if (isMain) {
  const { files, total } = measureInitialJs(join(import.meta.dirname, '..', 'dist'))
  const kb = (bytes) => (bytes / 1024).toFixed(1)
  for (const item of files) console.log(`  ${item.file}: ${kb(item.gzipped)} KB gzip`)
  console.log(`JavaScript inicial: ${kb(total)} KB gzip (orçamento ${kb(BUDGET_BYTES)} KB)`)
  if (files.length === 0) {
    console.error('Nenhum script inicial encontrado no dist/index.html: o build existe?')
    process.exit(1)
  }
  if (total > BUDGET_BYTES) {
    console.error('Orçamento de tamanho estourado.')
    process.exit(1)
  }
}
