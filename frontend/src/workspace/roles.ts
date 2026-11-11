import type { Role } from '../api/types'

const LABELS: Record<Role, string> = { OWNER: 'Dono', EDITOR: 'Editor', VIEWER: 'Leitor' }

/** O papel da pessoa em um workspace, em português. Um papel que o aplicativo não conhece aparece como veio (nunca em branco). */
export function roleLabel(role: Role): string {
  return LABELS[role] ?? String(role)
}
