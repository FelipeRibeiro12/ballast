import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import Metas from './Metas'
import { acao } from './testeAcoes'

function meta(sobra: Partial<Record<string, unknown>> = {}): Record<string, unknown> {
  return {
    id: 'm1',
    titulo: 'Viagem',
    valorAlvoCentavos: 1_000_000,
    dataAlvo: '2027-03-31',
    aporteMensalCentavos: 150_000,
    acumuladoCentavos: 400_000,
    faltaCentavos: 600_000,
    atingida: false,
    prazoVencido: false,
    necessarioPorMesCentavos: 100_000,
    chegadaPeloPlano: '2027-01',
    ritmoMensalCentavos: 120_000,
    chegadaPeloRitmo: '2027-02',
    ...sobra,
  }
}

const celular = meta({
  id: 'm2',
  titulo: 'Celular',
  valorAlvoCentavos: 100_000,
  dataAlvo: null,
  aporteMensalCentavos: null,
  acumuladoCentavos: 100_000,
  faltaCentavos: 0,
  atingida: true,
  necessarioPorMesCentavos: null,
  chegadaPeloPlano: null,
  ritmoMensalCentavos: 0,
  chegadaPeloRitmo: null,
})

const aportes = [
  { id: 'a2', valorCentavos: -50_000, data: '2026-10-05' },
  { id: 'a1', valorCentavos: 150_000, data: '2026-10-01' },
]

function jsonResponse(corpo: unknown, status: number): Response {
  return new Response(JSON.stringify(corpo), { status, headers: { 'Content-Type': 'application/json' } })
}

type Rotas = Record<string, (corpo: unknown) => Promise<Response>>

const responde = (corpo: unknown, status = 200) => () => Promise.resolve(jsonResponse(corpo, status))
const semCorpo = () => Promise.resolve(new Response(null, { status: 204 }))

function mockFetch(rotas: Rotas = {}): void {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      const chave = `${init?.method ?? 'GET'} ${url}`
      const corpo: unknown = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined
      const rota = rotas[chave]
      if (rota) return rota(corpo)
      if (chave === 'GET /metas') return Promise.resolve(jsonResponse([meta(), celular], 200))
      if (chave === 'GET /metas/m1/aportes') return Promise.resolve(jsonResponse(aportes, 200))
      return Promise.reject(new Error(`rota sem mock: ${chave}`))
    }),
  )
}

function chamadas(): string[] {
  return vi.mocked(fetch).mock.calls.map(([url, init]) => `${init?.method ?? 'GET'} ${String(url)}`)
}

function corpoDe(chave: string): unknown {
  const chamada = vi.mocked(fetch).mock.calls.find(([url, init]) => `${init?.method ?? 'GET'} ${String(url)}` === chave)
  const corpo = chamada?.[1]?.body
  return typeof corpo === 'string' ? JSON.parse(corpo) : undefined
}

// O Intl separa símbolo e número com espaço sem quebra; o texto do teste usa espaço comum.
const texto = (valor: string): RegExp =>
  new RegExp(valor.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replace(/[ \u00a0]/g, '[\\s\\u00a0]'))

function renderizar() {
  const aoSessaoExpirar = vi.fn()
  render(<Metas aoSessaoExpirar={aoSessaoExpirar} />)
  return { aoSessaoExpirar }
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-10-22T12:00:00-03:00'))
})

afterEach(() => {
  vi.useRealTimers()
})

describe('Metas', () => {
  it('mostra o acumulado, o que falta e as três respostas de quando chega', async () => {
    mockFetch()
    renderizar()

    const lista = await screen.findByRole('list', { name: 'Metas' })
    const [viagem, celularItem] = within(lista).getAllByRole('listitem')
    expect(within(viagem).getByText('Viagem')).toBeVisible()
    expect(within(viagem).getByText(texto('R$ 4.000,00 de R$ 10.000,00'))).toBeVisible()
    expect(within(viagem).getByText(texto('Faltam R$ 6.000,00'))).toBeVisible()
    expect(within(viagem).getByText(texto('Para chegar em 31/03/2027: R$ 1.000,00 por mês'))).toBeVisible()
    expect(within(viagem).getByText(texto('Guardando R$ 1.500,00 por mês, chega em janeiro de 2027'))).toBeVisible()
    expect(
      within(viagem).getByText(texto('No ritmo dos últimos 3 meses (R$ 1.200,00 por mês), chega em fevereiro de 2027')),
    ).toBeVisible()
    expect(within(celularItem).getByText('Atingida')).toBeVisible()
    expect(within(celularItem).queryByText(/chega em/)).toBeNull()
  })

  it('avisa quando o prazo passou e quando não há ritmo', async () => {
    const vencida = meta({ prazoVencido: true, necessarioPorMesCentavos: null, chegadaPeloRitmo: null, ritmoMensalCentavos: 0 })
    mockFetch({ 'GET /metas': responde([vencida]) })
    renderizar()

    const lista = await screen.findByRole('list', { name: 'Metas' })
    expect(within(lista).getByText('Prazo vencido')).toBeVisible()
    expect(within(lista).getByText('Sem aporte nos últimos 3 meses')).toBeVisible()
  })

  it('liga as telas de orçamento e metas', async () => {
    mockFetch()
    renderizar()
    await screen.findByRole('list', { name: 'Metas' })

    const atalhos = screen.getByRole('navigation', { name: 'Planejamento' })
    expect(within(atalhos).getByRole('link', { name: 'Orçamento' })).toHaveAttribute('href', '#/orcamento')
    expect(within(atalhos).getByRole('link', { name: 'Metas' })).toHaveAttribute('aria-current', 'page')
  })

  it('mostra o vazio com a ação de criar', async () => {
    mockFetch({ 'GET /metas': responde([]) })
    renderizar()

    expect(await screen.findByText('Nenhuma meta ainda.')).toBeVisible()
  })

  it('cria uma meta com data e aporte opcionais', async () => {
    mockFetch({ 'POST /metas': responde(meta(), 201) })
    renderizar()
    await screen.findByRole('list', { name: 'Metas' })

    fireEvent.click(screen.getByRole('button', { name: 'Nova meta' }))
    const form = screen.getByRole('form', { name: 'Meta' })
    fireEvent.change(within(form).getByLabelText('Título'), { target: { value: 'Carro' } })
    fireEvent.change(within(form).getByLabelText('Valor alvo'), { target: { value: '50.000,00' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Salvar meta' }))

    await waitFor(() =>
      expect(corpoDe('POST /metas')).toEqual({
        titulo: 'Carro',
        valorAlvoCentavos: 5_000_000,
        dataAlvo: null,
        aporteMensalCentavos: null,
      }),
    )
    await waitFor(() => expect(chamadas().filter((c) => c === 'GET /metas')).toHaveLength(2))
  })

  it('edita a meta com os valores atuais no formulário', async () => {
    mockFetch({ 'PUT /metas/m1': responde(meta(), 200) })
    renderizar()
    await screen.findByRole('list', { name: 'Metas' })

    fireEvent.click(await acao('Editar Viagem'))
    const form = screen.getByRole('form', { name: 'Meta' })
    expect(within(form).getByLabelText('Valor alvo')).toHaveValue('10.000,00')
    expect(within(form).getByLabelText('Data alvo (opcional)')).toHaveValue('2027-03-31')
    expect(within(form).getByLabelText('Aporte por mês (opcional)')).toHaveValue('1.500,00')
    fireEvent.change(within(form).getByLabelText('Data alvo (opcional)'), { target: { value: '' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Salvar meta' }))

    await waitFor(() =>
      expect(corpoDe('PUT /metas/m1')).toEqual({
        titulo: 'Viagem',
        valorAlvoCentavos: 1_000_000,
        dataAlvo: null,
        aporteMensalCentavos: 150_000,
      }),
    )
  })

  it('aporta o valor do plano por padrão, com a data de hoje', async () => {
    mockFetch({ 'POST /metas/m1/aportes': responde(meta(), 201) })
    renderizar()
    await screen.findByRole('list', { name: 'Metas' })

    fireEvent.click(screen.getByRole('button', { name: 'Aportar em Viagem' }))
    const form = screen.getByRole('form', { name: 'Aporte' })
    expect(within(form).getByLabelText('Valor')).toHaveValue('1.500,00')
    expect(within(form).getByLabelText('Data')).toHaveValue('2026-10-22')
    fireEvent.click(within(form).getByRole('button', { name: 'Registrar' }))

    await waitFor(() => expect(corpoDe('POST /metas/m1/aportes')).toEqual({ valorCentavos: 150_000, data: '2026-10-22' }))
  })

  it('retirada vai com sinal negativo e mostra a recusa do servidor', async () => {
    mockFetch({ 'POST /metas/m1/aportes': responde({ erro: 'retirada maior que o acumulado da meta' }, 400) })
    renderizar()
    await screen.findByRole('list', { name: 'Metas' })

    fireEvent.click(screen.getByRole('button', { name: 'Aportar em Viagem' }))
    const form = screen.getByRole('form', { name: 'Aporte' })
    fireEvent.click(within(form).getByLabelText('Retirar'))
    fireEvent.change(within(form).getByLabelText('Valor'), { target: { value: '50' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Registrar' }))

    expect(await within(form).findByRole('alert')).toHaveTextContent('retirada maior que o acumulado da meta')
    expect(corpoDe('POST /metas/m1/aportes')).toEqual({ valorCentavos: -5_000, data: '2026-10-22' })
  })

  it('recusa valor fora do formato sem chamar a API', async () => {
    mockFetch()
    renderizar()
    await screen.findByRole('list', { name: 'Metas' })

    fireEvent.click(screen.getByRole('button', { name: 'Aportar em Viagem' }))
    const form = screen.getByRole('form', { name: 'Aporte' })
    fireEvent.change(within(form).getByLabelText('Valor'), { target: { value: 'abc' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Registrar' }))

    expect(within(form).getByRole('alert')).toHaveTextContent('Valor inválido. Use o formato 1.234,56.')
    expect(chamadas().some((c) => c.startsWith('POST'))).toBe(false)
  })

  it('lista os aportes e apaga um em dois cliques', async () => {
    mockFetch({ 'DELETE /metas/m1/aportes/a1': semCorpo })
    renderizar()
    await screen.findByRole('list', { name: 'Metas' })

    fireEvent.click(await acao('Ver aportes de Viagem'))
    const historico = await screen.findByRole('list', { name: 'Aportes de Viagem' })
    const [retirada, deposito] = within(historico).getAllByRole('listitem')
    expect(retirada).toHaveTextContent(texto('− R$ 500,00'))
    expect(retirada).toHaveTextContent('05/10/2026')
    expect(deposito).toHaveTextContent(texto('+ R$ 1.500,00'))

    fireEvent.click(within(deposito).getByRole('button', { name: 'Apagar aporte de 01/10/2026' }))
    expect(chamadas().some((c) => c.startsWith('DELETE'))).toBe(false)
    fireEvent.click(within(deposito).getByRole('button', { name: 'Confirmar apagar aporte de 01/10/2026' }))

    await waitFor(() => expect(chamadas()).toContain('DELETE /metas/m1/aportes/a1'))
  })

  it('apaga a meta em dois cliques', async () => {
    mockFetch({ 'DELETE /metas/m1': semCorpo })
    renderizar()
    await screen.findByRole('list', { name: 'Metas' })

    fireEvent.click(await acao('Apagar Viagem'))
    expect(chamadas().some((c) => c.startsWith('DELETE'))).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: 'Confirmar apagar Viagem e os aportes dela' }))

    await waitFor(() => expect(chamadas()).toContain('DELETE /metas/m1'))
  })

  it('avisa quem perdeu a sessão', async () => {
    mockFetch({ 'GET /metas': responde({ erro: 'não autenticado' }, 401) })
    const { aoSessaoExpirar } = renderizar()

    await waitFor(() => expect(aoSessaoExpirar).toHaveBeenCalled())
  })
})
