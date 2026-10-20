import { describe, expect, it } from 'vitest'
import { calcularParcelas } from './parcelas'

const cartao = { diaFechamento: 3, diaVencimento: 10 }

describe('calcularParcelas', () => {
  it('divide 1.000,00 em 3x com o resto na última', () => {
    const resultado = calcularParcelas(100000, 3, '2026-10-01', cartao)
    expect(resultado).toEqual({
      tipo: 'ok',
      parcelas: [
        { numero: 1, valorCentavos: 33333, vencimento: '2026-10-10' },
        { numero: 2, valorCentavos: 33333, vencimento: '2026-11-10' },
        { numero: 3, valorCentavos: 33334, vencimento: '2026-12-10' },
      ],
    })
  })

  it('a soma das parcelas fecha no total', () => {
    const resultado = calcularParcelas(100001, 7, '2026-10-01', cartao)
    if (resultado.tipo !== 'ok') throw new Error('esperava ok')
    expect(resultado.parcelas.reduce((soma, p) => soma + BigInt(p.valorCentavos), 0n)).toBe(100001n)
  })

  it('recusa quando a parcela dá zero centavo', () => {
    expect(calcularParcelas(47, 48, '2026-10-01', cartao)).toEqual({ tipo: 'parcela-zero' })
  })

  it('aceita exatamente 1 centavo por parcela', () => {
    const resultado = calcularParcelas(48, 48, '2026-10-01', cartao)
    expect(resultado.tipo).toBe('ok')
  })

  it('12x atravessa fevereiro e a virada de ano, cortando o dia 31 no último dia', () => {
    const resultado = calcularParcelas(120000, 12, '2026-12-20', { diaFechamento: 25, diaVencimento: 31 })
    if (resultado.tipo !== 'ok') throw new Error('esperava ok')
    expect(resultado.parcelas.map((p) => p.vencimento)).toEqual([
      '2026-12-31',
      '2027-01-31',
      '2027-02-28',
      '2027-03-31',
      '2027-04-30',
      '2027-05-31',
      '2027-06-30',
      '2027-07-31',
      '2027-08-31',
      '2027-09-30',
      '2027-10-31',
      '2027-11-30',
    ])
  })

  it('ano bissexto fecha fevereiro no dia 29', () => {
    const resultado = calcularParcelas(30000, 3, '2027-12-20', { diaFechamento: 25, diaVencimento: 30 })
    if (resultado.tipo !== 'ok') throw new Error('esperava ok')
    expect(resultado.parcelas.map((p) => p.vencimento)).toEqual(['2027-12-30', '2028-01-30', '2028-02-29'])
  })

  it('compra depois do fechamento começa na fatura seguinte', () => {
    const resultado = calcularParcelas(20000, 2, '2026-10-04', cartao)
    if (resultado.tipo !== 'ok') throw new Error('esperava ok')
    expect(resultado.parcelas.map((p) => p.vencimento)).toEqual(['2026-11-10', '2026-12-10'])
  })
})
