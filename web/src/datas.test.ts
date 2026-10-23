import {
  formatarDataCompleta,
  formatarDiaMes,
  hojeLocal,
  horaEmSaoPaulo,
  intervaloDoMes,
  lerMesIso,
  mesDeHoje,
  mesIso,
  rotuloDoDia,
  rotuloDoMes,
  somarDias,
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

  describe('somarDias', () => {
    it('vira o mês e o ano', () => {
      expect(somarDias('2026-10-31', 1)).toBe('2026-11-01')
      expect(somarDias('2026-12-31', 1)).toBe('2027-01-01')
      expect(somarDias('2026-01-01', -1)).toBe('2025-12-31')
    })

    it('respeita fevereiro e o ano bissexto', () => {
      expect(somarDias('2028-02-28', 1)).toBe('2028-02-29')
      expect(somarDias('2027-02-28', 1)).toBe('2027-03-01')
    })

    it('soma 30 dias a partir de hoje', () => {
      expect(somarDias('2026-10-19', 30)).toBe('2026-11-18')
    })
  })

  describe('rotuloDoDia', () => {
    it('usa Hoje, Ontem e Amanhã perto do dia atual', () => {
      expect(rotuloDoDia('2026-10-19', '2026-10-19')).toBe('Hoje, 19/10')
      expect(rotuloDoDia('2026-10-18', '2026-10-19')).toBe('Ontem, 18/10')
      expect(rotuloDoDia('2026-10-20', '2026-10-19')).toBe('Amanhã, 20/10')
    })

    it('usa o dia da semana nos outros dias, com inicial maiúscula', () => {
      expect(rotuloDoDia('2026-10-05', '2026-10-19')).toBe('Segunda-feira, 05/10')
      expect(rotuloDoDia('2026-10-31', '2026-10-19')).toBe('Sábado, 31/10')
    })
  })
})

describe('horaEmSaoPaulo', () => {
  it('mostra a hora de São Paulo, mesmo quando em UTC já é outro dia', () => {
    expect(horaEmSaoPaulo('2026-10-22T01:30:00Z')).toBe('22:30')
    expect(horaEmSaoPaulo('2026-10-21T03:05:00Z')).toBe('00:05')
  })
})

describe('mesIso', () => {
  it('escreve o mês com dois dígitos e o ano com quatro', () => {
    expect(mesIso({ ano: 2026, mes: 3 })).toBe('2026-03')
    expect(mesIso({ ano: 2199, mes: 12 })).toBe('2199-12')
  })

  it('lê de volta o mesmo mês', () => {
    expect(lerMesIso('2027-04')).toEqual({ ano: 2027, mes: 4 })
    expect(mesIso(lerMesIso('1900-01'))).toBe('1900-01')
  })
})
