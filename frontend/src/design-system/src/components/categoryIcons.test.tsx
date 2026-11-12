import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { expectNoSeriousViolations } from '../../../test/axe'
import { CategoryCoin } from './CategoryCoin'
import { CATEGORY_ICON_ORDER, inkOn, isCategoryIcon, suggestCategoryIcon, suggestCategoryIcons } from './categoryIcons'
import { IconPicker } from './IconPicker'
import { SwatchPicker } from './SwatchPicker'

// As chaves que a API aceita (o servidor recusa qualquer outra): a mesma lista, nesta ordem.
const API_KEYS = 'bowl, cart, utensils, coffee, glass, house, key, bolt, drop, wifi, phone, wrench, bus, car, fuel, plane, ticket, game, play, music, dumbbell, heart, pill, scissors, paw, shield, bag, shirt, gift, book, cap, briefcase, laptop, receipt, percent, sprout, undo, star, box'.split(', ')

describe('os ícones de categoria', () => {
  it('são 39, com as chaves que a API aceita', () => {
    expect(CATEGORY_ICON_ORDER).toHaveLength(39)
    expect(CATEGORY_ICON_ORDER).toEqual(API_KEYS)
    expect(isCategoryIcon('cart')).toBe(true)
    expect(isCategoryIcon('qualquercoisa')).toBe(false)
    expect(isCategoryIcon(null)).toBe(false)
  })

  it('o nome sugere o ícone (sem acento, o mais específico primeiro) e sem pista não sugere nada', () => {
    expect(suggestCategoryIcon('Mercado')).toBe('cart')
    expect(suggestCategoryIcon('Academia')).toBe('dumbbell')
    expect(suggestCategoryIcon('Farmácia')).toBe('pill')
    expect(suggestCategoryIcon('Cuidados pessoais')).toBe('scissors')
    expect(suggestCategoryIcons('Saúde e farmácia', 3)).toEqual(['pill', 'heart'])
    expect(suggestCategoryIcon('Xodó')).toBeNull()
    expect(suggestCategoryIcon('ab')).toBeNull()
  })

  it('o texto da moeda é escuro sobre cor clara e branco sobre cor escura', () => {
    expect(inkOn('#e0a93a')).toBe('#08201f')
    expect(inkOn('#1a1a40')).toBe('#ffffff')
  })
})

describe('CategoryCoin', () => {
  it('cheia na cor, com o ícone gravado; decorativa', () => {
    const { container } = render(<CategoryCoin name="Lazer" color="#ff8a78" icon="ticket" />)
    const coin = container.firstElementChild as HTMLElement

    expect(coin).toHaveAttribute('aria-hidden', 'true')
    expect(coin.style.background).toBe('rgb(255, 138, 120)')
    expect(coin.querySelector('svg')).not.toBeNull()
    expect(coin).not.toHaveClass('tx-coin--outline')
  })

  it('vazada (subcategoria): só o contorno na cor da mãe', () => {
    const { container } = render(<CategoryCoin name="Viagens" color="#ff8a78" icon="plane" variant="outline" />)
    const coin = container.firstElementChild as HTMLElement

    expect(coin).toHaveClass('tx-coin--outline')
    expect(coin.style.borderColor).toBe('rgb(255, 138, 120)')
    expect(coin.style.background).toBe('')
  })

  it('sem ícone gravado sugere pelo nome; sem sugestão mostra a inicial; `false` força a inicial', () => {
    const suggested = render(<CategoryCoin name="Mercado" icon={null} />).container
    expect(suggested.querySelector('svg')).not.toBeNull()
    const initial = render(<CategoryCoin name="Xodó" icon={null} />).container
    expect(initial.querySelector('svg')).toBeNull()
    expect(initial).toHaveTextContent('X')
    const forced = render(<CategoryCoin name="Mercado" icon={false} />).container
    expect(forced.querySelector('svg')).toBeNull()
    expect(forced).toHaveTextContent('M')
  })
})

describe('IconPicker e SwatchPicker', () => {
  it('escolher um ícone, o "sem ícone" e buscar pelo nome; as sugestões acompanham o nome', async () => {
    const onChange = vi.fn()
    const user = userEvent.setup()
    const { container } = render(<IconPicker name="Academia" value="dumbbell" color="#4fb3b1" onChange={onChange} />)

    expect(screen.getAllByRole('radio', { name: 'Academia' })[0]).toBeChecked()
    await user.click(screen.getByRole('radio', { name: 'Mercado' }))
    expect(onChange).toHaveBeenLastCalledWith('cart')
    await user.click(screen.getByRole('radio', { name: 'Sem ícone, usar a inicial' }))
    expect(onChange).toHaveBeenLastCalledWith(false)

    await user.type(screen.getByRole('searchbox', { name: 'Buscar ícone' }), 'farm')
    expect(screen.getByRole('radio', { name: 'Farmácia' })).toBeInTheDocument()
    expect(screen.queryByRole('radio', { name: 'Mercado' })).toBeNull()
    await user.clear(screen.getByRole('searchbox', { name: 'Buscar ícone' }))
    await user.type(screen.getByRole('searchbox', { name: 'Buscar ícone' }), 'zzzz')
    expect(screen.getByText(/Nenhum ícone com esse nome/)).toBeInTheDocument()
    await expectNoSeriousViolations(container)
  })

  it('SwatchPicker: as oito cores, a escolhida marcada', async () => {
    const onChange = vi.fn()
    const user = userEvent.setup()
    render(<SwatchPicker value="#e0a93a" onChange={onChange} />)

    expect(screen.getAllByRole('radio')).toHaveLength(8)
    expect(screen.getByRole('radio', { name: 'Cor 2' })).toBeChecked()
    await user.click(screen.getByRole('radio', { name: 'Cor 4' }))
    expect(onChange).toHaveBeenCalledWith('#ff8a78')
  })
})
