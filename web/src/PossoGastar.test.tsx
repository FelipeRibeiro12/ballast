import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import PossoGastar from './PossoGastar'

const base = {
  hoje: '2026-03-15',
  ate: '2026-04-04',
  modo: 'dia',
  diaLimite: 4,
  incluirBeneficios: false,
  incluirTerceiros: false,
  rendimentoCofrinhoCentavos: 0,
  livreCentavos: 60_000,
  menorSaldoEm: '2026-03-25',
  dias: 21,
  porDiaCentavos: 2_857,
  vencidasCentavos: 0,
  beneficios: [],
}

function jsonResponse(corpo: unknown, status: number): Response {
  return new Response(JSON.stringify(corpo), { status, headers: { 'Content-Type': 'application/json' } })
}

function mockFetch(corpo: unknown, status = 200): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(() => Promise.resolve(jsonResponse(corpo, status))),
  )
}

// O Intl separa símbolo e número com espaço sem quebra; o texto do teste usa espaço comum.
const texto = (valor: string): RegExp =>
  new RegExp(valor.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replace(/[ \u00a0]/g, '[\\s\\u00a0]'))

function renderizar(versao = 0) {
  const aoSessaoExpirar = vi.fn()
  const resultado = render(<PossoGastar versao={versao} aoSessaoExpirar={aoSessaoExpirar} />)
  return { aoSessaoExpirar, ...resultado }
}

describe('PossoGastar', () => {
  it('mostra quanto dá para gastar até o dia escolhido e quanto por dia', async () => {
    mockFetch(base)
    renderizar()

    const cartao = await screen.findByRole('region', { name: 'Posso gastar' })
    expect(await within(cartao).findByText(texto('R$ 600,00'), { selector: 'strong' })).toBeVisible()
    expect(within(cartao).getByText('até 04/04')).toBeVisible()
    expect(within(cartao).getByText(texto('R$ 28,57 por dia nos próximos 21 dias'))).toBeVisible()
    expect(within(cartao).getByText('O saldo chega ao menor valor em 25/03.')).toBeVisible()
    expect(vi.mocked(fetch).mock.calls.map(([url]) => String(url))).toContain('/posso-gastar')
  })

  it('sem dia escolhido nem cartão, conta até o fim do mês e diz isso', async () => {
    mockFetch({ ...base, modo: 'fim-do-mes', diaLimite: null, ate: '2026-03-31', dias: 17, menorSaldoEm: '2026-03-15' })
    renderizar()

    const cartao = await screen.findByRole('region', { name: 'Posso gastar' })
    expect(await within(cartao).findByText('até 31/03, fim do mês')).toBeVisible()
    // O menor saldo hoje não precisa de aviso.
    expect(within(cartao).queryByText(/menor valor/)).toBeNull()
  })

  it('o último dia da janela fala de hoje, não de dias', async () => {
    mockFetch({ ...base, ate: '2026-03-15', dias: 1, porDiaCentavos: 60_000, menorSaldoEm: '2026-03-15' })
    renderizar()

    const cartao = await screen.findByRole('region', { name: 'Posso gastar' })
    expect(await within(cartao).findByText('até hoje')).toBeVisible()
    expect(within(cartao).queryByText(/por dia/)).toBeNull()
  })

  it('quando falta dinheiro, diz quanto falta em vez de quanto sobra', async () => {
    mockFetch({ ...base, livreCentavos: -25_000, porDiaCentavos: null, menorSaldoEm: '2026-03-28' })
    renderizar()

    const cartao = await screen.findByRole('region', { name: 'Posso gastar' })
    expect(await within(cartao).findByText(texto('Faltam R$ 250,00'), { selector: 'strong' })).toBeVisible()
    expect(within(cartao).getByText('para cobrir o que está previsto até 04/04. O saldo fica negativo em 28/03.')).toBeVisible()
    expect(within(cartao).queryByText(/por dia/)).toBeNull()
  })

  it('avisa quando o valor conta com previstas vencidas', async () => {
    mockFetch({ ...base, vencidasCentavos: -15_000 })
    renderizar()

    const cartao = await screen.findByRole('region', { name: 'Posso gastar' })
    expect(await within(cartao).findByText(texto('Já desconta R$ 150,00 de despesas vencidas que ainda não foram pagas.'))).toBeVisible()
  })

  it('mostra cada benefício à parte, fora do valor livre', async () => {
    mockFetch({ ...base, beneficios: [{ contaId: 'vr', nome: 'VR', livreCentavos: 55_000 }] })
    renderizar()

    const lista = await screen.findByRole('list', { name: 'Benefícios' })
    const [vr] = within(lista).getAllByRole('listitem')
    expect(vr).toHaveTextContent('VR')
    expect(vr).toHaveTextContent(texto('R$ 550,00'))
    expect(within(lista.parentElement as HTMLElement).getByText('Só nas categorias de cada benefício.')).toBeVisible()
  })

  it('busca de novo quando os lançamentos mudam', async () => {
    mockFetch(base)
    const { rerender } = renderizar()
    await screen.findByText(texto('R$ 600,00'), { selector: 'strong' })

    rerender(<PossoGastar versao={1} aoSessaoExpirar={vi.fn()} />)

    await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(2))
  })

  it('erro avisa sem quebrar o Início, e a sessão expirada volta ao login', async () => {
    mockFetch({ erro: 'projeção fora do limite' }, 400)
    renderizar()
    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível calcular quanto dá para gastar.')

    mockFetch({ erro: 'sessão expirada' }, 401)
    const { aoSessaoExpirar } = renderizar()
    await vi.waitFor(() => expect(aoSessaoExpirar).toHaveBeenCalled())
  })

  it('a engrenagem grava o dia escolhido e recarrega o cálculo', async () => {
    const chamadas: { url: string; metodo: string; corpo: string | null }[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        chamadas.push({ url: String(url), metodo: init?.method ?? 'GET', corpo: (init?.body as string | undefined) ?? null })
        if (init?.method === 'PUT') return Promise.resolve(new Response(null, { status: 204 }))
        return Promise.resolve(jsonResponse(base, 200))
      }),
    )
    renderizar()

    fireEvent.click(await screen.findByRole('button', { name: 'Configurar o Posso gastar' }))
    fireEvent.change(screen.getByLabelText('Contar entradas e saídas até o dia'), { target: { value: '10' } })
    fireEvent.click(screen.getByRole('button', { name: 'Salvar' }))

    await waitFor(() => expect(chamadas.filter((c) => c.url === '/posso-gastar')).toHaveLength(2))
    const gravacao = chamadas.find((c) => c.metodo === 'PUT')
    expect(gravacao?.url).toBe('/posso-gastar/configuracao')
    expect(gravacao?.corpo).toBe(JSON.stringify({ diaLimite: 10, incluirBeneficios: false, incluirTerceiros: false }))
  })

  it('escolher o ciclo da fatura manda diaLimite nulo', async () => {
    const corpos: (string | null)[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn((_url: string, init?: RequestInit) => {
        if (init?.method === 'PUT') {
          corpos.push((init.body as string | undefined) ?? null)
          return Promise.resolve(new Response(null, { status: 204 }))
        }
        return Promise.resolve(jsonResponse(base, 200))
      }),
    )
    renderizar()

    fireEvent.click(await screen.findByRole('button', { name: 'Configurar o Posso gastar' }))
    fireEvent.change(screen.getByLabelText('Contar entradas e saídas até o dia'), { target: { value: '' } })
    fireEvent.click(screen.getByRole('button', { name: 'Salvar' }))

    await waitFor(() =>
      expect(corpos).toEqual([JSON.stringify({ diaLimite: null, incluirBeneficios: false, incluirTerceiros: false })]),
    )
  })

  it('as chaves de benefícios e terceiros vão junto na gravação', async () => {
    const corpos: (string | null)[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn((_url: string, init?: RequestInit) => {
        if (init?.method === 'PUT') {
          corpos.push((init.body as string | undefined) ?? null)
          return Promise.resolve(new Response(null, { status: 204 }))
        }
        return Promise.resolve(jsonResponse(base, 200))
      }),
    )
    renderizar()

    fireEvent.click(await screen.findByRole('button', { name: 'Configurar o Posso gastar' }))
    fireEvent.click(screen.getByRole('checkbox', { name: 'Benefícios' }))
    fireEvent.click(screen.getByRole('checkbox', { name: 'Terceiros' }))
    fireEvent.click(screen.getByRole('button', { name: 'Salvar' }))

    await waitFor(() =>
      expect(corpos).toEqual([JSON.stringify({ diaLimite: 4, incluirBeneficios: true, incluirTerceiros: true })]),
    )
  })

  it('abre a engrenagem com as chaves que já estavam ligadas', async () => {
    mockFetch({ ...base, incluirBeneficios: true })
    renderizar()

    fireEvent.click(await screen.findByRole('button', { name: 'Configurar o Posso gastar' }))

    expect(screen.getByRole('checkbox', { name: 'Benefícios' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'Terceiros' })).not.toBeChecked()
  })
})
