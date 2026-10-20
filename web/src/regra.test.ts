import { descreverRegra } from './regra'
import type { RegraRecorrencia } from './api'

function regra(sobra: Partial<RegraRecorrencia>): RegraRecorrencia {
  return {
    frequencia: 'mensal',
    intervalo: 1,
    diaDoMes: 5,
    diaDaSemana: null,
    inicio: '2026-11-05',
    fim: null,
    ocorrenciasMax: null,
    ...sobra,
  }
}

describe('descreverRegra', () => {
  it.each([
    [{ frequencia: 'diaria', diaDoMes: null }, 'Todo dia'],
    [{ frequencia: 'diaria', diaDoMes: null, intervalo: 3 }, 'A cada 3 dias'],
    [{ frequencia: 'semanal', diaDoMes: null, diaDaSemana: 0 }, 'Toda semana no domingo'],
    [{ frequencia: 'semanal', diaDoMes: null, diaDaSemana: 0, intervalo: 2 }, 'A cada 2 semanas no domingo'],
    [{ frequencia: 'semanal', diaDoMes: null, diaDaSemana: 3 }, 'Toda semana na quarta-feira'],
    [{ frequencia: 'semanal', diaDoMes: null, diaDaSemana: 6 }, 'Toda semana no sábado'],
    [{}, 'Todo mês no dia 5'],
    [{ intervalo: 2, diaDoMes: 31 }, 'A cada 2 meses no dia 31'],
    [{ frequencia: 'anual', diaDoMes: null, inicio: '2024-02-29' }, 'Todo ano em 29/02'],
    [{ frequencia: 'anual', diaDoMes: null, inicio: '2024-02-29', intervalo: 4 }, 'A cada 4 anos em 29/02'],
  ] as [Partial<RegraRecorrencia>, string][])('descreve %j', (sobra, esperado) => {
    expect(descreverRegra(regra(sobra))).toBe(esperado)
  })

  it('acrescenta o fim por data ou por número de vezes', () => {
    expect(descreverRegra(regra({ fim: '2027-03-05' }))).toBe('Todo mês no dia 5, até 05/03/2027')
    expect(descreverRegra(regra({ ocorrenciasMax: 12 }))).toBe('Todo mês no dia 5, 12 vezes')
    expect(descreverRegra(regra({ ocorrenciasMax: 1 }))).toBe('Todo mês no dia 5, 1 vez')
  })
})
