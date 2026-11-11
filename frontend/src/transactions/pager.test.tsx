import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { expectNoSeriousViolations } from '../test/axe'
import { Pager } from './Pager'

describe('Pager', () => {
  it('mostra a página atual e o total, e leva à anterior e à próxima', async () => {
    const user = userEvent.setup()
    const onPage = vi.fn()
    render(<Pager page={2} totalPages={5} onPage={onPage} />)

    expect(screen.getByText('Página 2 de 5')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Anterior' }))
    await user.click(screen.getByRole('button', { name: 'Próxima' }))

    expect(onPage.mock.calls).toEqual([[1], [3]])
  })

  it('na primeira página "Anterior" fica indisponível', () => {
    render(<Pager page={1} totalPages={3} onPage={() => {}} />)

    expect(screen.getByRole('button', { name: 'Anterior' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Próxima' })).toBeEnabled()
  })

  it('na última página "Próxima" fica indisponível', () => {
    render(<Pager page={3} totalPages={3} onPage={() => {}} />)

    expect(screen.getByRole('button', { name: 'Próxima' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Anterior' })).toBeEnabled()
  })

  it('uma página só (ou nenhuma) não desenha controles', () => {
    const { container, rerender } = render(<Pager page={1} totalPages={1} onPage={() => {}} />)
    expect(container).toBeEmptyDOMElement()

    rerender(<Pager page={1} totalPages={0} onPage={() => {}} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('enquanto carrega, os dois botões ficam indisponíveis (sem pedir duas páginas ao mesmo tempo)', () => {
    render(<Pager page={2} totalPages={5} onPage={() => {}} busy />)

    expect(screen.getByRole('button', { name: 'Anterior' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Próxima' })).toBeDisabled()
  })

  it('é uma navegação nomeada, a mudança de página é anunciada e passa no axe', async () => {
    const { container } = render(<Pager page={2} totalPages={5} onPage={() => {}} />)

    expect(screen.getByRole('navigation', { name: 'Páginas de lançamentos' })).toBeInTheDocument()
    expect(screen.getByText('Página 2 de 5')).toHaveAttribute('aria-live', 'polite')
    await expectNoSeriousViolations(container)
  })

  it('funciona pelo teclado', async () => {
    const user = userEvent.setup()
    const onPage = vi.fn()
    render(<Pager page={2} totalPages={5} onPage={onPage} />)

    await user.tab()
    expect(screen.getByRole('button', { name: 'Anterior' })).toHaveFocus()
    await user.tab()
    await user.keyboard('{Enter}')

    expect(onPage).toHaveBeenCalledWith(3)
  })
})
