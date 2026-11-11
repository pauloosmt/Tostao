// Para onde voltar depois de entrar. O lugar pedido vem do estado do roteador (nunca do endereço), mas mesmo assim só se
// aceita um caminho interno simples: nada de outra origem, nada de consulta ou fragmento (é por ali que um token vazaria).

export const HOME_PATH = '/'

// Telas de quem não tem sessão: voltar para elas depois de entrar seria um laço.
const PUBLIC_PATHS = new Set(['/entrar', '/cadastro'])

// Letras, números e . _ ~ - em segmentos separados por uma barra.
const SAFE_PATH = /^\/(?:[A-Za-z0-9._~-]+(?:\/[A-Za-z0-9._~-]+)*\/?)?$/

function pathnameOf(from: unknown): unknown {
  if (typeof from === 'string') return from
  if (typeof from === 'object' && from !== null && 'pathname' in from) return (from as { pathname: unknown }).pathname
  return undefined
}

/** O caminho para onde ir; qualquer coisa duvidosa vira o início. Só o caminho é mantido: consulta e fragmento são descartados. */
export function safeRedirect(from: unknown): string {
  const pathname = pathnameOf(from)
  if (typeof pathname !== 'string' || !SAFE_PATH.test(pathname)) return HOME_PATH
  if (pathname.split('/').some((segment) => segment === '..' || segment === '.')) return HOME_PATH
  const normalized = pathname.length > 1 && pathname.endsWith('/') ? pathname.slice(0, -1) : pathname
  return PUBLIC_PATHS.has(normalized) ? HOME_PATH : pathname
}
