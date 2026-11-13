// Prepara o empacotamento: garante o front compilado (frontend/dist) e copia o ícone do app.
import { spawnSync } from 'node:child_process'
import { copyFileSync, existsSync, mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..')
const front = join(root, 'frontend')

if (!existsSync(join(front, 'dist', 'index.html')) || process.argv.includes('--rebuild')) {
  console.log('Compilando o front (npm run build)…')
  const built = spawnSync('npm', ['run', 'build'], { cwd: front, stdio: 'inherit', shell: true })
  if (built.status !== 0) process.exit(built.status ?? 1)
}

mkdirSync(join(root, 'desktop', 'build'), { recursive: true })
copyFileSync(join(front, 'public', 'icons', 'icon-512.png'), join(root, 'desktop', 'build', 'icon.png'))
console.log('Front e ícone prontos.')
