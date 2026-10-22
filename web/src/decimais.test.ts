import { describe, expect, it } from 'vitest'
import { formatarDecimal, lerPreco, lerQuantidade } from './decimais'

describe('lerQuantidade', () => {
  it.each([
    ['1', '1'],
    ['0,5', '0.5'],
    ['0.5', '0.5'],
    ['0,123456789012345678', '0.123456789012345678'],
    ['  2,25 ', '2.25'],
    ['99999999999999999999', '99999999999999999999'],
  ])('lê %s como %s', (texto, esperado) => {
    expect(lerQuantidade(texto)).toBe(esperado)
  })

  it.each(['', '0', '0,000', '-1', '1e3', '1.234,5', '1,2,3', ',5', '5,', '0,1234567890123456789', '123456789012345678901'])(
    'recusa %s',
    (texto) => {
      expect(lerQuantidade(texto)).toBeNull()
    },
  )
})

describe('lerPreco', () => {
  it.each([
    ['350000', '350000'],
    ['350.000,50', '350000.50'],
    ['0,00000001', '0.00000001'],
    ['999.999.999.999,99999999', '999999999999.99999999'],
    ['1,5', '1.5'],
  ])('lê %s como %s', (texto, esperado) => {
    expect(lerPreco(texto)).toBe(esperado)
  })

  it.each(['', '0', '0,00', '-1', '1.5', '1,123456789', '1.000.000.000.000', 'abc', '1e3'])('recusa %s', (texto) => {
    expect(lerPreco(texto)).toBeNull()
  })
})

describe('formatarDecimal', () => {
  it.each([
    ['0.12345678', '0,12345678'],
    ['1234567.5', '1.234.567,5'],
    ['100', '100'],
    ['-20.5', '-20,5'],
    ['123456789012345678901234567890.000000000000000001', '123.456.789.012.345.678.901.234.567.890,000000000000000001'],
  ])('mostra %s como %s sem passar por Number', (texto, esperado) => {
    expect(formatarDecimal(texto)).toBe(esperado)
  })
})
