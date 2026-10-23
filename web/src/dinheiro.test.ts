import { centavosParaCampo, formatarCentavos, formatarCentavosEmDolar, lerCentavos, lerValorPositivo } from './dinheiro'

// O Intl separa o símbolo do número com espaço sem quebra (U+00A0).
const normalizar = (texto: string): string => texto.replace(/ /g, ' ')

describe('formatarCentavos', () => {
  it.each([
    [0, 'R$ 0,00'],
    [5, 'R$ 0,05'],
    [-5, '-R$ 0,05'],
    [100, 'R$ 1,00'],
    [123456, 'R$ 1.234,56'],
    [-123456, '-R$ 1.234,56'],
    [9007199254740991, 'R$ 90.071.992.547.409,91'],
    [-9007199254740991, '-R$ 90.071.992.547.409,91'],
  ])('formata %i como %s', (centavos, esperado) => {
    expect(normalizar(formatarCentavos(centavos))).toBe(esperado)
  })

  it('aceita bigint acima do limite seguro sem perder o centavo', () => {
    expect(normalizar(formatarCentavos(18014398509481983n))).toBe('R$ 180.143.985.094.819,83')
  })
})

describe('lerCentavos', () => {
  it.each([
    ['1234', 123400],
    ['1234,5', 123450],
    ['1.234,56', 123456],
    ['-10,00', -1000],
    ['0,99', 99],
    ['0', 0],
    ['-0,01', -1],
    ['  12,30  ', 1230],
    ['90.071.992.547.409,91', 9007199254740991],
    ['-90.071.992.547.409,91', -9007199254740991],
  ])('lê "%s" como %i', (texto, esperado) => {
    expect(lerCentavos(texto)).toBe(esperado)
  })

  it.each([
    [''],
    ['   '],
    ['abc'],
    ['12a'],
    ['1,234'],
    ['1,'],
    [',5'],
    ['-'],
    ['1,2,3'],
    ['1.2.3'],
    ['1e3'],
    ['--1'],
    ['+5'],
    ['90.071.992.547.409,92'],
    ['-90.071.992.547.409,92'],
    ['99999999999999999999'],
  ])('recusa "%s"', (texto) => {
    expect(lerCentavos(texto)).toBeNull()
  })
})

describe('lerValorPositivo', () => {
  it.each([
    ['0,01', 1],
    ['1.234,56', 123456],
    ['90.071.992.547.409,91', 9007199254740991],
  ])('lê "%s" como %i', (texto, esperado) => {
    expect(lerValorPositivo(texto)).toBe(esperado)
  })

  it.each([['0'], ['0,00'], ['-1,00'], ['-0,01'], [''], ['abc'], ['90.071.992.547.409,92']])(
    'recusa "%s"',
    (texto) => {
      expect(lerValorPositivo(texto)).toBeNull()
    },
  )
})

describe('centavosParaCampo', () => {
  it.each([
    [0, '0,00'],
    [5, '0,05'],
    [123456, '1.234,56'],
    [-1050, '-10,50'],
    [Number.MAX_SAFE_INTEGER, '90.071.992.547.409,91'],
  ])('%d vira %s e volta igual pelo lerCentavos', (centavos, texto) => {
    expect(centavosParaCampo(centavos)).toBe(texto)
    expect(lerCentavos(texto)).toBe(centavos)
  })
})

describe('formatarCentavosEmDolar', () => {
  it('mostra dólar no formato brasileiro sem passar por float', () => {
    expect(formatarCentavosEmDolar(3_500_025).replace(/\s/g, ' ')).toBe('US$ 35.000,25')
    expect(formatarCentavosEmDolar(-5).replace(/\s/g, ' ')).toBe('-US$ 0,05')
  })
})
