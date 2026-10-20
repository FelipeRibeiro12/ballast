import { somarLinhas, validarDivisao, valoresGerados } from './divisao'

const linha = (dia: string, valor: string) => ({ dia, valor })

describe('somarLinhas', () => {
  it('soma percentual em centésimos e ignora o que não leu', () => {
    expect(somarLinhas('percentual', [linha('5', '33,33'), linha('6', 'abc'), linha('7', '40')])).toBe(7333n)
  })

  it('soma valor em centavos sem passar do inteiro seguro', () => {
    const linhas = [linha('5', '90.071.992.547.409,91'), linha('6', '90.071.992.547.409,91')]
    expect(somarLinhas('valor', linhas)).toBe(18014398509481982n)
  })
})

describe('valoresGerados', () => {
  it('mostra 333,30 + 333,30 + 333,40 para 33,33 / 33,33 / 33,34 de 1.000,00', () => {
    const linhas = [linha('5', '33,33'), linha('15', '33,33'), linha('25', '33,34')]
    expect(valoresGerados(100000, linhas)).toEqual([33330, 33330, 33340])
  })

  it('não calcula com a soma errada, percentual inválido ou sem valor total', () => {
    expect(valoresGerados(100000, [linha('5', '33,33'), linha('15', '33,33')])).toBeNull()
    expect(valoresGerados(100000, [linha('5', '50'), linha('15', 'x')])).toBeNull()
    expect(valoresGerados(null, [linha('5', '50'), linha('15', '50')])).toBeNull()
  })
})

describe('validarDivisao', () => {
  const dois = [linha('15', '40'), linha('30', '60')]

  it('monta a divisão na ordem das linhas, com inteiros', () => {
    expect(validarDivisao('percentual', dois, 500000)).toEqual({
      tipo: 'ok',
      divisao: {
        modo: 'percentual',
        parcelas: [
          { diaDoMes: 15, percentualCentesimos: 4000 },
          { diaDoMes: 30, percentualCentesimos: 6000 },
        ],
      },
    })
    expect(validarDivisao('valor', [linha('30', '2.000,00'), linha('15', '3.000,00')], 500000)).toEqual({
      tipo: 'ok',
      divisao: {
        modo: 'valor',
        parcelas: [
          { diaDoMes: 30, valorCentavos: 200000 },
          { diaDoMes: 15, valorCentavos: 300000 },
        ],
      },
    })
  })

  it.each([
    ['dia vazio', [linha('', '40'), linha('30', '60')], /dia do mês da parcela 1/i],
    ['dia 32', [linha('32', '40'), linha('30', '60')], /dia do mês da parcela 1/i],
    ['dia decimal', [linha('1.5', '40'), linha('30', '60')], /dia do mês da parcela 1/i],
    ['percentual inválido', [linha('15', '40'), linha('30', '60,555')], /percentual da parcela 2/i],
    ['dia repetido', [linha('15', '40'), linha('15', '60')], /dias das parcelas devem ser diferentes/i],
    ['soma diferente de 100', [linha('15', '40'), linha('30', '59,99')], /somar 100%/i],
  ])('recusa percentual com %s', (_nome, linhas, mensagem) => {
    const resultado = validarDivisao('percentual', linhas, 500000)
    expect(resultado.tipo).toBe('erro')
    if (resultado.tipo === 'erro') expect(resultado.mensagem).toMatch(mensagem)
  })

  it('recusa valor que não soma o total e valor inválido', () => {
    const soma = validarDivisao('valor', [linha('15', '2.000,00'), linha('30', '2.999,99')], 500000)
    expect(soma.tipo === 'erro' && soma.mensagem).toMatch(/somar o valor total/i)
    const invalido = validarDivisao('valor', [linha('15', '0'), linha('30', '5.000,00')], 500000)
    expect(invalido.tipo === 'erro' && invalido.mensagem).toMatch(/valor da parcela 1/i)
  })

  it('recusa parcela que daria zero centavos', () => {
    const resultado = validarDivisao('percentual', [linha('15', '0,01'), linha('30', '99,99')], 50)
    expect(resultado.tipo === 'erro' && resultado.mensagem).toMatch(/ao menos R\$[\s ]0,01/)
  })
})
