import { fireEvent, render, screen } from '@testing-library/react'
import { StrictMode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { SheetDialog } from './SheetDialog'

// O que o navegador de verdade faz e o jsdom não: no modo de desenvolvimento o React monta, desmonta e monta de novo, e o `close` do
// <dialog> chega depois. A folha tem de continuar aberta e só fechar quando a pessoa pede (Esc, "Fechar" ou o fundo escurecido).
describe('SheetDialog', () => {
  it('no modo estrito continua aberta, mesmo com o `close` atrasado do <dialog>; o Esc e o "Fechar" pedem para fechar', () => {
    const onClose = vi.fn()
    render(
      <StrictMode>
        <SheetDialog title="Novo limite" onClose={onClose}>
          <p>corpo</p>
        </SheetDialog>
      </StrictMode>,
    )
    const dialog = document.querySelector('dialog') as HTMLDialogElement
    expect(dialog).toHaveAttribute('open')

    fireEvent(dialog, new Event('close')) // o evento atrasado da desmontagem do modo estrito
    expect(onClose).not.toHaveBeenCalled()
    expect(dialog).toHaveAttribute('open')

    const esc = new Event('cancel', { cancelable: true })
    fireEvent(dialog, esc)
    expect(onClose).toHaveBeenCalledTimes(1)
    expect(esc.defaultPrevented).toBe(true) // quem fecha é o pai

    fireEvent.click(screen.getByRole('button', { name: 'Fechar' }))
    expect(onClose).toHaveBeenCalledTimes(2)
  })

  it('o toque no fundo escurecido (o próprio <dialog>) fecha; o toque dentro da folha não', () => {
    const onClose = vi.fn()
    render(
      <SheetDialog title="Filtros" onClose={onClose}>
        <p>corpo</p>
      </SheetDialog>,
    )

    fireEvent.click(screen.getByText('corpo'))
    expect(onClose).not.toHaveBeenCalled()
    fireEvent.click(document.querySelector('dialog') as HTMLDialogElement)
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})
