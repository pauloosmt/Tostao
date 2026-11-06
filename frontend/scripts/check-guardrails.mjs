// Guard-rail da constituição: o front não monta header Authorization à mão
// e não coloca token, senha ou código em rota/query string.
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'

const rules = [
  { re: /authorization/i, msg: 'header Authorization manual é proibido (use o cookie HttpOnly)' },
  { re: /[?&](token|password|senha|code|jwt)=/i, msg: 'token/senha/código em query string é proibido' },
]

function* files(dir) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    if (statSync(p).isDirectory()) yield* files(p)
    else if (/\.(ts|tsx|js|jsx)$/.test(name)) yield p
  }
}

let failed = false
for (const file of files('src')) {
  const lines = readFileSync(file, 'utf8').split('\n')
  lines.forEach((line, i) => {
    for (const { re, msg } of rules) {
      if (re.test(line)) {
        console.error(`${file}:${i + 1}: ${msg}`)
        failed = true
      }
    }
  })
}
process.exit(failed ? 1 : 0)
