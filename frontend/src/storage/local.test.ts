import { afterEach, describe, expect, it, vi } from 'vitest'
import { THEME_KEY, getItem, removeItem, setItem, workspaceKey } from './local'

afterEach(() => {
  vi.restoreAllMocks()
})

describe('com o armazenamento disponível', () => {
  it('guarda, lê e remove', () => {
    expect(getItem('k')).toBeNull()
    expect(setItem('k', 'v')).toBe(true)
    expect(getItem('k')).toBe('v')
    removeItem('k')
    expect(getItem('k')).toBeNull()
  })

  it('as chaves seguem o data-model, e a do workspace é por pessoa', () => {
    expect(THEME_KEY).toBe('tostao.theme')
    expect(workspaceKey('abc')).toBe('tostao.workspace.abc')
    expect(workspaceKey('abc')).not.toBe(workspaceKey('def'))
  })
})

describe('com o armazenamento bloqueado ou cheio, nunca lança', () => {
  it('a leitura devolve null', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('bloqueado', 'SecurityError')
    })

    expect(getItem('k')).toBeNull()
  })

  it('a escrita devolve false (espaço cheio)', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('cheio', 'QuotaExceededError')
    })

    expect(setItem('k', 'v')).toBe(false)
  })

  it('a remoção é silenciosa', () => {
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new DOMException('bloqueado', 'SecurityError')
    })

    expect(() => removeItem('k')).not.toThrow()
  })

  it('o próprio localStorage inacessível (o acesso à propriedade lança) também não derruba', () => {
    vi.spyOn(window, 'localStorage', 'get').mockImplementation(() => {
      throw new DOMException('bloqueado', 'SecurityError')
    })

    expect(getItem('k')).toBeNull()
    expect(setItem('k', 'v')).toBe(false)
    expect(() => removeItem('k')).not.toThrow()
  })
})
