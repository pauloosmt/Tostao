// Os textos do painel do mês: o fecho da Carteira e a variação sobre o mês anterior. Puro.
import type { DashboardChange } from '../api/types'
import { formatBRL } from '../design-system/src'
import { monthWord } from '../dates/months'

/** O fecho dourado: "Sobram R$ 1.723,60 em novembro", "Faltam R$ 200,00 em novembro" ou o mês no zero. */
export function claspText(resultCents: number, month: string): string {
  const word = monthWord(month)
  if (resultCents === 0) return `Receitas e despesas empatam em ${word}`
  return resultCents > 0 ? `Sobram ${formatBRL(resultCents)} em ${word}` : `Faltam ${formatBRL(-resultCents)} em ${word}`
}

/** "12,5" (no máximo uma casa, vírgula, sem zero sobrando). */
export function percentText(percent: number): string {
  return (Math.round(percent * 10) / 10).toString().replace('.', ',')
}

/** A variação sobre o mês anterior: "12,5% a mais que em outubro", "igual a outubro"; sem percentual (anterior zero), nada. */
export function changeText(change: DashboardChange, previousMonth: string): string | undefined {
  if (change.changePercent === null) return undefined
  const word = monthWord(previousMonth)
  if (change.changeCents === 0) return `Igual a ${word}`
  const percent = percentText(Math.abs(change.changePercent))
  return change.changeCents > 0 ? `${percent}% a mais que em ${word}` : `${percent}% a menos que em ${word}`
}
