// Guard-rails da constituição para o front:
//  * não se monta header Authorization à mão (a sessão é o cookie HttpOnly);
//  * não se coloca token, senha ou código em rota/query string;
//  * o navegador fala só com o domínio do aplicativo: nenhum recurso (fonte, script, estilo, imagem) de outra origem.
import { readdirSync, readFileSync, statSync, existsSync } from 'node:fs'
import { extname, join } from 'node:path'
import { pathToFileURL } from 'node:url'

const CODE = ['.ts', '.tsx', '.js', '.jsx', '.mjs']
const STYLE_AND_PAGE = ['.css', '.html']

// http(s):// que não seja localhost, 127.0.0.1 ou o namespace do W3C (xmlns de SVG/XML), que não é um recurso.
const EXTERNAL_ORIGIN = /https?:\/\/(?!(?:localhost|127\.0\.0\.1)(?![\w.-])|www\.w3\.org\/)/i

export const rules = [
  { re: /authorization/i, msg: 'header Authorization manual é proibido (use o cookie HttpOnly)', exts: CODE },
  { re: /[?&](token|password|senha|code|jwt)=/i, msg: 'token/senha/código em query string é proibido', exts: CODE },
  {
    re: EXTERNAL_ORIGIN,
    msg: 'origem externa é proibida (o navegador fala só com o domínio do aplicativo; hospede o recurso no próprio app)',
    exts: [...CODE, ...STYLE_AND_PAGE],
  },
]

function* files(dir) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) yield* files(path)
    else yield path
  }
}

/** Varre `<root>/src` e o `<root>/index.html` e devolve as violações: `{ file, line, msg }`. */
export function findViolations(root = '.') {
  const targets = []
  const src = join(root, 'src')
  if (existsSync(src)) targets.push(...files(src))
  const page = join(root, 'index.html')
  if (existsSync(page)) targets.push(page)

  const violations = []
  for (const file of targets) {
    const ext = extname(file)
    const applicable = rules.filter((rule) => rule.exts.includes(ext))
    if (applicable.length === 0) continue
    readFileSync(file, 'utf8')
      .split('\n')
      .forEach((line, i) => {
        for (const { re, msg } of applicable) {
          if (re.test(line)) violations.push({ file, line: i + 1, msg })
        }
      })
  }
  return violations
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const violations = findViolations('.')
  for (const { file, line, msg } of violations) console.error(`${file}:${line}: ${msg}`)
  process.exit(violations.length > 0 ? 1 : 0)
}
