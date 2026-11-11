import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { Me, WorkspaceMembership } from '../api/types'
import { workspaceKey } from '../storage/local'
import { WorkspaceProvider } from './WorkspaceContext'
import { useWorkspace } from './useWorkspace'

const casa: WorkspaceMembership = { id: 'w-casa', name: 'Casa', baseCurrency: 'BRL', role: 'OWNER' }
const viagem: WorkspaceMembership = { id: 'w-viagem', name: 'Viagem', baseCurrency: 'BRL', role: 'VIEWER' }
const ana = (workspaces: WorkspaceMembership[]): Me => ({ id: 'u-ana', name: 'Ana', email: 'ana@exemplo.com', workspaces })
const bia = (workspaces: WorkspaceMembership[]): Me => ({ id: 'u-bia', name: 'Bia', email: 'bia@exemplo.com', workspaces })

function Current() {
  const { current, select } = useWorkspace()
  return (
    <div>
      <p data-testid="current">{current ? `${current.name} (${current.role})` : 'nenhum'}</p>
      <button type="button" onClick={() => select('w-viagem')}>
        Escolher Viagem
      </button>
      <button type="button" onClick={() => select('w-que-nao-e-meu')}>
        Escolher alheio
      </button>
    </div>
  )
}

const mount = (me: Me) =>
  render(
    <WorkspaceProvider me={me}>
      <Current />
    </WorkspaceProvider>,
  )
const current = () => screen.getByTestId('current').textContent

describe('qual é o workspace atual', () => {
  it('sem escolha guardada, o primeiro da lista', () => {
    mount(ana([casa, viagem]))

    expect(current()).toBe('Casa (OWNER)')
  })

  it('com escolha guardada que ainda vale, a escolhida', () => {
    localStorage.setItem(workspaceKey('u-ana'), 'w-viagem')

    mount(ana([casa, viagem]))

    expect(current()).toBe('Viagem (VIEWER)')
  })

  it('com escolha guardada que não consta mais na lista, o primeiro', () => {
    localStorage.setItem(workspaceKey('u-ana'), 'w-que-saiu')

    mount(ana([casa, viagem]))

    expect(current()).toBe('Casa (OWNER)')
  })

  it('outra conta no mesmo aparelho não herda a escolha: vale o primeiro dela', () => {
    localStorage.setItem(workspaceKey('u-ana'), 'w-viagem')

    mount(bia([casa, viagem]))

    expect(current()).toBe('Casa (OWNER)')
  })

  it('sem nenhum workspace, não há atual', () => {
    mount(ana([]))

    expect(current()).toBe('nenhum')
  })
})

describe('trocar', () => {
  it('muda o atual e guarda só o identificador, na chave da pessoa', async () => {
    const user = userEvent.setup()
    mount(ana([casa, viagem]))

    await user.click(screen.getByRole('button', { name: 'Escolher Viagem' }))

    expect(current()).toBe('Viagem (VIEWER)')
    expect(localStorage.length).toBe(1)
    expect(localStorage.getItem('tostao.workspace.u-ana')).toBe('w-viagem')
  })

  it('a escolha sobrevive à recarga (um novo provider lê o que foi guardado)', async () => {
    const user = userEvent.setup()
    const first = mount(ana([casa, viagem]))
    await user.click(screen.getByRole('button', { name: 'Escolher Viagem' }))
    first.unmount()

    mount(ana([casa, viagem]))

    expect(current()).toBe('Viagem (VIEWER)')
  })

  it('um identificador que não é da pessoa é ignorado e nada é guardado', async () => {
    const user = userEvent.setup()
    mount(ana([casa, viagem]))

    await user.click(screen.getByRole('button', { name: 'Escolher alheio' }))

    expect(current()).toBe('Casa (OWNER)')
    expect(localStorage.length).toBe(0)
  })

  it('com o armazenamento bloqueado, o aplicativo segue funcionando só nesta visita', async () => {
    const user = userEvent.setup()
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('bloqueado', 'SecurityError')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('cheio', 'QuotaExceededError')
    })
    mount(ana([casa, viagem]))
    expect(current()).toBe('Casa (OWNER)')

    await user.click(screen.getByRole('button', { name: 'Escolher Viagem' }))

    expect(current()).toBe('Viagem (VIEWER)')
  })
})

describe('trocar de pessoa', () => {
  it('a escolha da anterior não vale para a seguinte, nem sem remontar o provider', async () => {
    const user = userEvent.setup()
    const view = mount(ana([casa, viagem]))
    await user.click(screen.getByRole('button', { name: 'Escolher Viagem' }))
    expect(current()).toBe('Viagem (VIEWER)')

    view.rerender(
      <WorkspaceProvider me={bia([casa, viagem])}>
        <Current />
      </WorkspaceProvider>,
    )

    expect(current()).toBe('Casa (OWNER)')
  })
})

describe('useWorkspace', () => {
  it('fora do WorkspaceProvider é um erro de uso, dito com clareza', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})

    expect(() => render(<Current />)).toThrow('useWorkspace precisa estar dentro de um WorkspaceProvider')
  })
})
