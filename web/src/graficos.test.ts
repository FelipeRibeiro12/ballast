import { faixaDoEixo, formatarCompacto, fracaoDoMaximo, percentualDoTotal, rotuloCurtoDoMes, topoDoEixo } from './graficos'

// O Intl separa símbolo e número com espaço sem quebra; o teste compara com espaço comum.
const semNbsp = (texto: string): string => texto.replace(/\u00a0/g, ' ')

describe('fracaoDoMaximo', () => {
  it.each([
    [50, 100, 50],
    [1, 3, 33.3],
    [100, 100, 100],
    [0, 100, 0],
    [10, 0, 0],
  ])('%i de %i é %d%%', (valor, maximo, esperado) => {
    expect(fracaoDoMaximo(valor, maximo)).toBe(esperado)
  })

  it('não perde precisão perto do inteiro seguro', () => {
    expect(fracaoDoMaximo(Number.MAX_SAFE_INTEGER - 1, Number.MAX_SAFE_INTEGER)).toBe(99.9)
  })
})

describe('percentualDoTotal', () => {
  it.each([
    [1, 3, 33],
    [2, 3, 67],
    [1, 2, 50],
    [1, 1000, 0],
    [0, 0, 0],
  ])('%i de %i arredonda para %i%%', (valor, total, esperado) => {
    expect(percentualDoTotal(valor, total)).toBe(esperado)
  })
})

describe('topoDoEixo', () => {
  it.each([
    [523_000, 600_000],
    [100_000, 100_000],
    [100_001, 150_000],
    [7, 8],
    [1, 1],
    [0, 0],
    [Number.MAX_SAFE_INTEGER, 10_000_000_000_000_000],
  ])('%i sobe para %i', (maximo, esperado) => {
    expect(topoDoEixo(maximo)).toBe(esperado)
  })
})

describe('formatarCompacto', () => {
  it.each([
    [0, 'R$ 0'],
    [50_000, 'R$ 500'],
    [600_000, 'R$ 6 mil'],
    [150_000_000, 'R$ 1,5 mi'],
  ])('%i centavos vira %s', (centavos, esperado) => {
    expect(semNbsp(formatarCompacto(centavos))).toBe(esperado)
  })
})

describe('rotuloCurtoDoMes', () => {
  it.each([
    [{ ano: 2026, mes: 10 }, 'out'],
    [{ ano: 2027, mes: 1 }, 'jan'],
  ])('%o vira %s', (mes, esperado) => {
    expect(rotuloCurtoDoMes(mes)).toBe(esperado)
  })
})

describe('faixaDoEixo', () => {
  it.each([
    [0, 523_000, 0, 600_000],
    [100_000, 523_000, 0, 600_000],
    [-50_000, 523_000, -50_000, 600_000],
    [-130_000, -40_000, -150_000, 0],
    [0, 0, 0, 100],
  ])('de %i a %i vira de %i a %i', (minimo, maximo, baixo, alto) => {
    expect(faixaDoEixo(minimo, maximo)).toEqual({ baixo, alto })
  })
})
