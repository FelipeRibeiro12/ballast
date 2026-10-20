import { calcularParcelasPorPercentual, formatarPercentual, lerPercentual } from './percentual'

describe('lerPercentual', () => {
  it.each([
    ['33,33', 3333],
    ['40', 4000],
    ['0,5', 50],
    ['0,01', 1],
    ['99,99', 9999],
    ['  12,3 ', 1230],
    ['5,00', 500],
  ])('lê %s como %i', (texto, esperado) => {
    expect(lerPercentual(texto)).toBe(esperado)
  })

  it.each(['', '33,333', '0', '0,00', '100', '100,00', '150', '-5', '-0,5', 'abc', '3a', '33.33', '1e2', ',5', '33,', '40%'])(
    'recusa %j',
    (texto) => {
      expect(lerPercentual(texto)).toBeNull()
    },
  )
})

describe('formatarPercentual', () => {
  it.each([
    [3333, '33,33%'],
    [4000, '40%'],
    [50, '0,5%'],
    [1, '0,01%'],
    [3350, '33,5%'],
    [9999, '99,99%'],
    [10000, '100%'],
  ])('formata %i como %s', (centesimos, esperado) => {
    expect(formatarPercentual(centesimos)).toBe(esperado)
  })
})

describe('calcularParcelasPorPercentual', () => {
  it('trunca cada parcela e a última leva o resto', () => {
    expect(calcularParcelasPorPercentual(100000, [3333, 3333, 3334])).toEqual([33330, 33330, 33340])
  })

  it('a soma fecha no total quando o truncamento sobra centavo', () => {
    const parcelas = calcularParcelasPorPercentual(1001, [3333, 3333, 3334])
    expect(parcelas).toEqual([333, 333, 335])
    expect(parcelas.reduce((a, b) => a + b, 0)).toBe(1001)
  })

  it('devolve zero na parcela pequena demais para valer um centavo', () => {
    expect(calcularParcelasPorPercentual(50, [1, 9999])).toEqual([0, 50])
  })

  it('não perde precisão em valor grande', () => {
    const total = 9007199254740991
    const parcelas = calcularParcelasPorPercentual(total, [3333, 6667])
    expect(parcelas[0]).toBe(3002099511605172)
    expect(parcelas[0] + parcelas[1]).toBe(total)
  })
})
