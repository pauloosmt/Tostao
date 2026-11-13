import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { addDays, addMonths, brToIso, isoToBr, maskBr, monthGrid } from '../dates/calendar'
import { expectNoSeriousViolations } from '../test/axe'
import { DateField } from './DateField'

function Harness({ initial = '', hint }: { initial?: string; hint?: string }) {
  const [value, setValue] = useState(initial)
  return (
    <>
      <DateField label="Data" value={value} onChange={setValue} hint={hint} />
      <output data-testid="iso">{value}</output>
    </>
  )
}
const input = () => screen.getByLabelText('Data')
const iso = () => screen.getByTestId('iso').textContent
const open = () => screen.getByRole('button', { name: 'Abrir calendário de Data' })

afterEach(() => vi.useRealTimers())

describe('as datas do calendário', () => {
  it('máscara, conversão e conta de dias e meses sem fuso', () => {
    expect(maskBr('13112026')).toBe('13/11/2026')
    expect(maskBr('13ab/1')).toBe('13/1')
    expect(brToIso('13/11/2026')).toBe('2026-11-13')
    expect(brToIso('31/02/2026')).toBeNull()
    expect(isoToBr('2026-11-13')).toBe('13/11/2026')
    expect(addDays('2026-12-31', 1)).toBe('2027-01-01')
    expect(addMonths('2026-01-31', 1)).toBe('2026-02-28')
    // seis semanas de domingo a sábado; novembro de 2026 começa num domingo
    const grid = monthGrid(2026, 10)
    expect(grid).toHaveLength(6)
    expect(grid.every((week) => week.length === 7)).toBe(true)
    expect(grid[0][0]).toMatchObject({ iso: '2026-11-01', inMonth: true })
    expect(grid[5][6]).toMatchObject({ iso: '2026-12-12', inMonth: false })
  })
})

describe('DateField', () => {
  it('digitar: só números contam, as barras entram sozinhas e a data completa chega em AAAA-MM-DD', async () => {
    const user = userEvent.setup()
    render(<Harness />)

    await user.type(input(), '13a/b11x2026')

    expect(input()).toHaveValue('13/11/2026')
    expect(iso()).toBe('2026-11-13')
    await user.clear(input())
    await user.type(input(), '1311')
    expect(iso()).toBe('') // incompleta não vale
  })

  it('uma data impossível avisa ao sair do campo e entrega vazio', async () => {
    const user = userEvent.setup()
    render(<Harness />)

    await user.type(input(), '31022026')
    await user.tab()

    expect(iso()).toBe('')
    expect(await screen.findByText('Informe uma data válida, como 13/11/2026.')).toBeInTheDocument()
  })

  it('aceita também AAAA-MM-DD (colar) e mostra no formato do Brasil', () => {
    render(<Harness />)

    fireEvent.change(input(), { target: { value: '2026-11-05' } })

    expect(input()).toHaveValue('05/11/2026')
    expect(iso()).toBe('2026-11-05')
  })

  it('o calendário abre no mês da data, marca o dia escolhido e escolher fecha e preenche', async () => {
    const user = userEvent.setup()
    render(<Harness initial="2026-11-13" />)

    await user.click(open())
    const calendar = screen.getByRole('dialog', { name: 'Calendário de Data' })
    expect(within(calendar).getByText('novembro de 2026')).toBeInTheDocument()
    expect(within(calendar).getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['D', 'S', 'T', 'Q', 'Q', 'S', 'S'])
    expect(within(calendar).getByRole('button', { name: '13 de novembro de 2026' })).toHaveAttribute('aria-pressed', 'true')

    await user.click(within(calendar).getByRole('button', { name: '20 de novembro de 2026' }))

    expect(screen.queryByRole('dialog', { name: 'Calendário de Data' })).toBeNull()
    expect(iso()).toBe('2026-11-20')
    expect(input()).toHaveValue('20/11/2026')
  })

  it('navega entre meses, e "Hoje" e "Limpar" preenchem e esvaziam', async () => {
    vi.useFakeTimers({ toFake: ['Date'], now: new Date(2026, 10, 13, 12) })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    render(<Harness initial="2026-11-13" />)

    await user.click(open())
    await user.click(screen.getByRole('button', { name: 'Próximo mês' }))
    expect(screen.getByText('dezembro de 2026')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Mês anterior' }))
    await user.click(screen.getByRole('button', { name: 'Mês anterior' }))
    expect(screen.getByText('outubro de 2026')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Limpar' }))
    expect(iso()).toBe('')

    await user.click(open())
    expect(screen.getByRole('button', { name: '13 de novembro de 2026' })).toHaveAttribute('aria-current', 'date')
    await user.click(screen.getByRole('button', { name: 'Hoje' }))
    expect(iso()).toBe('2026-11-13')
  })

  it('pelo teclado: setas movem o dia, PageDown o mês, Esc fecha só o calendário e devolve o foco', async () => {
    const user = userEvent.setup()
    const onEscape = vi.fn()
    render(
      <div onKeyDown={onEscape}>
        <Harness initial="2026-11-13" />
      </div>,
    )

    await user.click(open())
    expect(screen.getByRole('button', { name: '13 de novembro de 2026' })).toHaveFocus()
    await user.keyboard('{ArrowRight}')
    expect(screen.getByRole('button', { name: '14 de novembro de 2026' })).toHaveFocus()
    await user.keyboard('{ArrowDown}')
    expect(screen.getByRole('button', { name: '21 de novembro de 2026' })).toHaveFocus()
    await user.keyboard('{PageDown}')
    expect(screen.getByText('dezembro de 2026')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '21 de dezembro de 2026' })).toHaveFocus()
    await user.keyboard('{Enter}')
    expect(iso()).toBe('2026-12-21')

    await user.click(open())
    onEscape.mockClear()
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog', { name: 'Calendário de Data' })).toBeNull()
    expect(open()).toHaveFocus()
    expect(onEscape).not.toHaveBeenCalled() // o Esc não sobe: a folha em volta não fecha
  })

  it('tocar fora fecha o calendário; a dica aparece sob o campo', async () => {
    const user = userEvent.setup()
    render(
      <>
        <Harness hint="Vazio: sem data." />
        <button type="button">fora</button>
      </>,
    )

    expect(screen.getByText('Vazio: sem data.')).toBeInTheDocument()
    await user.click(open())
    await user.click(screen.getByRole('button', { name: 'fora' }))
    expect(screen.queryByRole('dialog', { name: 'Calendário de Data' })).toBeNull()
  })

  it('sem violações de acessibilidade, com o calendário aberto', async () => {
    const user = userEvent.setup()
    const { container } = render(<Harness initial="2026-11-13" />)
    await user.click(open())

    await expectNoSeriousViolations(container)
  })
})

describe('responsividade do calendário', () => {
  function openAt(width: number, fieldLeft: number, fieldWidth: number) {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: width })
    const rectOf = () => ({ left: fieldLeft, right: fieldLeft + fieldWidth, top: 0, bottom: 40, width: fieldWidth, height: 40, x: fieldLeft, y: 0, toJSON: () => ({}) })
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(rectOf)
    render(<Harness initial="2026-11-13" />)
    fireEvent.click(open())
    return screen.getByRole('dialog', { name: 'Calendário de Data' })
  }

  afterEach(() => vi.restoreAllMocks())

  it.each([
    ['celular estreito (320)', 320, 16, 288],
    ['celular (390)', 390, 16, 358],
    ['tablet (768)', 768, 100, 568],
    ['desktop (1280)', 1280, 300, 400],
  ])('em %s o calendário mostra as 6 semanas e o mês inteiro', (_name, width, left, fieldWidth) => {
    const calendar = openAt(width, left, fieldWidth)

    expect(within(calendar).getAllByRole('row')).toHaveLength(7) // cabeçalho + 6 semanas
    expect(within(calendar).getAllByRole('button', { name: /de novembro de 2026$/ })).toHaveLength(30)
  })

  it('alinha à direita só quando não cabe à direita do campo (campo da direita em tela estreita)', () => {
    const narrow = openAt(360, 190, 154) // campo "Até" ao lado de "De": 190 + 296 passa de 344
    expect(narrow).toHaveClass('tx-cal--right')
  })

  it('fica à esquerda quando cabe', () => {
    const wide = openAt(1280, 100, 300)
    expect(wide).not.toHaveClass('tx-cal--right')
  })
})
