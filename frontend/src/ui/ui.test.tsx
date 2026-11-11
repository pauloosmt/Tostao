import { act, render, screen } from '@testing-library/react'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { expectNoSeriousViolations } from '../test/axe'
import { renderWithUser } from '../test/render'
import { ConfirmDialog } from './ConfirmDialog'
import { ErrorNotice } from './ErrorNotice'
import { LoadingScreen } from './LoadingScreen'
import { PasswordField } from './PasswordField'

describe('LoadingScreen', () => {
  it('é anunciada como um estado, com o texto de espera', async () => {
    const { container } = render(<LoadingScreen />)

    expect(screen.getByRole('status')).toHaveTextContent('Carregando…')
    await expectNoSeriousViolations(container)
  })

  it('aceita outro texto', () => {
    render(<LoadingScreen label="Entrando…" />)

    expect(screen.getByRole('status')).toHaveTextContent('Entrando…')
  })
})

describe('ErrorNotice', () => {
  it('anuncia o erro na hora (role alert) com o título e a mensagem', async () => {
    const { container } = render(<ErrorNotice title="Não foi possível entrar">Tente de novo.</ErrorNotice>)

    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent('Não foi possível entrar')
    expect(alert).toHaveTextContent('Tente de novo.')
    expect(screen.queryByRole('button')).toBeNull()
    await expectNoSeriousViolations(container)
  })

  it('com onRetry oferece "Tentar de novo", que chama a função', async () => {
    const onRetry = vi.fn()
    const { user } = renderWithUser(<ErrorNotice onRetry={onRetry}>Sem conexão.</ErrorNotice>)

    await user.click(screen.getByRole('button', { name: 'Tentar de novo' }))

    expect(onRetry).toHaveBeenCalledTimes(1)
  })

  it('o texto do botão pode ser outro', () => {
    render(
      <ErrorNotice onRetry={() => {}} retryLabel="Recarregar">
        Falhou.
      </ErrorNotice>,
    )

    expect(screen.getByRole('button', { name: 'Recarregar' })).toBeInTheDocument()
  })
})

describe('PasswordField', () => {
  it('começa escondida, mostra e esconde, e o botão informa o estado', async () => {
    const { user } = renderWithUser(<PasswordField label="Senha" autoComplete="current-password" defaultValue="Segredo-123" />)
    const input = screen.getByLabelText('Senha')
    const toggle = screen.getByRole('button', { name: 'Mostrar senha' })

    expect(input).toHaveAttribute('type', 'password')
    expect(toggle).toHaveAttribute('aria-pressed', 'false')

    await user.click(toggle)
    expect(input).toHaveAttribute('type', 'text')
    expect(screen.getByRole('button', { name: 'Mostrar senha' })).toHaveAttribute('aria-pressed', 'true')

    await user.click(toggle)
    expect(input).toHaveAttribute('type', 'password')
    expect(input).toHaveValue('Segredo-123')
  })

  it('leva o autocomplete certo e não corrige nem sugere a senha', () => {
    const { rerender } = render(<PasswordField label="Senha" autoComplete="current-password" />)
    const input = screen.getByLabelText('Senha')
    expect(input).toHaveAttribute('autocomplete', 'current-password')
    expect(input).toHaveAttribute('autocapitalize', 'off')
    expect(input).toHaveAttribute('spellcheck', 'false')

    rerender(<PasswordField label="Senha" autoComplete="new-password" />)
    expect(screen.getByLabelText('Senha')).toHaveAttribute('autocomplete', 'new-password')
  })

  it('mostra o erro no campo, ligado a ele, e passa no axe', async () => {
    const { container } = render(<PasswordField label="Senha" autoComplete="new-password" error="Informe uma senha." />)

    expect(screen.getByLabelText('Senha')).toHaveAccessibleDescription('Informe uma senha.')
    expect(screen.getByLabelText('Senha')).toBeInvalid()
    await expectNoSeriousViolations(container)
  })
})

function Harness({ onConfirm = () => {}, busy = false }: { onConfirm?: () => void; busy?: boolean }) {
  const [open, setOpen] = useState(false)
  const [cancelled, setCancelled] = useState(0)
  return (
    <div>
      <button type="button" onClick={() => setOpen(true)}>
        Abrir
      </button>
      <p data-testid="cancelled">{cancelled}</p>
      <ConfirmDialog
        open={open}
        title="Sair de todos os aparelhos?"
        confirmLabel="Sair de todos"
        busy={busy}
        onConfirm={() => {
          onConfirm()
          setOpen(false)
        }}
        onCancel={() => {
          setCancelled((n) => n + 1)
          setOpen(false)
        }}
      >
        Você terá de entrar de novo em cada aparelho.
      </ConfirmDialog>
    </div>
  )
}

describe('ConfirmDialog', () => {
  it('fechado, não está na tela; aberto, é um diálogo com título e texto', async () => {
    const { user, container } = renderWithUser(<Harness />)
    expect(screen.queryByRole('dialog')).toBeNull()

    await user.click(screen.getByRole('button', { name: 'Abrir' }))

    const dialog = screen.getByRole('dialog', { name: 'Sair de todos os aparelhos?' })
    expect(dialog).toHaveAccessibleDescription('Você terá de entrar de novo em cada aparelho.')
    await expectNoSeriousViolations(container)
  })

  it('o foco começa em "Cancelar", o caminho seguro', async () => {
    const { user } = renderWithUser(<Harness />)

    await user.click(screen.getByRole('button', { name: 'Abrir' }))

    expect(screen.getByRole('button', { name: 'Cancelar' })).toHaveFocus()
  })

  it('confirmar chama onConfirm e fecha', async () => {
    const onConfirm = vi.fn()
    const { user } = renderWithUser(<Harness onConfirm={onConfirm} />)
    await user.click(screen.getByRole('button', { name: 'Abrir' }))

    await user.click(screen.getByRole('button', { name: 'Sair de todos' }))

    expect(onConfirm).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(screen.getByTestId('cancelled')).toHaveTextContent('0')
  })

  it('cancelar fecha sem confirmar', async () => {
    const onConfirm = vi.fn()
    const { user } = renderWithUser(<Harness onConfirm={onConfirm} />)
    await user.click(screen.getByRole('button', { name: 'Abrir' }))

    await user.click(screen.getByRole('button', { name: 'Cancelar' }))

    expect(onConfirm).not.toHaveBeenCalled()
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(screen.getByTestId('cancelled')).toHaveTextContent('1')
  })

  it('Esc (o navegador fecha o <dialog>) conta como cancelar, uma vez só', async () => {
    const { user } = renderWithUser(<Harness />)
    await user.click(screen.getByRole('button', { name: 'Abrir' }))

    // No navegador, Esc faz o <dialog> se fechar sozinho e disparar `close`; o jsdom não tem essa tecla, então fechamos o elemento.
    act(() => (screen.getByRole('dialog') as HTMLDialogElement).close())

    expect(screen.queryByRole('dialog')).toBeNull()
    expect(screen.getByTestId('cancelled')).toHaveTextContent('1')
  })

  it('durante a ação os botões ficam indisponíveis e o Esc é barrado', async () => {
    const { user } = renderWithUser(<Harness busy />)
    await user.click(screen.getByRole('button', { name: 'Abrir' }))

    expect(screen.getByRole('button', { name: 'Cancelar' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Sair de todos' })).toBeDisabled()

    const escape = new Event('cancel', { cancelable: true })
    screen.getByRole('dialog').dispatchEvent(escape)
    expect(escape.defaultPrevented).toBe(true)
  })

  it('sem ação em andamento o Esc (evento cancel) não é barrado', async () => {
    const { user } = renderWithUser(<Harness />)
    await user.click(screen.getByRole('button', { name: 'Abrir' }))

    const escape = new Event('cancel', { cancelable: true })
    screen.getByRole('dialog').dispatchEvent(escape)

    expect(escape.defaultPrevented).toBe(false)
  })
})

describe('ConfirmDialog: o tom da confirmação', () => {
  const open = (tone?: 'danger' | 'primary') =>
    render(
      <ConfirmDialog open title="Arquivar?" confirmLabel="Arquivar" tone={tone} onConfirm={() => {}} onCancel={() => {}}>
        A conta sai da lista.
      </ConfirmDialog>,
    )

  it('por padrão a confirmação é de perigo (o que não se desfaz)', () => {
    open()

    expect(screen.getByRole('button', { name: 'Arquivar' })).toHaveClass('tx-btn--danger')
  })

  it('com tom primário, a confirmação não parece destrutiva', () => {
    open('primary')

    expect(screen.getByRole('button', { name: 'Arquivar' })).toHaveClass('tx-btn--primary')
    expect(screen.getByRole('button', { name: 'Arquivar' })).not.toHaveClass('tx-btn--danger')
    expect(screen.getByRole('button', { name: 'Cancelar' })).toHaveClass('tx-btn--secondary')
  })
})
