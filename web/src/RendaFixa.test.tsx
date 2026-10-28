import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import RendaFixa from './RendaFixa'
import type { Conta } from './api'

const corretora: Conta = {
  id: 'k1',
  nome: 'Corretora',
  tipo: 'investimento',
  moeda: 'BRL',
  saldoInicialCentavos: 0,
  saldoCentavos: 0,
  arquivada: false,
  criadaEm: '2026-10-19T12:00:00Z',
  cartao: null,
  categoriasPermitidas: null,
    percentualCdi: null,
}

function titulo(sobra: Partial<Record<string, unknown>> = {}): Record<string, unknown> {
  return {
    id: 't1',
    contaId: 'k1',
    tipo: 'cdb',
    nome: 'CDB Banco X',
    valorAplicadoCentavos: 100_000,
    dataAplicacao: '2026-01-10',
    vencimento: '2028-01-10',
    indexador: 'cdi',
    taxa: '110',
    resgate: null,
    ultimaMarcacao: null,
    valorBrutoCentavos: 110_000,
    iofCentavos: 0,
    irCentavos: 2_000,
    valorLiquidoCentavos: 108_000,
    rendimentoCentavos: 10_000,
    ...sobra,
  }
}

function lista(titulos: unknown[], cdiAte: string | null = '2026-10-21'): Record<string, unknown> {
  return { data: '2026-10-22', indices: { cdiAte, ipcaAte: '2026-09-01' }, titulos }
}

function jsonResponse(corpo: unknown, status: number): Response {
  return new Response(JSON.stringify(corpo), { status, headers: { 'Content-Type': 'application/json' } })
}

type Rotas = Record<string, (corpo: unknown) => Promise<Response>>

const responde = (corpo: unknown, status = 200) => () => Promise.resolve(jsonResponse(corpo, status))

function mockFetch(rotas: Rotas = {}): void {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      const chave = `${init?.method ?? 'GET'} ${url}`
      const corpo: unknown = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined
      const rota = rotas[chave]
      if (rota) return rota(corpo)
      if (chave === 'GET /titulos-renda-fixa?data=2026-10-22') return Promise.resolve(jsonResponse(lista([titulo()]), 200))
      return Promise.reject(new Error(`rota sem mock: ${chave}`))
    }),
  )
}

function chamadas(chave: string): unknown[] {
  return vi
    .mocked(fetch)
    .mock.calls.filter(([url, init]) => `${init?.method ?? 'GET'} ${String(url)}` === chave)
    .map(([, init]) => (typeof init?.body === 'string' ? JSON.parse(init.body) : undefined))
}

const texto = (valor: string): RegExp =>
  new RegExp(valor.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replace(/[ \u00a0]/g, '[\\s\\u00a0]'))

function renderizar(contas: Conta[] = [corretora]) {
  const aoMudar = vi.fn()
  const aoSessaoExpirar = vi.fn()
  render(<RendaFixa contas={contas} data="2026-10-22" aoMudar={aoMudar} aoSessaoExpirar={aoSessaoExpirar} />)
  return { aoMudar, aoSessaoExpirar }
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-10-22T12:00:00-03:00'))
})

afterEach(() => {
  vi.useRealTimers()
})

describe('RendaFixa', () => {
  it('lista o título com líquido, bruto, imposto estimado e rendimento', async () => {
    mockFetch()
    renderizar()
    const item = within(await screen.findByRole('list', { name: 'Títulos' })).getByRole('listitem')
    expect(within(item).getByText('CDB Banco X')).toBeVisible()
    expect(within(item).getByText('110% do CDI · Corretora')).toBeVisible()
    expect(within(item).getByText(texto('R$ 1.080,00'))).toBeVisible()
    expect(within(item).getByText(texto('R$ 1.100,00'))).toBeVisible()
    expect(within(item).getByText('IR e IOF estimados')).toBeVisible()
    expect(within(item).getByText(texto('R$ 20,00'))).toBeVisible()
    expect(within(item).getByText(texto('R$ 100,00'))).toBeVisible()
    expect(screen.getByText(/Calculado com o CDI até 21\/10\/2026/)).toBeVisible()
  })

  it('LCI não mostra imposto e o título resgatado mostra o resgate', async () => {
    mockFetch({
      'GET /titulos-renda-fixa?data=2026-10-22': responde(
        lista([
          titulo({ id: 't2', tipo: 'lci', nome: 'LCI', irCentavos: 0, valorLiquidoCentavos: 110_000 }),
          titulo({
            id: 't3',
            nome: 'CDB antigo',
            resgate: { data: '2026-05-10', valorCentavos: 104_000 },
            valorBrutoCentavos: 0,
            iofCentavos: 0,
            irCentavos: 0,
            valorLiquidoCentavos: 0,
            rendimentoCentavos: 4_000,
          }),
        ]),
      ),
    })
    renderizar()
    const [lci, antigo] = within(await screen.findByRole('list', { name: 'Títulos' })).getAllByRole('listitem')
    expect(within(lci).queryByText('IR e IOF estimados')).toBeNull()
    expect(within(antigo).getByText(texto('Resgatado em 10/05/2026 por R$ 1.040,00'))).toBeVisible()
    expect(within(antigo).queryByRole('button', { name: 'Resgatar CDB antigo' })).toBeNull()
  })

  it('avisa quando ainda não há CDI e sem conta explica onde criar', async () => {
    mockFetch({ 'GET /titulos-renda-fixa?data=2026-10-22': responde(lista([], null)) })
    renderizar([])
    expect(await screen.findByText(/Ainda sem CDI do Banco Central/)).toBeVisible()
    expect(screen.getByText('Nenhuma conta de investimento.')).toBeVisible()
    expect(screen.queryByRole('button', { name: 'Novo título' })).toBeNull()
  })

  it('cadastra um título com a taxa em texto e avisa a tela de cima', async () => {
    mockFetch({ 'POST /titulos-renda-fixa': responde(titulo(), 201) })
    const { aoMudar } = renderizar()
    fireEvent.click(await screen.findByRole('button', { name: 'Novo título' }))
    const form = screen.getByRole('form', { name: 'Novo título' })
    fireEvent.change(within(form).getByLabelText('Tipo'), { target: { value: 'lci' } })
    fireEvent.change(within(form).getByLabelText('Nome'), { target: { value: 'LCI Banco Y' } })
    fireEvent.change(within(form).getByLabelText('Valor aplicado em reais'), { target: { value: '5.000,00' } })
    fireEvent.change(within(form).getByLabelText('Data da aplicação'), { target: { value: '2026-02-01' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Prefixado' }))
    fireEvent.change(within(form).getByLabelText('Taxa ao ano, em %'), { target: { value: '12,5' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Cadastrar título' }))

    await waitFor(() => expect(chamadas('POST /titulos-renda-fixa')).toHaveLength(1))
    expect(chamadas('POST /titulos-renda-fixa')[0]).toEqual({
      contaId: 'k1',
      tipo: 'lci',
      nome: 'LCI Banco Y',
      valorAplicadoCentavos: 500_000,
      dataAplicacao: '2026-02-01',
      vencimento: null,
      indexador: 'prefixado',
      taxa: '12.5',
    })
    await waitFor(() => expect(aoMudar).toHaveBeenCalled())
  })

  it('recusa taxa fora do formato sem chamar a API', async () => {
    mockFetch()
    renderizar()
    fireEvent.click(await screen.findByRole('button', { name: 'Novo título' }))
    const form = screen.getByRole('form', { name: 'Novo título' })
    fireEvent.change(within(form).getByLabelText('Nome'), { target: { value: 'X' } })
    fireEvent.change(within(form).getByLabelText('Valor aplicado em reais'), { target: { value: '10,00' } })
    fireEvent.change(within(form).getByLabelText('Quanto do CDI, em %'), { target: { value: '95%' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Cadastrar título' }))
    expect(await within(form).findByRole('alert')).toHaveTextContent('Taxa inválida')
    expect(chamadas('POST /titulos-renda-fixa')).toHaveLength(0)
  })

  it('edita o título com o formulário preenchido', async () => {
    mockFetch({ 'PUT /titulos-renda-fixa/t1': responde(titulo({ taxa: '115' })) })
    renderizar()
    fireEvent.click(await screen.findByRole('button', { name: 'Editar CDB Banco X' }))
    const form = screen.getByRole('form', { name: 'Editar CDB Banco X' })
    expect(within(form).getByLabelText('Valor aplicado em reais')).toHaveValue('1.000,00')
    expect(within(form).getByLabelText('Quanto do CDI, em %')).toHaveValue('110')
    fireEvent.change(within(form).getByLabelText('Quanto do CDI, em %'), { target: { value: '115' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Salvar alterações' }))
    await waitFor(() => expect(chamadas('PUT /titulos-renda-fixa/t1')[0]).toMatchObject({ taxa: '115', vencimento: '2028-01-10' }))
  })

  it('grava o valor que o banco mostrou e o resgate', async () => {
    mockFetch({
      'POST /titulos-renda-fixa/t1/marcacoes': () => Promise.resolve(new Response(null, { status: 201 })),
      'POST /titulos-renda-fixa/t1/resgate': responde(titulo({ resgate: { data: '2026-10-22', valorCentavos: 109_000 } })),
    })
    renderizar()
    fireEvent.click(await screen.findByRole('button', { name: 'Informar valor de CDB Banco X' }))
    let form = screen.getByRole('form', { name: 'Valor de CDB Banco X' })
    fireEvent.change(within(form).getByLabelText('Valor bruto em reais'), { target: { value: '1.095,50' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Salvar valor' }))
    await waitFor(() =>
      expect(chamadas('POST /titulos-renda-fixa/t1/marcacoes')).toEqual([{ data: '2026-10-22', valorCentavos: 109_550 }]),
    )

    await waitFor(() => expect(screen.queryByRole('form', { name: 'Valor de CDB Banco X' })).toBeNull())
    fireEvent.click(screen.getByRole('button', { name: 'Resgatar CDB Banco X' }))
    form = screen.getByRole('form', { name: 'Resgate de CDB Banco X' })
    fireEvent.change(within(form).getByLabelText('Valor recebido em reais'), { target: { value: '1.090,00' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Registrar resgate' }))
    await waitFor(() =>
      expect(chamadas('POST /titulos-renda-fixa/t1/resgate')).toEqual([{ data: '2026-10-22', valorCentavos: 109_000 }]),
    )
  })

  it('mostra a recusa do servidor ao gravar o valor do banco', async () => {
    mockFetch({
      'POST /titulos-renda-fixa/t1/marcacoes': responde({ erro: 'data deve ficar entre a aplicação e hoje' }, 400),
    })
    renderizar()
    fireEvent.click(await screen.findByRole('button', { name: 'Informar valor de CDB Banco X' }))
    const form = screen.getByRole('form', { name: 'Valor de CDB Banco X' })
    fireEvent.change(within(form).getByLabelText('Valor bruto em reais'), { target: { value: '10,00' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Salvar valor' }))
    expect(await within(form).findByRole('alert')).toHaveTextContent('data deve ficar entre a aplicação e hoje')
  })

  it('apaga em dois cliques', async () => {
    mockFetch({ 'DELETE /titulos-renda-fixa/t1': () => Promise.resolve(new Response(null, { status: 204 })) })
    renderizar()
    fireEvent.click(await screen.findByRole('button', { name: 'Apagar CDB Banco X' }))
    expect(chamadas('DELETE /titulos-renda-fixa/t1')).toHaveLength(0)
    fireEvent.click(screen.getByRole('button', { name: 'Confirmar' }))
    await waitFor(() => expect(chamadas('DELETE /titulos-renda-fixa/t1')).toHaveLength(1))
  })
})
