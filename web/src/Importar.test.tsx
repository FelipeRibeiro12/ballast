import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import type { Conta } from './api'
import Importar from './Importar'

const conta: Conta = {
  id: 'c1',
  nome: 'Nubank',
  tipo: 'corrente',
  moeda: 'BRL',
  percentualCdi: null,
  emInvestimentos: false,
  saldoInicialCentavos: 0,
  saldoCentavos: 0,
  arquivada: false,
  criadaEm: '2026-10-19T12:00:00Z',
  cartao: null,
  categoriasPermitidas: null,
}

const categorias = [
  { id: 'k-outros', nome: 'Outros', tipo: 'despesa', paiId: null, cor: null, icone: null, protegida: true },
  { id: 'k-mercado', nome: 'Mercado', tipo: 'despesa', paiId: null, cor: null, icone: null, protegida: false },
  { id: 'k-outras', nome: 'Outras receitas', tipo: 'receita', paiId: null, cor: null, icone: null, protegida: true },
]

function linha(sobra: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id: 'l1',
    ordem: 1,
    data: '2026-10-10',
    tipo: 'despesa',
    valorCentavos: 12345,
    titulo: 'PADARIA CENTRAL',
    descricao: null,
    valida: true,
    erro: null,
    proposta: 'importar',
    motivo: null,
    decisao: null,
    acao: 'importar',
    categoriaId: 'k-outros',
    lancamentoId: null,
    resultado: null,
    ...sobra,
  }
}

function detalhe(linhas: Record<string, unknown>[], sobra: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id: 'i1',
    contaId: 'c1',
    status: 'pendente',
    totalLinhas: linhas.length,
    totalInvalidas: 0,
    totalImportar: linhas.length,
    totalConciliar: 0,
    totalIgnorar: 0,
    criadoEm: '2026-10-29T12:00:00Z',
    confirmadaEm: null,
    linhas,
    ...sobra,
  }
}

function json(corpo: unknown, status: number): Response {
  return new Response(JSON.stringify(corpo), { status, headers: { 'Content-Type': 'application/json' } })
}

type Chamada = { url: string; metodo: string; init: RequestInit | undefined }

// Cada rota responde pelo "METODO url"; o que não está na tabela cai em 500 para o teste falhar alto.
function mockFetch(rotas: Record<string, () => Promise<Response> | Response>): Chamada[] {
  const chamadas: Chamada[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      const metodo = init?.method ?? 'GET'
      chamadas.push({ url, metodo, init })
      const resposta = rotas[`${metodo} ${url}`]
      return Promise.resolve(resposta === undefined ? json({ erro: 'inesperado' }, 500) : resposta())
    }),
  )
  return chamadas
}

function cabecalhoDaChave(chamada: Chamada): string | undefined {
  return (chamada.init?.headers as Record<string, string> | undefined)?.['Idempotency-Key']
}

const aoMudarLancamentos = vi.fn()

function renderizar(): void {
  render(<Importar contas={[conta]} aoSessaoExpirar={vi.fn()} aoMudarLancamentos={aoMudarLancamentos} />)
}

function enviarArquivo(arquivo: File = new File(['<OFX></OFX>'], 'extrato.ofx')): void {
  fireEvent.change(screen.getByLabelText(/arquivo do extrato/i), { target: { files: [arquivo] } })
  fireEvent.click(screen.getByRole('button', { name: 'Enviar extrato' }))
}

const rotasBase = {
  'GET /importacoes': () => json([], 200),
  'GET /categorias': () => json(categorias, 200),
}

describe('Importar', () => {
  beforeEach(() => aoMudarLancamentos.mockClear())
  afterEach(() => vi.unstubAllGlobals())

  it('explica que o arquivo não é guardado e mostra o vazio', async () => {
    mockFetch(rotasBase)
    renderizar()

    expect(await screen.findByText(/nenhuma importação pendente/i)).toBeVisible()
    expect(screen.getByText(/o arquivo não fica guardado/i)).toBeVisible()
    expect(screen.getByText(/nada entra no extrato antes de você/i)).toBeVisible()
  })

  it('envia o arquivo cru, sem nome, e abre a revisão', async () => {
    const chamadas = mockFetch({
      ...rotasBase,
      'POST /importacoes?conta_id=c1': () => json(detalhe([linha()]), 201),
      'GET /importacoes/i1': () => json(detalhe([linha()]), 200),
    })
    renderizar()
    await screen.findByText(/nenhuma importação pendente/i)

    enviarArquivo()

    expect(await screen.findByText('PADARIA CENTRAL')).toBeVisible()
    const envio = chamadas.find((chamada) => chamada.metodo === 'POST')
    expect(envio?.init?.headers).toEqual({ 'Content-Type': 'application/octet-stream' })
    expect(envio?.init?.body).toBeInstanceOf(File)
    expect(screen.getByRole('status', { name: 'Resumo da importação' })).toHaveTextContent(
      '1 para criar, 0 para conciliar, 0 para ignorar',
    )
    expect(screen.getByText(/− R\$\s*123,45/)).toBeVisible()
  })

  it('abre a importação existente com aviso no 409 de arquivo repetido', async () => {
    mockFetch({
      ...rotasBase,
      'POST /importacoes?conta_id=c1': () => json({ erro: 'arquivo já importado', importacaoId: 'i1' }, 409),
      'GET /importacoes/i1': () => json(detalhe([linha()]), 200),
    })
    renderizar()
    await screen.findByText(/nenhuma importação pendente/i)

    enviarArquivo()

    expect(await screen.findByText(/este arquivo já foi enviado antes/i)).toBeVisible()
    expect(await screen.findByText('PADARIA CENTRAL')).toBeVisible()
  })

  it.each([
    [422, /não consegui ler este arquivo/i],
    [413, /passa de 1 MiB/i],
    [409, /20 importações pendentes/i],
  ])('mostra mensagem em português para o erro %i do envio', async (status, texto) => {
    const corpo = status === 409 ? { erro: 'limite de importações pendentes atingido' } : { erro: 'x' }
    mockFetch({ ...rotasBase, 'POST /importacoes?conta_id=c1': () => json(corpo, status) })
    renderizar()
    await screen.findByText(/nenhuma importação pendente/i)

    enviarArquivo()

    expect(await screen.findByRole('alert')).toHaveTextContent(texto)
  })

  it('barra arquivo acima de 1 MiB sem chamar a API', async () => {
    const chamadas = mockFetch(rotasBase)
    renderizar()
    await screen.findByText(/nenhuma importação pendente/i)

    enviarArquivo(new File([new Uint8Array(1024 * 1024 + 1)], 'grande.ofx'))

    expect(await screen.findByRole('alert')).toHaveTextContent(/passa de 1 MiB/i)
    expect(chamadas.some((chamada) => chamada.metodo === 'POST')).toBe(false)
  })

  it('lista as pendentes e retoma uma delas', async () => {
    mockFetch({
      ...rotasBase,
      'GET /importacoes': () => {
        const { linhas: _linhas, ...item } = detalhe([linha()])
        return json([item], 200)
      },
      'GET /importacoes/i1': () => json(detalhe([linha()]), 200),
    })
    renderizar()

    fireEvent.click(await screen.findByRole('button', { name: /revisar importação de nubank/i }))

    expect(await screen.findByText('PADARIA CENTRAL')).toBeVisible()
  })

  it('troca a decisão da linha chamando o PUT', async () => {
    const chamadas = mockFetch({
      ...rotasBase,
      'GET /importacoes/i1': () => json(detalhe([linha()]), 200),
      'POST /importacoes?conta_id=c1': () => json(detalhe([linha()]), 201),
      'PUT /importacoes/i1/linhas/l1': () => json(linha({ decisao: 'ignorar', acao: 'ignorar' }), 200),
    })
    renderizar()
    await screen.findByText(/nenhuma importação pendente/i)
    enviarArquivo()

    fireEvent.change(await screen.findByLabelText('Ação da linha 1'), { target: { value: 'ignorar' } })

    await waitFor(() =>
      expect(screen.getByRole('status', { name: 'Resumo da importação' })).toHaveTextContent('0 para criar, 0 para conciliar, 1 para ignorar'),
    )
    const put = chamadas.find((chamada) => chamada.metodo === 'PUT')
    expect(JSON.parse(String(put?.init?.body))).toEqual({ acao: 'ignorar' })
  })

  it('edita a categoria de uma linha a importar', async () => {
    const chamadas = mockFetch({
      ...rotasBase,
      'GET /importacoes/i1': () => json(detalhe([linha()]), 200),
      'POST /importacoes?conta_id=c1': () => json(detalhe([linha()]), 201),
      'PUT /importacoes/i1/linhas/l1': () => json(linha({ categoriaId: 'k-mercado', decisao: 'importar' }), 200),
    })
    renderizar()
    await screen.findByText(/nenhuma importação pendente/i)
    enviarArquivo()

    fireEvent.change(await screen.findByLabelText('Categoria da linha 1'), { target: { value: 'k-mercado' } })

    await waitFor(() => expect(screen.getByLabelText('Categoria da linha 1')).toHaveValue('k-mercado'))
    const put = chamadas.find((chamada) => chamada.metodo === 'PUT')
    expect(JSON.parse(String(put?.init?.body))).toEqual({
      acao: 'importar',
      categoriaId: 'k-mercado',
      titulo: 'PADARIA CENTRAL',
    })
  })

  it('trava a linha que a proposta já ignorou', async () => {
    mockFetch({
      ...rotasBase,
      'GET /importacoes/i1': () =>
        json(detalhe([linha({ proposta: 'ignorar', acao: 'ignorar', motivo: 'ja_importada' })]), 200),
      'POST /importacoes?conta_id=c1': () => json(detalhe([linha()]), 201),
    })
    renderizar()
    await screen.findByText(/nenhuma importação pendente/i)
    enviarArquivo()

    expect(await screen.findByText('Já importada antes')).toBeVisible()
    expect(screen.getByLabelText('Ação da linha 1')).toBeDisabled()
  })

  it('mostra o lançamento candidato da conciliação', async () => {
    const candidata = linha({ proposta: 'conciliar', acao: 'conciliar', lancamentoId: 'lan1' })
    mockFetch({
      ...rotasBase,
      'GET /importacoes/i1': () => json(detalhe([candidata]), 200),
      'POST /importacoes?conta_id=c1': () => json(detalhe([candidata]), 201),
      'GET /lancamentos?de=2026-10-07&ate=2026-10-13&contaId=c1': () =>
        json(
          [
            {
              id: 'lan1',
              contaId: 'c1',
              tipo: 'despesa',
              titulo: 'Padaria',
              valorCentavos: 12345,
              dataCompetencia: '2026-10-09',
              dataCaixa: '2026-10-09',
              status: 'realizado',
              categoriaId: 'k-outros',
              descricao: null,
              direcao: null,
              compraParceladaId: null,
              criadoEm: '2026-10-09T12:00:00Z',
              parcelaNum: null,
              parcelaOrdem: null,
              parcelaTotal: null,
              templateId: null,
              terceiroId: null,
              transferenciaId: null,
            },
          ],
          200,
        ),
    })
    renderizar()
    await screen.findByText(/nenhuma importação pendente/i)
    enviarArquivo()

    expect(await screen.findByText(/concilia com: padaria, 09\/10\/2026/i)).toBeVisible()
  })

  async function abrirRevisao(confirmacao: () => Promise<Response> | Response): Promise<Chamada[]> {
    const chamadas = mockFetch({
      ...rotasBase,
      'GET /importacoes/i1': () => json(detalhe([linha()]), 200),
      'POST /importacoes?conta_id=c1': () => json(detalhe([linha()]), 201),
      'POST /importacoes/i1/confirmacao': confirmacao,
    })
    renderizar()
    await screen.findByText(/nenhuma importação pendente/i)
    enviarArquivo()
    await screen.findByText('PADARIA CENTRAL')
    return chamadas
  }

  it('confirma com Idempotency-Key e mostra o resumo', async () => {
    const chamadas = await abrirRevisao(() =>
      json({ id: 'i1', status: 'confirmada', criadas: 1, conciliadas: 0, ignoradas: 0, duplicadas: 0 }, 200),
    )

    fireEvent.click(screen.getByRole('button', { name: 'Confirmar importação' }))

    expect(await screen.findByText(/1 lançamentos criados, 0 conciliados, 0 ignorados/i)).toBeVisible()
    const confirmacao = chamadas.find((chamada) => chamada.url === '/importacoes/i1/confirmacao')
    const cabecalhos = confirmacao?.init?.headers as Record<string, string>
    expect(cabecalhos['Idempotency-Key']).toMatch(/^[\w.:-]{1,80}$/)
    expect(aoMudarLancamentos).toHaveBeenCalledTimes(1)
  })

  it('o duplo clique confirma uma vez só', async () => {
    let liberar: (resposta: Response) => void = () => undefined
    const chamadas = await abrirRevisao(() => new Promise<Response>((resolver) => (liberar = resolver)))

    const botao = screen.getByRole('button', { name: 'Confirmar importação' })
    fireEvent.click(botao)
    fireEvent.click(botao)
    expect(await screen.findByRole('button', { name: 'Confirmando...' })).toBeDisabled()
    liberar(json({ id: 'i1', status: 'confirmada', criadas: 1, conciliadas: 0, ignoradas: 0, duplicadas: 0 }, 200))

    await screen.findByText(/lançamentos criados/i)
    expect(chamadas.filter((chamada) => chamada.url === '/importacoes/i1/confirmacao')).toHaveLength(1)
  })

  it('reusa a chave quando a rede falha e troca quando o servidor recusa', async () => {
    const respostas = [
      () => Promise.reject(new TypeError('rede')),
      () => Promise.reject(new TypeError('rede')),
      () => json({ erro: 'categoria não permitida nesta conta' }, 400),
      () => json({ id: 'i1', status: 'confirmada', criadas: 1, conciliadas: 0, ignoradas: 0, duplicadas: 0 }, 200),
    ]
    let indice = 0
    const chamadas = await abrirRevisao(() => respostas[indice++]() as Promise<Response>)

    for (let tentativa = 0; tentativa < 3; tentativa++) {
      fireEvent.click(screen.getByRole('button', { name: 'Confirmar importação' }))
      await screen.findByRole('alert')
      await waitFor(() => expect(screen.getByRole('button', { name: 'Confirmar importação' })).toBeEnabled())
    }
    expect(screen.getByRole('alert')).toHaveTextContent('categoria não permitida nesta conta')
    fireEvent.click(screen.getByRole('button', { name: 'Confirmar importação' }))
    await screen.findByText(/lançamentos criados/i)

    const chaves = chamadas
      .filter((chamada) => chamada.url === '/importacoes/i1/confirmacao')
      .map((chamada) => cabecalhoDaChave(chamada))
    expect(chaves).toHaveLength(4)
    expect(chaves[1]).toBe(chaves[0])
    expect(chaves[2]).toBe(chaves[0])
    expect(chaves[3]).not.toBe(chaves[0])
  })

  it('descarta a importação depois de confirmar o descarte', async () => {
    const chamadas = await abrirRevisao(() => json({}, 500))
    chamadas.length = 0

    fireEvent.click(screen.getByRole('button', { name: 'Descartar importação' }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirmar descarte' }))

    await waitFor(() => expect(chamadas.some((chamada) => chamada.metodo === 'DELETE')).toBe(true))
  })

  it('mostra importação já confirmada só para consulta', async () => {
    mockFetch({
      ...rotasBase,
      'POST /importacoes?conta_id=c1': () => json({ erro: 'arquivo já importado', importacaoId: 'i1' }, 409),
      'GET /importacoes/i1': () => json(detalhe([linha({ resultado: 'criada' })], { status: 'confirmada' }), 200),
    })
    renderizar()
    await screen.findByText(/nenhuma importação pendente/i)

    enviarArquivo()

    const aviso = await screen.findByText(/já foi confirmada/i)
    expect(aviso).toBeVisible()
    expect(screen.queryByRole('button', { name: 'Confirmar importação' })).toBeNull()
    expect(within(screen.getByRole('list', { name: 'Linhas do extrato' })).getByLabelText('Ação da linha 1')).toBeDisabled()
  })
})
