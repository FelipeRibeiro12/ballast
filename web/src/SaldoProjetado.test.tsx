import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import SaldoProjetado from './SaldoProjetado'
import type { Conta } from './api'
import { formatarCentavos } from './dinheiro'

function conta(id: string, nome: string): Conta {
  return {
    id,
    nome,
    tipo: 'corrente',
    moeda: 'BRL',
    saldoInicialCentavos: 0,
    saldoCentavos: 0,
    arquivada: false,
    criadaEm: '2026-10-19T12:00:00Z',
    cartao: null,
    categoriasPermitidas: null,
    percentualCdi: null,
  }
}

const nubank = conta('c1', 'Nubank')
const carteira = conta('c2', 'Carteira')

function dia(data: string, entradas: number, saidas: number, saldo: number) {
  return { data, entradasCentavos: entradas, saidasCentavos: saidas, saldoCentavos: saldo }
}

// Fica negativo no dia 21 e chega ao menor saldo no dia 22; só 21, 22 e 23 têm movimento.
function serie(sobra: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    inicio: '2026-10-20',
    fim: '2026-10-24',
    contaId: null,
    saldoAtualCentavos: 10000,
    vencidasCentavos: 0,
    menorSaldo: { data: '2026-10-22', saldoCentavos: -5000 },
    dias: [
      dia('2026-10-20', 0, 0, 10000),
      dia('2026-10-21', 0, 12000, -2000),
      dia('2026-10-22', 0, 3000, -5000),
      dia('2026-10-23', 20000, 0, 15000),
      dia('2026-10-24', 0, 0, 15000),
    ],
    ...sobra,
  }
}

function positiva(): Record<string, unknown> {
  return serie({
    menorSaldo: { data: '2026-10-20', saldoCentavos: 10000 },
    dias: [dia('2026-10-20', 0, 0, 10000), dia('2026-10-21', 5000, 0, 15000)],
    fim: '2026-10-21',
  })
}

function jsonResponse(corpo: unknown, status: number): Response {
  return new Response(JSON.stringify(corpo), { status, headers: { 'Content-Type': 'application/json' } })
}

const responde = (corpo: unknown, status = 200) => () => Promise.resolve(jsonResponse(corpo, status))

type Rota = () => Promise<Response>

function mockFetch(rota: Rota): void {
  vi.stubGlobal('fetch', vi.fn(rota))
}

function urlsPedidas(): string[] {
  return vi.mocked(fetch).mock.calls.map(([url]) => String(url))
}

// O Intl separa símbolo e número com espaço sem quebra; o texto do teste usa espaço comum.
const texto = (valor: string): RegExp =>
  new RegExp(valor.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replace(/[ \u00a0]/g, '[\\s\\u00a0]'))

function renderizar(sobra: Partial<Parameters<typeof SaldoProjetado>[0]> = {}) {
  const aoSessaoExpirar = vi.fn()
  const props = { aoSessaoExpirar, contas: [nubank, carteira], versaoDeSaldo: 0, ...sobra }
  const resultado = render(<SaldoProjetado {...props} />)
  return { ...resultado, aoSessaoExpirar, props }
}

describe('SaldoProjetado', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('mostra carregando e pede 90 dias por padrão', async () => {
    mockFetch(responde(serie()))
    renderizar()
    expect(screen.getByText(/carregando/i)).toBeVisible()
    expect(await screen.findByText(/menor saldo/i)).toBeVisible()
    expect(urlsPedidas()).toEqual(['/saldo-projetado?dias=90'])
    expect(screen.getByRole('button', { name: '90 dias' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: '30 dias' })).toHaveAttribute('aria-pressed', 'false')
  })

  it('pede o período certo ao trocar para 30 e para 180 dias', async () => {
    mockFetch(responde(serie()))
    renderizar()
    await screen.findByText(/menor saldo/i)

    fireEvent.click(screen.getByRole('button', { name: '30 dias' }))
    await waitFor(() => expect(urlsPedidas()).toContain('/saldo-projetado?dias=30'))
    expect(await screen.findByText(/menor saldo/i)).toBeVisible()
    expect(screen.getByRole('button', { name: '30 dias' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: '90 dias' })).toHaveAttribute('aria-pressed', 'false')

    fireEvent.click(screen.getByRole('button', { name: '180 dias' }))
    await waitFor(() => expect(urlsPedidas()).toContain('/saldo-projetado?dias=180'))
    expect(await screen.findByText(/menor saldo/i)).toBeVisible()
  })

  it('manda contaId ao filtrar e volta a pedir todas as contas', async () => {
    mockFetch(responde(serie()))
    renderizar()
    await screen.findByText(/menor saldo/i)

    const select = screen.getByLabelText(/conta/i)
    expect(within(select).getByRole('option', { name: 'Todas as contas ativas' })).toBeVisible()
    fireEvent.change(select, { target: { value: 'c2' } })
    await waitFor(() => expect(urlsPedidas()).toContain('/saldo-projetado?dias=90&contaId=c2'))

    fireEvent.change(select, { target: { value: '' } })
    await waitFor(() => expect(urlsPedidas().filter((u) => u === '/saldo-projetado?dias=90')).toHaveLength(2))
  })

  it('lista o benefício no filtro e projeta só ele quando escolhido', async () => {
    mockFetch(responde(serie()))
    const vr: Conta = { ...conta('b1', 'VR'), tipo: 'beneficio', categoriasPermitidas: [] }
    renderizar({ contas: [nubank, vr] })
    await screen.findByText(/menor saldo/i)

    const select = screen.getByLabelText(/conta/i)
    expect(within(select).getByRole('option', { name: 'VR' })).toBeVisible()
    // A API já deixa o benefício de fora da projeção sem contaId; o cliente não filtra nada.
    expect(urlsPedidas()).toEqual(['/saldo-projetado?dias=90'])
    fireEvent.change(select, { target: { value: 'b1' } })
    await waitFor(() => expect(urlsPedidas()).toContain('/saldo-projetado?dias=90&contaId=b1'))
  })

  it('volta para todas as contas quando a escolhida sai da lista de ativas', async () => {
    mockFetch(responde(serie()))
    const { rerender, props } = renderizar()
    await screen.findByText(/menor saldo/i)
    fireEvent.change(screen.getByLabelText(/conta/i), { target: { value: 'c2' } })
    await waitFor(() => expect(urlsPedidas()).toContain('/saldo-projetado?dias=90&contaId=c2'))

    rerender(<SaldoProjetado {...props} contas={[nubank]} />)
    await waitFor(() => expect(urlsPedidas().at(-1)).toBe('/saldo-projetado?dias=90'))
    expect(screen.getByLabelText(/conta/i)).toHaveValue('')
  })

  it('mostra saldo atual, saldo no fim do período e menor saldo com a data', async () => {
    mockFetch(responde(serie()))
    renderizar()
    // Rótulo e valor ficam em dt e dd vizinhos: o resumo é uma lista de definição.
    expect((await screen.findByText('Saldo atual')).nextSibling).toHaveTextContent(texto(formatarCentavos(10000)))
    expect(screen.getByText('Saldo em 24/10').nextSibling).toHaveTextContent(texto(formatarCentavos(15000)))
    expect(screen.getByText('Menor saldo em 22/10').nextSibling).toHaveTextContent(texto(formatarCentavos(-5000)))
  })

  it('avisa em texto o primeiro dia em que o saldo fica negativo', async () => {
    mockFetch(responde(serie()))
    renderizar()
    expect(await screen.findByText('Fica negativo em 21/10')).toBeVisible()
  })

  it('não avisa de negativo quando o saldo nunca passa de zero para baixo', async () => {
    mockFetch(responde(positiva()))
    renderizar()
    await screen.findByText(/menor saldo/i)
    expect(screen.queryByText(/fica negativo/i)).not.toBeInTheDocument()
  })

  it.each([
    [-1550, '-'],
    [2000, '+'],
  ])('avisa das previstas vencidas com sinal: %i', async (vencidas, sinal) => {
    mockFetch(responde(serie({ vencidasCentavos: vencidas })))
    renderizar()
    const valor = formatarCentavos(vencidas)
    const esperado = sinal === '+' ? `+${valor}` : valor
    expect(await screen.findByText(texto(`Inclui ${esperado} de previstas vencidas`))).toBeVisible()
  })

  it('não avisa de vencidas quando a soma é zero', async () => {
    mockFetch(responde(serie()))
    renderizar()
    await screen.findByText(/menor saldo/i)
    expect(screen.queryByText(/previstas vencidas/i)).not.toBeInTheDocument()
  })

  it('lista só os dias com movimento', async () => {
    mockFetch(responde(serie()))
    renderizar()
    const lista = await screen.findByRole('list', { name: /dias com movimento/i })
    const itens = within(lista).getAllByRole('listitem')
    expect(itens).toHaveLength(3)
    expect(itens[0]).toHaveTextContent('21/10')
    expect(itens[0]).toHaveTextContent(texto(`Saídas: −${formatarCentavos(12000)}`))
    expect(itens[0]).toHaveTextContent(texto(`Saldo: ${formatarCentavos(-2000)}`))
    expect(itens[2]).toHaveTextContent('23/10')
    expect(itens[2]).toHaveTextContent(texto(`Entradas: +${formatarCentavos(20000)}`))
    expect(lista).not.toHaveTextContent('20/10')
    expect(lista).not.toHaveTextContent('24/10')
  })

  it('diz que não há movimento quando nenhum dia tem entrada ou saída', async () => {
    mockFetch(
      responde(
        serie({
          menorSaldo: { data: '2026-10-20', saldoCentavos: 10000 },
          dias: [dia('2026-10-20', 0, 0, 10000), dia('2026-10-21', 0, 0, 10000)],
        }),
      ),
    )
    renderizar()
    expect(await screen.findByText(/nenhum dia com movimento/i)).toBeVisible()
    expect(screen.queryByRole('list', { name: /dias com movimento/i })).not.toBeInTheDocument()
  })

  it('desenha uma polyline com um ponto por dia, o menor saldo e a linha do zero', async () => {
    mockFetch(responde(serie()))
    renderizar()
    const grafico = await screen.findByRole('img', { name: /saldo projetado/i })
    // O SVG não tem papéis por elemento, então aqui a consulta é pela forma desenhada.
    const polyline = grafico.querySelector('polyline')
    expect(polyline?.getAttribute('points')?.trim().split(/\s+/)).toHaveLength(5)
    expect(grafico.querySelectorAll('circle')).toHaveLength(1)
    expect(grafico.querySelector('line[data-zero]')).not.toBeNull()
    expect(grafico).toHaveTextContent('20/10')
    expect(grafico).toHaveTextContent('24/10')
  })

  it('não desenha a linha do zero quando o saldo não cruza zero', async () => {
    mockFetch(responde(positiva()))
    renderizar()
    const grafico = await screen.findByRole('img', { name: /saldo projetado/i })
    expect(grafico.querySelector('line[data-zero]')).toBeNull()
  })

  it('descreve saldo do primeiro dia, do último e menor saldo no aria-label do gráfico', async () => {
    mockFetch(responde(serie()))
    renderizar()
    const grafico = await screen.findByRole('img')
    const rotulo = grafico.getAttribute('aria-label') ?? ''
    expect(rotulo).toMatch(texto(`começa com ${formatarCentavos(10000)}`))
    expect(rotulo).toMatch(texto(`termina com ${formatarCentavos(15000)}`))
    expect(rotulo).toMatch(texto(`menor saldo ${formatarCentavos(-5000)} em 22/10`))
  })

  it('busca de novo quando a versão do saldo sobe', async () => {
    mockFetch(responde(serie()))
    const { rerender, props } = renderizar()
    await screen.findByText(/menor saldo/i)
    expect(urlsPedidas()).toHaveLength(1)

    mockFetch(responde(serie({ saldoAtualCentavos: 77700 })))
    rerender(<SaldoProjetado {...props} versaoDeSaldo={1} />)
    expect((await screen.findByText('Saldo atual')).nextSibling).toHaveTextContent(texto(formatarCentavos(77700)))
  })

  it('busca de novo quando a lista de contas ativas muda', async () => {
    mockFetch(responde(serie()))
    const { rerender, props } = renderizar()
    await screen.findByText(/menor saldo/i)

    rerender(<SaldoProjetado {...props} contas={[nubank, carteira, conta('c3', 'Poupança')]} />)
    await waitFor(() => expect(urlsPedidas()).toHaveLength(2))
    expect(screen.getByRole('option', { name: 'Poupança' })).toBeInTheDocument()
  })

  it('não busca de novo quando chega outra lista com as mesmas contas', async () => {
    mockFetch(responde(serie()))
    const { rerender, props } = renderizar()
    await screen.findByText(/menor saldo/i)

    rerender(<SaldoProjetado {...props} contas={[{ ...nubank, saldoCentavos: 5 }, carteira]} />)
    await new Promise((resolver) => setTimeout(resolver, 20))
    expect(urlsPedidas()).toHaveLength(1)
  })

  it('chama aoSessaoExpirar no 401', async () => {
    mockFetch(responde({ erro: 'x' }, 401))
    const { aoSessaoExpirar } = renderizar()
    await waitFor(() => expect(aoSessaoExpirar).toHaveBeenCalledTimes(1))
  })

  it('mostra o erro e tenta de novo', async () => {
    mockFetch(responde({ erro: 'x' }, 500))
    renderizar()
    expect(await screen.findByRole('alert')).toHaveTextContent(/não foi possível carregar o saldo projetado/i)

    mockFetch(responde(serie()))
    fireEvent.click(screen.getByRole('button', { name: /tentar de novo/i }))
    expect(await screen.findByText(/menor saldo/i)).toBeVisible()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('mostra mensagem de indisponível no 503', async () => {
    mockFetch(responde({ erro: 'x' }, 503))
    renderizar()
    expect(await screen.findByRole('alert')).toHaveTextContent(/serviço indisponível/i)
  })

  it('mostra a mensagem do servidor no 400', async () => {
    mockFetch(responde({ erro: 'projeção fora do limite' }, 400))
    renderizar()
    expect(await screen.findByRole('alert')).toHaveTextContent('projeção fora do limite')
  })

  it('avisa quando a conta pedida não existe mais', async () => {
    mockFetch(responde({ erro: 'conta não encontrada' }, 404))
    renderizar()
    expect(await screen.findByRole('alert')).toHaveTextContent(/essa conta não existe mais/i)
  })

  it('mostra erro quando o corpo foge do contrato', async () => {
    mockFetch(responde({ inicio: 'x' }))
    renderizar()
    expect(await screen.findByRole('alert')).toBeVisible()
  })
})
