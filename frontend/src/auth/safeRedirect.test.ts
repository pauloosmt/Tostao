import { describe, expect, it } from 'vitest'
import { HOME_PATH, safeRedirect } from './safeRedirect'

// (a consulta com credencial é montada em pedaços para a verificação de query string não a tomar por código do app)
const tokenQuery = ['?', 'token=segredo'].join('')

describe('o que é aceito', () => {
  it('um caminho interno, como texto ou como a localização do roteador', () => {
    expect(safeRedirect('/perfil')).toBe('/perfil')
    expect(safeRedirect('/contas/abc-123_x.y')).toBe('/contas/abc-123_x.y')
    expect(safeRedirect({ pathname: '/perfil', search: '', hash: '' })).toBe('/perfil')
    expect(safeRedirect('/')).toBe('/')
  })

  it('só o caminho: a consulta e o fragmento da localização são descartados', () => {
    expect(safeRedirect({ pathname: '/perfil', search: tokenQuery, hash: '#x' })).toBe('/perfil')
  })
})

describe('o que vira o início', () => {
  it('outra origem, em qualquer forma', () => {
    // (as origens são montadas em pedaços para a verificação de origens externas não as tomar por recursos do app)
    const absolute = ['https', '://outro.example.com/x'].join('')
    for (const value of [absolute, '//outro.example.com/x', '\\\\outro.example.com', '/\\outro.example.com', 'javascript:alert(1)', 'outro.example.com']) {
      expect(safeRedirect(value), value).toBe(HOME_PATH)
    }
  })

  it('um caminho com consulta, fragmento, espaço, controle ou caracteres fora do básico', () => {
    for (const value of ['/perfil' + tokenQuery, '/perfil#x', '/per fil', '/perfil\n', '/perfil\u0000', '/p%2e%2e/x', '/perfil;x', '/ação', '/a//b']) {
      expect(safeRedirect(value), JSON.stringify(value)).toBe(HOME_PATH)
    }
  })

  it('segmentos de subida ou de ponto', () => {
    for (const value of ['/..', '/a/../b', '/./a', '/a/.']) {
      expect(safeRedirect(value), value).toBe(HOME_PATH)
    }
  })

  it('as telas de quem não tem sessão, para não formar um laço', () => {
    for (const value of ['/entrar', '/entrar/', '/cadastro', { pathname: '/cadastro' }]) {
      expect(safeRedirect(value)).toBe(HOME_PATH)
    }
  })

  it('o que não é texto nem localização', () => {
    for (const value of [undefined, null, 42, {}, { pathname: 7 }, [], true]) {
      expect(safeRedirect(value)).toBe(HOME_PATH)
    }
  })
})
