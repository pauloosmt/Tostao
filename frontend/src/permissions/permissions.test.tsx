import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import type { Me, Role } from '../api/types'
import { expectNoSeriousViolations } from '../test/axe'
import { WorkspaceProvider } from '../workspace/WorkspaceContext'
import { usePermissions } from './usePermissions'
import { WriteGate } from './WriteGate'

const meWith = (role: Role | null): Me => ({
  id: 'u1',
  name: 'Ana',
  email: 'ana@exemplo.com',
  workspaces: role ? [{ id: 'w1', name: 'Casa', baseCurrency: 'BRL', role }] : [],
})

function Probe() {
  const { role, canWrite } = usePermissions()
  return (
    <p data-testid="result">
      {String(role)}:{String(canWrite)}
    </p>
  )
}

const withRole = (role: Role | null, ui: React.ReactElement) =>
  render(
    <MemoryRouter>
      <WorkspaceProvider me={meWith(role)}>{ui}</WorkspaceProvider>
    </MemoryRouter>,
  )

describe('usePermissions', () => {
  it.each([
    ['OWNER', 'OWNER:true'],
    ['EDITOR', 'EDITOR:true'],
    ['VIEWER', 'VIEWER:false'],
    [null, 'null:false'],
  ] as const)('o papel %s -> %s', (role, expected) => {
    withRole(role, <Probe />)

    expect(screen.getByTestId('result')).toHaveTextContent(expected)
  })
})

describe('WriteGate', () => {
  const form = <p>Formulário de criação</p>

  it('dono e editor veem o formulário', () => {
    for (const role of ['OWNER', 'EDITOR'] as const) {
      const view = withRole(
        role,
        <WriteGate backTo="/contas" backLabel="Voltar às contas">
          {form}
        </WriteGate>,
      )

      expect(screen.getByText('Formulário de criação')).toBeInTheDocument()
      expect(screen.queryByText('Somente consulta')).toBeNull()
      view.unmount()
    }
  })

  it('o leitor vê o aviso de papel e o caminho de volta, nunca o formulário', async () => {
    const { container } = withRole(
      'VIEWER',
      <WriteGate backTo="/contas" backLabel="Voltar às contas">
        {form}
      </WriteGate>,
    )

    expect(screen.queryByText('Formulário de criação')).toBeNull()
    expect(screen.getByRole('heading', { name: 'Somente consulta' })).toBeInTheDocument()
    expect(screen.getByText(/papel neste workspace é leitor/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Voltar às contas' })).toHaveAttribute('href', '/contas')
    await expectNoSeriousViolations(container)
  })

  it('sem nenhum workspace também não mostra o formulário', () => {
    withRole(
      null,
      <WriteGate backTo="/categorias" backLabel="Voltar às categorias">
        {form}
      </WriteGate>,
    )

    expect(screen.queryByText('Formulário de criação')).toBeNull()
    expect(screen.getByText('Você não participa de nenhum workspace.')).toBeInTheDocument()
  })
})
