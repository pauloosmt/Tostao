import { describe, expect, it } from 'vitest'
import { MAX_IMPORT_BYTES, validateImportFile } from './importFile'

describe('o arquivo do extrato', () => {
  it('pede o arquivo, recusa o vazio e o que passa de 2 MB; aceita de 1 byte até 2 MB exatos', () => {
    expect(validateImportFile(null)).toBe('Escolha o arquivo do extrato.')
    expect(validateImportFile({ size: 0 })).toBe('O arquivo está vazio.')
    expect(validateImportFile({ size: MAX_IMPORT_BYTES + 1 })).toBe('O arquivo passa de 2 MB.')
    expect(validateImportFile({ size: 1 })).toBeUndefined()
    expect(validateImportFile({ size: MAX_IMPORT_BYTES })).toBeUndefined()
  })
})
