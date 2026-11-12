import type { CopyBudgetsResult } from '../api/types'

/** O resumo do que a cópia fez, em uma frase. */
export function copySummary(result: CopyBudgetsResult): string {
  const parts = [`${result.created} ${result.created === 1 ? 'limite criado' : 'limites criados'}`]
  if (result.skippedExisting > 0) parts.push(`${result.skippedExisting} já ${result.skippedExisting === 1 ? 'existia' : 'existiam'}`)
  if (result.skippedArchivedCategory > 0) parts.push(`${result.skippedArchivedCategory} ignorado${result.skippedArchivedCategory === 1 ? '' : 's'} por categoria arquivada`)
  return `${parts.join(', ')}.`
}
