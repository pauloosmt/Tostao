import axe from 'axe-core'

/**
 * Falha, listando as regras, se o axe achar violação séria ou crítica no trecho. O contraste fica de fora porque o jsdom não
 * calcula cores nem layout: ele é garantido pelos tokens do design system.
 */
export async function expectNoSeriousViolations(container: Element): Promise<void> {
  const results = await axe.run(container, {
    rules: { 'color-contrast': { enabled: false } },
    resultTypes: ['violations'],
  })
  const serious = results.violations
    .filter((violation) => violation.impact === 'serious' || violation.impact === 'critical')
    .map((violation) => `${violation.id}: ${violation.help} -> ${violation.nodes.map((node) => node.target.join(' ')).join(' | ')}`)
  if (serious.length > 0) {
    throw new Error(`Violações de acessibilidade (séria ou crítica):\n${serious.join('\n')}`)
  }
}
