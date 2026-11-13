// O arquivo do extrato: o que se confere antes de enviar (a API confere de novo e é a autoridade). Puro.
export const MAX_IMPORT_BYTES = 2 * 1024 * 1024

export const IMPORT_FILE_MESSAGES = {
  none: 'Escolha o arquivo do extrato.',
  empty: 'O arquivo está vazio.',
  tooBig: 'O arquivo passa de 2 MB.',
} as const

/** A mensagem do problema do arquivo, ou `undefined` se está tudo certo para enviar. */
export function validateImportFile(file: { size: number } | null | undefined): string | undefined {
  if (!file) return IMPORT_FILE_MESSAGES.none
  if (file.size === 0) return IMPORT_FILE_MESSAGES.empty
  if (file.size > MAX_IMPORT_BYTES) return IMPORT_FILE_MESSAGES.tooBig
  return undefined
}
