import { render, screen, within } from '@testing-library/react'
import ResumoDasCarteiras from './ResumoDasCarteiras'

function json(corpo: unknown, status = 200): Response {
  return new Response(JSON.stringify(corpo), { status, headers: { 'Content-Type': 'application/json' } })
}

const total = {
  valorUsdCentavos: 150_000,
  valorBrlCentavos: 825_000,
  variacao24hUsdCentavos: 14_600,
  variacao24hBrlCentavos: 80_300,
  variacao24hPercentual: '10.78',
  variacaoParcial: false,
}

const cofre = {
  id: 'w1',
  rede: 'ethereum',
  rotulo: 'Cofre',
  valorUsdCentavos: 100_000,
  valorBrlCentavos: 550_000,
  variacao24hUsdCentavos: 14_600,
  variacao24hBrlCentavos: 80_300,
  variacao24hPercentual: '17.10',
  variacaoParcial: false,
}

const semRotulo = {
  id: 'w2',
  rede: 'solana',
  rotulo: null,
  valorUsdCentavos: 50_000,
  valorBrlCentavos: null,
  variacao24hUsdCentavos: -2_500,
  variacao24hBrlCentavos: null,
  variacao24hPercentual: null,
  variacaoParcial: true,
}

function mockResumo(resposta: () => Response): void {
  vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(resposta())))
}

function renderizar(): { aoSessaoExpirar: () => void } {
  const aoSessaoExpirar = vi.fn()
  render(<ResumoDasCarteiras versao={0} aoSessaoExpirar={aoSessaoExpirar} />)
  return { aoSessaoExpirar }
}

// O Intl separa símbolo e número com espaço sem quebra; o texto do teste usa espaço comum.
const texto = (valor: string): RegExp =>
  new RegExp(valor.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replace(/[ \u00a0]/g, '[\\s\\u00a0]'))

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('ResumoDasCarteiras', () => {
  it('mostra o total em dólar e real com a variação do dia, sinal e percentual', async () => {
    mockResumo(() => json({ carteiras: [cofre], total }))
    renderizar()

    const resumo = await screen.findByRole('region', { name: 'Total das carteiras' })
    expect(within(resumo).getByText(texto('US$ 1.500,00'))).toBeVisible()
    expect(within(resumo).getByText(texto('R$ 8.250,00'))).toBeVisible()
    expect(within(resumo).getByText(texto('+US$ 146,00'))).toBeVisible()
    expect(within(resumo).getByText(texto('(+R$ 803,00)'))).toBeVisible()
    expect(within(resumo).getByText(texto('+10,78%'), { exact: false })).toBeVisible()
    expect(within(resumo).queryByText(/variação parcial/)).toBeNull()
  })

  it('mostra cada carteira pelo rótulo, ou pela rede quando não tem rótulo', async () => {
    mockResumo(() => json({ carteiras: [cofre, semRotulo], total }))
    renderizar()

    const lista = await screen.findByRole('list', { name: 'Valor por carteira' })
    const itens = within(lista).getAllByRole('listitem')
    expect(itens).toHaveLength(2)
    expect(within(itens[0]).getByText('Cofre')).toBeVisible()
    expect(within(itens[0]).getByText(texto('US$ 1.000,00'))).toBeVisible()
    expect(within(itens[0]).getByText(texto('R$ 5.500,00'))).toBeVisible()
    expect(within(itens[1]).getByText('Solana', { selector: 'strong' })).toBeVisible()
  })

  it('variação negativa leva sinal de menos e percentual nulo vira hífen', async () => {
    mockResumo(() => json({ carteiras: [semRotulo], total: { ...total, variacao24hUsdCentavos: -2_500, variacao24hPercentual: null } }))
    renderizar()

    const resumo = await screen.findByRole('region', { name: 'Total das carteiras' })
    expect(within(resumo).getByText(texto('-US$ 25,00'))).toBeVisible()
    expect(within(resumo).getByText(/· -$/)).toBeVisible()
  })

  it('sem cotação do dólar mostra só o valor em dólar', async () => {
    mockResumo(() =>
      json({
        carteiras: [semRotulo],
        total: { ...total, valorBrlCentavos: null, variacao24hBrlCentavos: null },
      }),
    )
    renderizar()

    const resumo = await screen.findByRole('region', { name: 'Total das carteiras' })
    expect(within(resumo).getByText(texto('US$ 1.500,00'))).toBeVisible()
    expect(within(resumo).queryByText(/R\$/)).toBeNull()
    const lista = screen.getByRole('list', { name: 'Valor por carteira' })
    expect(within(lista).queryByText(/R\$/)).toBeNull()
  })

  it('avisa variação parcial na carteira que ficou sem base', async () => {
    mockResumo(() => json({ carteiras: [cofre, semRotulo], total: { ...total, variacaoParcial: true } }))
    renderizar()

    const lista = await screen.findByRole('list', { name: 'Valor por carteira' })
    const itens = within(lista).getAllByRole('listitem')
    expect(within(itens[0]).queryByText(/variação parcial/)).toBeNull()
    expect(within(itens[1]).getByText(/variação parcial/)).toBeVisible()
    const resumo = screen.getByRole('region', { name: 'Total das carteiras' })
    expect(within(resumo).getByText(/variação parcial/)).toBeVisible()
  })

  it('mostra rótulo de terceiro como texto, nunca como HTML', async () => {
    const malicioso = { ...cofre, rotulo: '<img src=x onerror=alert(1)>' }
    mockResumo(() => json({ carteiras: [malicioso], total }))
    const { container } = render(<ResumoDasCarteiras versao={0} aoSessaoExpirar={vi.fn()} />)

    expect(await screen.findByText('<img src=x onerror=alert(1)>')).toBeVisible()
    expect(container.querySelector('img')).toBeNull()
  })

  it('não mostra nada sem carteira', async () => {
    mockResumo(() => json({ carteiras: [], total: { ...total, valorUsdCentavos: 0, valorBrlCentavos: 0 } }))
    const { container } = render(<ResumoDasCarteiras versao={0} aoSessaoExpirar={vi.fn()} />)

    await vi.waitFor(() => expect(screen.queryByText(/Carregando/)).toBeNull())
    expect(container).toBeEmptyDOMElement()
  })

  it('avisa quando a API falha', async () => {
    mockResumo(() => json({}, 500))
    renderizar()

    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível carregar o valor das carteiras.')
  })

  it('avisa quando o formato da resposta não bate com o contrato', async () => {
    mockResumo(() => json({ carteiras: [{ ...cofre, variacao24hPercentual: 9.73 }], total }))
    renderizar()

    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível carregar o valor das carteiras.')
  })

  it('devolve a sessão expirada para quem chamou', async () => {
    mockResumo(() => json({}, 401))
    const { aoSessaoExpirar } = renderizar()

    await vi.waitFor(() => expect(aoSessaoExpirar).toHaveBeenCalled())
  })
})
