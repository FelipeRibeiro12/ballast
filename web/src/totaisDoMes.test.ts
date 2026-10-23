import { describe, expect, it } from 'vitest'
import type { Lancamento } from './api'
import { aindaNaoAconteceu, somarTotaisDoMes } from './totaisDoMes'

const HOJE = '2026-10-19'

function lancamento(sobra: Partial<Lancamento> = {}): Lancamento {
  return {
    id: 'l1',
    tipo: 'despesa',
    status: 'realizado',
    titulo: 'Feira',
    descricao: null,
    valorCentavos: 10000,
    categoriaId: 'cd1',
    transferenciaId: null,
    templateId: null,
    parcelaOrdem: null,
    compraParceladaId: null,
    parcelaNum: null,
    parcelaTotal: null,
    direcao: null,
    contaId: 'c1',
    dataCompetencia: '2026-10-05',
    dataCaixa: '2026-10-05',
    criadoEm: '2026-10-05T12:00:00Z',
    ...sobra,
  }
}

describe('aindaNaoAconteceu', () => {
  it('previsto não aconteceu, mesmo com a data já passada', () => {
    expect(aindaNaoAconteceu(lancamento({ status: 'previsto', dataCaixa: '2026-10-01' }), HOJE)).toBe(true)
  })

  it('realizado com caixa depois de hoje ainda não moveu dinheiro', () => {
    expect(aindaNaoAconteceu(lancamento({ dataCaixa: '2026-10-20' }), HOJE)).toBe(true)
  })

  it('realizado com caixa hoje ou antes já aconteceu', () => {
    expect(aindaNaoAconteceu(lancamento({ dataCaixa: HOJE }), HOJE)).toBe(false)
    expect(aindaNaoAconteceu(lancamento({ dataCaixa: '2026-10-01' }), HOJE)).toBe(false)
  })

  it('cancelado não entra em lugar nenhum', () => {
    expect(aindaNaoAconteceu(lancamento({ status: 'cancelado', dataCaixa: '2026-10-25' }), HOJE)).toBe(false)
  })
})

describe('somarTotaisDoMes', () => {
  it('separa o que já moveu dinheiro do que ainda vai mover pela data de caixa', () => {
    const totais = somarTotaisDoMes(
      [
        lancamento({ id: 'a', valorCentavos: 12345 }),
        // Compra no cartão: competência em outubro, caixa no vencimento da fatura.
        lancamento({ id: 'b', valorCentavos: 30000, dataCompetencia: '2026-10-02', dataCaixa: '2026-10-25' }),
        lancamento({ id: 'c', tipo: 'receita', status: 'previsto', valorCentavos: 500000, dataCaixa: '2026-10-30' }),
        lancamento({ id: 'd', tipo: 'receita', valorCentavos: 7000, dataCaixa: '2026-10-28' }),
        lancamento({ id: 'e', tipo: 'receita', valorCentavos: 1000, dataCaixa: HOJE }),
        lancamento({ id: 'f', status: 'cancelado', valorCentavos: 99999, dataCaixa: '2026-10-01' }),
      ],
      HOJE,
    )
    expect(totais).toEqual({
      receitas: 1000n,
      despesas: 12345n,
      previstoReceitas: 507000n,
      previstoDespesas: 30000n,
    })
  })

  it('transferência fica fora dos quatro totais', () => {
    const totais = somarTotaisDoMes(
      [
        lancamento({ tipo: 'transferencia', direcao: 'saida', categoriaId: null, transferenciaId: 't1' }),
        lancamento({ tipo: 'transferencia', direcao: 'entrada', categoriaId: null, transferenciaId: 't2', dataCaixa: '2026-10-25' }),
      ],
      HOJE,
    )
    expect(totais).toEqual({ receitas: 0n, despesas: 0n, previstoReceitas: 0n, previstoDespesas: 0n })
  })
})
