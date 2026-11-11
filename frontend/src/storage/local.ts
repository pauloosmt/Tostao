// O localStorage, sem nunca lançar. Em janela anônima, com dados do site bloqueados ou com o espaço cheio, o acesso lança
// (ou o próprio `localStorage` é inacessível): o aplicativo segue funcionando, só deixa de lembrar da escolha.

/** Lê um valor guardado; `null` se não há, ou se o armazenamento não está disponível. */
export function getItem(key: string): string | null {
  try {
    return window.localStorage.getItem(key)
  } catch {
    return null
  }
}

/** Guarda um valor; devolve se conseguiu (quem chama pode ignorar: lembrar é uma conveniência). */
export function setItem(key: string, value: string): boolean {
  try {
    window.localStorage.setItem(key, value)
    return true
  } catch {
    return false
  }
}

export function removeItem(key: string): void {
  try {
    window.localStorage.removeItem(key)
  } catch {
    // sem armazenamento, não há o que remover
  }
}

// As chaves usadas pelo aplicativo, num lugar só (ver data-model.md).
export const THEME_KEY = 'tostao.theme'
export const workspaceKey = (userId: string): string => `tostao.workspace.${userId}`
