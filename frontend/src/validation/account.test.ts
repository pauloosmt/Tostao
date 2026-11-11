import { describe, expect, it } from 'vitest'
import {
  firstInvalid,
  utf8Bytes,
  validateCurrentPassword,
  validateEmail,
  validateLogin,
  validateName,
  validateNewPassword,
  validateRegister,
} from './account'

describe('nome', () => {
  it('exige de 1 a 100 caracteres, sem contar os espaços das pontas', () => {
    expect(validateName('')).toBe('Informe seu nome.')
    expect(validateName('   ')).toBe('Informe seu nome.')
    expect(validateName('A')).toBeUndefined()
    expect(validateName('  Ana  ')).toBeUndefined()
    expect(validateName('a'.repeat(100))).toBeUndefined()
    expect(validateName('a'.repeat(101))).toBe('Use no máximo 100 caracteres.')
    expect(validateName(' ' + 'a'.repeat(100) + ' ')).toBeUndefined()
  })
})

describe('e-mail', () => {
  it('exige um e-mail com arroba, sem espaços, de até 254 caracteres', () => {
    expect(validateEmail('')).toBe('Informe seu e-mail.')
    expect(validateEmail('  ')).toBe('Informe seu e-mail.')
    for (const bad of ['abc', '@exemplo.com', 'ana@', 'a b@exemplo.com', 'ana@@exemplo.com', 'ana@exe mplo.com']) {
      expect(validateEmail(bad), bad).toBe('Informe um e-mail válido.')
    }
    for (const good of ['ana@exemplo.com', '  ana@exemplo.com  ', 'a.b+c@sub.exemplo.com.br', 'ana@localhost']) {
      expect(validateEmail(good), good).toBeUndefined()
    }
  })

  it('o limite é de 254 caracteres', () => {
    const at254 = 'a'.repeat(254 - '@e.com'.length) + '@e.com'
    expect(at254).toHaveLength(254)
    expect(validateEmail(at254)).toBeUndefined()
    expect(validateEmail('a' + at254)).toBe('Use no máximo 254 caracteres.')
  })
})

describe('senha do cadastro', () => {
  it('mede em bytes de UTF-8, como a API', () => {
    expect(utf8Bytes('abc')).toBe(3)
    expect(utf8Bytes('ç')).toBe(2)
    expect(utf8Bytes('€')).toBe(3)
    expect(utf8Bytes('😀')).toBe(4)
  })

  it('exige de 8 a 72 bytes', () => {
    expect(validateNewPassword('')).toBe('Informe uma senha.')
    expect(validateNewPassword('abc1234')).toBe('Use ao menos 8 caracteres.')
    expect(validateNewPassword('abc12345')).toBeUndefined()
    expect(validateNewPassword('a1' + 'x'.repeat(70))).toBeUndefined()
    expect(validateNewPassword('a1' + 'x'.repeat(71))).toBe('Use no máximo 72 bytes (acentos e emojis contam mais de um).')
  })

  it('os 72 bytes contam os acentos e emojis a mais', () => {
    const exactly72 = 'ç'.repeat(35) + '12' // 35 letras de 2 bytes + 2 dígitos = 72 bytes
    expect(utf8Bytes(exactly72)).toBe(72)
    expect(validateNewPassword(exactly72)).toBeUndefined()
    const over = 'ç'.repeat(36) + '12' // 74 bytes, só 38 caracteres
    expect(validateNewPassword(over)).toBe('Use no máximo 72 bytes (acentos e emojis contam mais de um).')
    // 8 caracteres de 1 byte são 8 bytes; 4 emojis são 16 bytes e passam do mínimo mesmo com 4 caracteres visíveis
    expect(validateNewPassword('😀😀😀😀a1')).toBeUndefined()
  })

  it('exige ao menos uma letra e um número (letras e dígitos de qualquer alfabeto)', () => {
    expect(validateNewPassword('somenteletras')).toBe('Inclua ao menos uma letra e um número.')
    expect(validateNewPassword('12345678')).toBe('Inclua ao menos uma letra e um número.')
    expect(validateNewPassword('!!!!????')).toBe('Inclua ao menos uma letra e um número.')
    expect(validateNewPassword('senhaç1x')).toBeUndefined()
    expect(validateNewPassword('пароль12')).toBeUndefined()
    expect(validateNewPassword('Senha-Forte-123')).toBeUndefined()
  })

  it('não apara os espaços: a senha vale exatamente como digitada', () => {
    expect(validateNewPassword('  a1    ')).toBeUndefined()
    expect(validateNewPassword('  a1  ')).toBe('Use ao menos 8 caracteres.')
    expect(validateNewPassword('        ')).toBe('Inclua ao menos uma letra e um número.')
  })
})

describe('entrada', () => {
  it('só exige e-mail válido e senha preenchida; o resto é com a API', () => {
    expect(validateCurrentPassword('')).toBe('Informe sua senha.')
    expect(validateCurrentPassword('x')).toBeUndefined()
    expect(validateLogin({ email: '', password: '' })).toEqual({ email: 'Informe seu e-mail.', password: 'Informe sua senha.' })
    expect(validateLogin({ email: 'abc', password: 'x' })).toEqual({ email: 'Informe um e-mail válido.' })
    expect(validateLogin({ email: 'ana@exemplo.com', password: 'qualquer' })).toEqual({})
  })
})

describe('cadastro', () => {
  it('junta os erros de cada campo e omite os campos bons', () => {
    expect(validateRegister({ name: '', email: 'abc', password: 'curta' })).toEqual({
      name: 'Informe seu nome.',
      email: 'Informe um e-mail válido.',
      password: 'Use ao menos 8 caracteres.',
    })
    expect(validateRegister({ name: 'Ana', email: 'ana@exemplo.com', password: 'Senha-Forte-123' })).toEqual({})
    expect(validateRegister({ name: 'Ana', email: 'ana@exemplo.com', password: 'semnumero' })).toEqual({
      password: 'Inclua ao menos uma letra e um número.',
    })
  })

  it('aponta o primeiro campo com erro, na ordem da tela', () => {
    const order = ['name', 'email', 'password'] as const
    expect(firstInvalid({ password: 'x', email: 'y' }, order)).toBe('email')
    expect(firstInvalid({ password: 'x' }, order)).toBe('password')
    expect(firstInvalid({}, order)).toBeUndefined()
  })
})
