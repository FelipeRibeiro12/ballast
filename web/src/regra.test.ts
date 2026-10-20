import { descreverParcelas, descreverRegra } from './regra'
import type { Recorrencia, RegraRecorrencia } from './api'

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

describe('descreverRegra com divisão', () => {
  const divisao = (dias: number[]): NonNullable<Recorrencia['divisao']> => ({
    modo: 'valor',
    parcelas: dias.map((diaDoMes, indice) => ({
      ordem: indice + 1,
      diaDoMes,
      valorCentavos: 100,
      valorParcelaCentavos: 100,
    })),
  })

  it('lista os dias das parcelas no lugar do dia da regra', () => {
    expect(descreverRegra(regra({ diaDoMes: 15 }), divisao([15, 30]))).toBe('Todo mês nos dias 15 e 30')
    expect(descreverRegra(regra({ intervalo: 2, diaDoMes: 5 }), divisao([5, 20, 30]))).toBe(
      'A cada 2 meses nos dias 5, 20 e 30',
    )
  })

  it('ordena os dias e mantém o fim da regra', () => {
    expect(descreverRegra(regra({ ocorrenciasMax: 6, diaDoMes: 30 }), divisao([30, 10]))).toBe(
      'Todo mês nos dias 10 e 30, 6 vezes',
    )
  })

  it('sem divisão nada muda', () => {
    expect(descreverRegra(regra({}), null)).toBe('Todo mês no dia 5')
  })
})

describe('descreverParcelas', () => {
  it('mostra o valor de cada parcela e o percentual no modo percentual', () => {
    expect(
      descreverParcelas({
        modo: 'percentual',
        parcelas: [
          { ordem: 1, diaDoMes: 15, percentualCentesimos: 4000, valorParcelaCentavos: 200000 },
          { ordem: 2, diaDoMes: 30, percentualCentesimos: 6000, valorParcelaCentavos: 300000 },
        ],
      }).map((texto) => texto.replace(/\u00a0/g, ' ')),
    ).toEqual(['dia 15: R$ 2.000,00 (40%)', 'dia 30: R$ 3.000,00 (60%)'])
  })

  it('no modo valor mostra só o valor', () => {
    expect(
      descreverParcelas({
        modo: 'valor',
        parcelas: [{ ordem: 1, diaDoMes: 5, valorCentavos: 50000, valorParcelaCentavos: 50000 }],
      }).map((texto) => texto.replace(/\u00a0/g, ' ')),
    ).toEqual(['dia 5: R$ 500,00'])
  })
})
