import type { Conta, Patrimonio } from './api'
import { gruposDoResumo } from './ResumoDasContas'

function conta(tipo: Conta['tipo'], saldoCentavos: number): Conta {
  return {
    id: `${tipo}-${saldoCentavos}`,
    nome: tipo,
    tipo,
    moeda: 'BRL',
    saldoInicialCentavos: saldoCentavos,
    saldoCentavos,
    arquivada: false,
    criadaEm: '2026-10-19T12:00:00Z',
    cartao: null,
    categoriasPermitidas: null,
    percentualCdi: null,
  }
}

function patrimonio(cripto: number, rendaFixa: number): Patrimonio {
  return { criptoCentavos: cripto, rendaFixaCentavos: rendaFixa } as Patrimonio
}

const porChave = (grupos: ReturnType<typeof gruposDoResumo>) => Object.fromEntries(grupos.map((g) => [g.chave, g.centavos]))

describe('gruposDoResumo', () => {
  it('o total não conta o cartão de crédito', () => {
    const grupos = porChave(
      gruposDoResumo([conta('corrente', 100_00), conta('poupanca', 50_00), conta('cartao_credito', -300_00)], null),
    )

    expect(grupos.total).toBe(150_00n)
    expect(grupos.bancos).toBe(150_00n)
  })

  it('separa dinheiro, benefício e cofrinho', () => {
    const grupos = porChave(
      gruposDoResumo([conta('dinheiro', 20_00), conta('beneficio', 80_00), conta('cofrinho', 500_00)], null),
    )

    expect(grupos.dinheiro).toBe(20_00n)
    expect(grupos.beneficio).toBe(80_00n)
    expect(grupos.cofrinho).toBe(500_00n)
    expect(grupos.total).toBe(600_00n)
  })

  it('traz cripto e investimentos do patrimônio e soma o grupo junto', () => {
    const grupos = porChave(gruposDoResumo([conta('corrente', 100_00)], patrimonio(40_00, 60_00)))

    expect(grupos.cripto).toBe(40_00n)
    expect(grupos.investimentos).toBe(60_00n)
    expect(grupos['investimentos-cripto']).toBe(100_00n)
    expect(grupos.total).toBe(200_00n)
  })

  it('sem patrimônio os grupos de cripto e investimentos somem', () => {
    const chaves = gruposDoResumo([conta('corrente', 1_00)], null).map((g) => g.chave)

    expect(chaves).not.toContain('cripto')
    expect(chaves).not.toContain('investimentos')
    expect(chaves).not.toContain('cofrinho')
  })

  it('soma sem perder centavo acima do inteiro seguro', () => {
    const grupos = porChave(
      gruposDoResumo([conta('corrente', Number.MAX_SAFE_INTEGER), conta('poupanca', Number.MAX_SAFE_INTEGER)], null),
    )

    expect(grupos.total).toBe(BigInt(Number.MAX_SAFE_INTEGER) * 2n)
  })
})
