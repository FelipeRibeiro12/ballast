import {
  formatarVariacaoEmDolar,
  formatarVariacaoEmReais,
  formatarVariacaoPercentual,
  sentidoDaVariacao,
} from './variacao'

// O Intl separa símbolo e número com espaço sem quebra.
const normalizar = (texto: string): string => texto.replace(/ /g, ' ')

describe('variação', () => {
  it('põe sinal explícito no dólar e no real', () => {
    expect(normalizar(formatarVariacaoEmDolar(12_345))).toBe('+US$ 123,45')
    expect(normalizar(formatarVariacaoEmDolar(-12_345))).toBe('-US$ 123,45')
    expect(normalizar(formatarVariacaoEmReais(-50))).toBe('-R$ 0,50')
  })

  it('zero fica sem sinal e estável', () => {
    expect(normalizar(formatarVariacaoEmDolar(0))).toBe('US$ 0,00')
    expect(sentidoDaVariacao(0)).toBe('estavel')
    expect(sentidoDaVariacao(1)).toBe('alta')
    expect(sentidoDaVariacao(-1)).toBe('queda')
  })

  it('formata o percentual em texto, com vírgula e sinal', () => {
    expect(formatarVariacaoPercentual('9.73')).toBe('+9,73%')
    expect(formatarVariacaoPercentual('-10.00')).toBe('-10,00%')
    expect(formatarVariacaoPercentual('0.00')).toBe('0,00%')
    expect(formatarVariacaoPercentual('-0.00')).toBe('0,00%')
  })

  it('percentual nulo vira hífen', () => {
    expect(formatarVariacaoPercentual(null)).toBe('-')
  })
})
