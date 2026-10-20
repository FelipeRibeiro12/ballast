import {
  formatarDataCompleta,
  formatarDiaMes,
  hojeLocal,
  intervaloDoMes,
  mesDeHoje,
  rotuloDoMes,
  somarMeses,
  ultimoDiaDoMes,
} from './datas'

describe('datas', () => {
  it('usa o dia local às 23h30, sem passar para o dia seguinte', () => {
    const agora = new Date(2026, 8, 30, 23, 30)
    expect(hojeLocal(agora)).toBe('2026-09-30')
    expect(mesDeHoje(agora)).toEqual({ ano: 2026, mes: 9 })
  })

  it('usa o dia local logo depois da meia-noite', () => {
    expect(hojeLocal(new Date(2026, 0, 1, 0, 5))).toBe('2026-01-01')
  })

  it.each([
    [2026, 1, 31],
    [2026, 2, 28],
    [2028, 2, 29],
    [2100, 2, 28],
    [2000, 2, 29],
    [2026, 4, 30],
    [2026, 12, 31],
  ])('último dia de %i-%i é %i', (ano, mes, dia) => {
    expect(ultimoDiaDoMes({ ano, mes })).toBe(dia)
  })

  it('monta o intervalo de fevereiro bissexto', () => {
    expect(intervaloDoMes({ ano: 2028, mes: 2 })).toEqual({ de: '2028-02-01', ate: '2028-02-29' })
  })

  it('soma meses atravessando o ano', () => {
    expect(somarMeses({ ano: 2026, mes: 12 }, 1)).toEqual({ ano: 2027, mes: 1 })
    expect(somarMeses({ ano: 2026, mes: 1 }, -1)).toEqual({ ano: 2025, mes: 12 })
  })

  it('formata dia e mês sem passar por Date', () => {
    expect(formatarDiaMes('2026-09-05')).toBe('05/09')
  })

  it('escreve o mês por extenso', () => {
    expect(rotuloDoMes({ ano: 2026, mes: 9 })).toBe('setembro de 2026')
  })
})

describe('formatarDataCompleta', () => {
  it('troca AAAA-MM-DD por dd/mm/aaaa sem passar por Date', () => {
    expect(formatarDataCompleta('2026-09-05')).toBe('05/09/2026')
    expect(formatarDataCompleta('2027-01-01')).toBe('01/01/2027')
  })
})
