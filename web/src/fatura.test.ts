import { describe, expect, it } from 'vitest'
import { vencimentoDaFatura } from './fatura'

describe('vencimentoDaFatura', () => {
  it('fechamento 31 em fevereiro fecha no último dia do mês', () => {
    // 2027 não é bissexto: fecha em 28/02, então a compra do dia 28 ainda entra nela.
    expect(vencimentoDaFatura('2027-02-28', 31, 5)).toBe('2027-03-05')
    expect(vencimentoDaFatura('2027-02-10', 31, 5)).toBe('2027-03-05')
    // Em ano bissexto o fechamento é 29/02: a compra do dia 29 entra nela, a de 1/03 vai para a seguinte.
    expect(vencimentoDaFatura('2028-02-29', 31, 5)).toBe('2028-03-05')
    expect(vencimentoDaFatura('2028-03-01', 31, 5)).toBe('2028-04-05')
  })

  it('vencimento menor que fechamento vence no mês seguinte ao do fechamento', () => {
    expect(vencimentoDaFatura('2026-10-10', 20, 5)).toBe('2026-11-05')
    expect(vencimentoDaFatura('2026-10-21', 20, 5)).toBe('2026-12-05')
  })

  it('vencimento maior que fechamento vence no mesmo mês', () => {
    expect(vencimentoDaFatura('2026-10-03', 5, 15)).toBe('2026-10-15')
    expect(vencimentoDaFatura('2026-10-10', 5, 15)).toBe('2026-11-15')
  })

  it('compra no dia do fechamento entra na fatura que fecha naquele dia', () => {
    expect(vencimentoDaFatura('2026-10-20', 20, 5)).toBe('2026-11-05')
    expect(vencimentoDaFatura('2026-10-20', 20, 25)).toBe('2026-10-25')
  })

  it('vira o ano', () => {
    expect(vencimentoDaFatura('2026-12-25', 20, 5)).toBe('2027-02-05')
    expect(vencimentoDaFatura('2026-12-10', 20, 5)).toBe('2027-01-05')
  })

  it('vencimento 31 em mês curto cai no último dia', () => {
    expect(vencimentoDaFatura('2027-01-10', 5, 31)).toBe('2027-02-28')
  })
})
