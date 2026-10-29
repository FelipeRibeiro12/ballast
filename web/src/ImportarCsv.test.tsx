import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import type { Conta } from './api'
import Importar from './Importar'

const conta: Conta = {
  id: 'c1',
  nome: 'Nubank',
  tipo: 'corrente',
  moeda: 'BRL',
  percentualCdi: null,
  saldoInicialCentavos: 0,
  saldoCentavos: 0,
  arquivada: false,
  criadaEm: '2026-10-19T12:00:00Z',
  cartao: null,
  categoriasPermitidas: null,
}

const salvo = {
  id: 'm1',
  nome: 'Nubank cartão',
  separador: 'virgula',
  formatoData: 'yyyy-MM-dd',
  decimal: 'ponto',
  sinal: 'positivo_despesa',
  temCabecalho: true,
  colunaData: 0,
  colunaValor: 2,
  colunaDescricao: 1,
  colunaId: null,
}

function linhaDaRevisao(): Record<string, unknown> {
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
  }
}

const detalhe = {
  id: 'i1',
  contaId: 'c1',
  status: 'pendente',
  totalLinhas: 1,
  totalInvalidas: 0,
  totalImportar: 1,
  totalConciliar: 0,
  totalIgnorar: 0,
  criadoEm: '2026-10-29T12:00:00Z',
  confirmadaEm: null,
  linhas: [linhaDaRevisao()],
}

function previaOk(sobra: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    totalLinhas: 2,
    colunasDetectadas: 3,
    linhas: [
      {
        ordem: 1,
        celulas: ['2026-10-10', 'PADARIA CENTRAL', '123.45'],
        valida: true,
        erro: null,
        data: '2026-10-10',
        tipo: 'despesa',
        valorCentavos: 12345,
        titulo: 'PADARIA CENTRAL',
      },
      {
        ordem: 2,
        celulas: ['31/02/2026', 'X', '1.00'],
        valida: false,
        erro: 'data inválida',
        data: null,
        tipo: null,
        valorCentavos: null,
        titulo: 'X',
      },
    ],
    ...sobra,
  }
}

function json(corpo: unknown, status: number): Response {
  return new Response(JSON.stringify(corpo), { status, headers: { 'Content-Type': 'application/json' } })
}

type Chamada = { url: string; metodo: string; init: RequestInit | undefined }
type Rotas = Record<string, () => Promise<Response> | Response>

// A chave é "METODO caminho" sem a query; a query fica em chamada.url para o teste conferir.
function mockFetch(rotas: Rotas): Chamada[] {
  const chamadas: Chamada[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      const metodo = init?.method ?? 'GET'
      chamadas.push({ url, metodo, init })
      const resposta = rotas[`${metodo} ${url.split('?')[0]}`]
      return Promise.resolve(resposta === undefined ? json({ erro: 'inesperado' }, 500) : resposta())
    }),
  )
  return chamadas
}

const rotasBase: Rotas = {
  'GET /importacoes': () => json([], 200),
  'GET /categorias': () => json([], 200),
  'GET /mapeamentos-csv': () => json([], 200),
  'POST /importacoes/csv/previa': () => json(previaOk(), 200),
}

async function abrirCsv(): Promise<void> {
  render(<Importar contas={[conta]} aoSessaoExpirar={vi.fn()} aoMudarLancamentos={vi.fn()} />)
  await screen.findByText(/nenhuma importação pendente/i)
  fireEvent.click(screen.getByRole('radio', { name: /csv/i }))
  await waitFor(() => expect(screen.getByLabelText('Mapeamento salvo')).toBeEnabled())
}

function escolherArquivo(arquivo: File = new File(['data,desc,valor\n'], 'extrato.csv')): void {
  fireEvent.change(screen.getByLabelText(/arquivo do extrato/i), { target: { files: [arquivo] } })
}

function preencher(rotulo: string, valor: string): void {
  fireEvent.change(screen.getByLabelText(rotulo), { target: { value: valor } })
}

function consultaDe(chamada: Chamada | undefined): URLSearchParams {
  return new URL(chamada?.url ?? '', 'http://x').searchParams
}

function chamadasDe(chamadas: Chamada[], metodo: string, caminho: string): Chamada[] {
  return chamadas.filter((chamada) => chamada.metodo === metodo && chamada.url.split('?')[0] === caminho)
}

describe('Importar CSV', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('mostra a prévia com a leitura de cada linha e o erro da linha ruim', async () => {
    mockFetch(rotasBase)
    await abrirCsv()

    escolherArquivo()

    const tabela = await screen.findByRole('table', { name: 'Prévia do arquivo' })
    expect(within(tabela).getByText('2026-10-10')).toBeVisible()
    expect(within(tabela).getByText(/10\/10\/2026 \| Despesa \| R\$\s*123,45 \| PADARIA CENTRAL/)).toBeVisible()
    expect(within(tabela).getByText(/linha com problema: data inválida/i)).toBeVisible()
    expect(screen.getByText(/primeiras 2 de 2 linhas|2 linhas no arquivo/i)).toBeVisible()
  })

  it('manda as colunas da prévia a partir de 0, sem coluna_id quando vazia', async () => {
    const chamadas = mockFetch(rotasBase)
    await abrirCsv()
    preencher('Coluna da data', '4')
    preencher('Coluna do valor', '5')
    preencher('Coluna da descrição', '6')

    escolherArquivo()
    await screen.findByRole('table', { name: 'Prévia do arquivo' })

    const consulta = consultaDe(chamadasDe(chamadas, 'POST', '/importacoes/csv/previa')[0])
    expect(Object.fromEntries(consulta)).toEqual({
      separador: 'ponto_e_virgula',
      formato_data: 'dd/MM/yyyy',
      decimal: 'virgula',
      sinal: 'negativo_despesa',
      tem_cabecalho: 'true',
      coluna_data: '3',
      coluna_valor: '4',
      coluna_descricao: '5',
    })
  })

  it('espera o usuário parar de mexer: várias mudanças seguidas fazem uma prévia só', async () => {
    const chamadas = mockFetch(rotasBase)
    await abrirCsv()
    escolherArquivo()
    preencher('Coluna do identificador (opcional)', '4')
    preencher('Coluna do identificador (opcional)', '5')
    preencher('Coluna do identificador (opcional)', '6')

    await screen.findByRole('table', { name: 'Prévia do arquivo' })

    const previas = chamadasDe(chamadas, 'POST', '/importacoes/csv/previa')
    expect(previas).toHaveLength(1)
    expect(consultaDe(previas[0]).get('coluna_id')).toBe('5')
  })

  it('não pede prévia com colunas repetidas e explica o motivo', async () => {
    const chamadas = mockFetch(rotasBase)
    await abrirCsv()
    preencher('Coluna do valor', '1')
    escolherArquivo()

    expect(await screen.findByText(/use colunas diferentes/i)).toBeVisible()
    await new Promise((resolver) => setTimeout(resolver, 800))
    expect(chamadasDe(chamadas, 'POST', '/importacoes/csv/previa')).toHaveLength(0)
  })

  it('mostra célula de fórmula como texto, nunca como elemento, e explica o apóstrofo', async () => {
    const formula = `'=HYPERLINK("http://mal.example","clique")`
    mockFetch({
      ...rotasBase,
      'POST /importacoes/csv/previa': () =>
        json(
          previaOk({
            linhas: [
              {
                ordem: 1,
                celulas: ['2026-10-10', formula, '1.00'],
                valida: true,
                erro: null,
                data: '2026-10-10',
                tipo: 'despesa',
                valorCentavos: 100,
                titulo: formula,
              },
            ],
          }),
          200,
        ),
    })
    await abrirCsv()

    escolherArquivo()

    const tabela = await screen.findByRole('table', { name: 'Prévia do arquivo' })
    expect(within(tabela).getAllByText(formula, { exact: false }).length).toBeGreaterThan(0)
    expect(tabela.querySelector('a, img, script')).toBeNull()
    expect(screen.queryByRole('link')).toBeNull()
    expect(screen.getByText(/apóstrofo no começo de uma célula foi colocado de propósito/i)).toBeVisible()
  })

  it('mostra erro da prévia sem repetir o corpo do servidor', async () => {
    mockFetch({ ...rotasBase, 'POST /importacoes/csv/previa': () => json({ erro: 'trecho secreto do arquivo' }, 422) })
    await abrirCsv()

    escolherArquivo()

    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent(/não consegui ler este arquivo com esse mapeamento/i)
    expect(alerta).not.toHaveTextContent('trecho secreto')
  })

  it('salva o mapeamento novo com colunas a partir de 0 e envia o arquivo cru', async () => {
    const chamadas = mockFetch({
      ...rotasBase,
      'POST /mapeamentos-csv': () => json({ ...salvo, id: 'm9', nome: 'Banco Y', colunaData: 3, colunaValor: 4, colunaDescricao: 5, colunaId: 0 }, 201),
      'POST /importacoes/csv': () => json(detalhe, 201),
      'GET /importacoes/i1': () => json(detalhe, 200),
    })
    await abrirCsv()
    preencher('Nome do mapeamento (o banco, por exemplo)', 'Banco Y')
    preencher('Coluna da data', '4')
    preencher('Coluna do valor', '5')
    preencher('Coluna da descrição', '6')
    preencher('Coluna do identificador (opcional)', '1')
    escolherArquivo()

    fireEvent.click(screen.getByRole('button', { name: 'Enviar extrato' }))

    expect(await screen.findByText('PADARIA CENTRAL')).toBeVisible()
    const gravacao = chamadasDe(chamadas, 'POST', '/mapeamentos-csv')[0]
    expect(JSON.parse(String(gravacao.init?.body))).toEqual({
      nome: 'Banco Y',
      separador: 'ponto_e_virgula',
      formatoData: 'dd/MM/yyyy',
      decimal: 'virgula',
      sinal: 'negativo_despesa',
      temCabecalho: true,
      colunaData: 3,
      colunaValor: 4,
      colunaDescricao: 5,
      colunaId: 0,
    })
    const envio = chamadasDe(chamadas, 'POST', '/importacoes/csv')[0]
    expect(envio.url).toBe('/importacoes/csv?conta_id=c1&mapeamento_id=m9')
    expect(envio.init?.headers).toEqual({ 'Content-Type': 'application/octet-stream' })
    expect(envio.init?.body).toBeInstanceOf(File)
  })

  it('carrega um mapeamento salvo com colunas a partir de 1 e grava a edição com PUT a partir de 0', async () => {
    const chamadas = mockFetch({
      ...rotasBase,
      'GET /mapeamentos-csv': () => json([salvo], 200),
      'PUT /mapeamentos-csv/m1': () => json({ ...salvo, colunaValor: 6 }, 200),
    })
    await abrirCsv()
    await screen.findByRole('option', { name: 'Nubank cartão' })

    fireEvent.change(screen.getByLabelText('Mapeamento salvo'), { target: { value: 'm1' } })

    expect(screen.getByLabelText('Coluna da data')).toHaveValue(1)
    expect(screen.getByLabelText('Coluna do valor')).toHaveValue(3)
    expect(screen.getByLabelText('Coluna da descrição')).toHaveValue(2)
    expect(screen.getByLabelText('Sinal do valor')).toHaveValue('positivo_despesa')
    expect(screen.getByRole('option', { name: 'A despesa vem positiva, comum em fatura de cartão' })).toBeInTheDocument()

    preencher('Coluna do valor', '7')
    fireEvent.click(screen.getByRole('button', { name: 'Salvar alterações' }))

    await waitFor(() => expect(chamadasDe(chamadas, 'PUT', '/mapeamentos-csv/m1')).toHaveLength(1))
    const corpo: unknown = JSON.parse(String(chamadasDe(chamadas, 'PUT', '/mapeamentos-csv/m1')[0].init?.body))
    expect(corpo).toMatchObject({ nome: 'Nubank cartão', colunaData: 0, colunaValor: 6, colunaDescricao: 1, colunaId: null })
  })

  it('apagar pede confirmação antes de chamar o DELETE', async () => {
    const chamadas = mockFetch({
      ...rotasBase,
      'GET /mapeamentos-csv': () => json([salvo], 200),
      'DELETE /mapeamentos-csv/m1': () => new Response(null, { status: 204 }),
    })
    await abrirCsv()
    await screen.findByRole('option', { name: 'Nubank cartão' })
    fireEvent.change(screen.getByLabelText('Mapeamento salvo'), { target: { value: 'm1' } })

    fireEvent.click(screen.getByRole('button', { name: 'Apagar mapeamento' }))
    expect(chamadas.some((chamada) => chamada.metodo === 'DELETE')).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: 'Manter' }))
    expect(chamadas.some((chamada) => chamada.metodo === 'DELETE')).toBe(false)

    fireEvent.click(screen.getByRole('button', { name: 'Apagar mapeamento' }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirmar exclusão' }))

    await waitFor(() => expect(screen.queryByRole('option', { name: 'Nubank cartão' })).toBeNull())
    expect(chamadasDe(chamadas, 'DELETE', '/mapeamentos-csv/m1')).toHaveLength(1)
  })

  it('abre a importação existente com aviso no 409 de arquivo repetido', async () => {
    mockFetch({
      ...rotasBase,
      'GET /mapeamentos-csv': () => json([salvo], 200),
      'POST /importacoes/csv': () => json({ erro: 'arquivo já importado', importacaoId: 'i1' }, 409),
      'GET /importacoes/i1': () => json(detalhe, 200),
    })
    await abrirCsv()
    await screen.findByRole('option', { name: 'Nubank cartão' })
    fireEvent.change(screen.getByLabelText('Mapeamento salvo'), { target: { value: 'm1' } })
    escolherArquivo()

    fireEvent.click(screen.getByRole('button', { name: 'Enviar extrato' }))

    expect(await screen.findByText(/este arquivo já foi enviado antes/i)).toBeVisible()
    expect(await screen.findByText('PADARIA CENTRAL')).toBeVisible()
  })

  it.each([
    [413, { erro: 'x' }, /passa de 1 MiB/i],
    [422, { erro: 'aspas sem fechar no arquivo' }, /não consegui ler este arquivo com esse mapeamento/i],
    [409, { erro: 'limite de importações pendentes atingido' }, /20 importações pendentes/i],
    [404, { erro: 'mapeamento não encontrado' }, /não encontrei a conta ou o mapeamento/i],
  ])('mostra mensagem própria para o erro %i do envio, sem eco do servidor', async (status, corpo, texto) => {
    mockFetch({
      ...rotasBase,
      'GET /mapeamentos-csv': () => json([salvo], 200),
      'POST /importacoes/csv': () => json(corpo, status),
    })
    await abrirCsv()
    await screen.findByRole('option', { name: 'Nubank cartão' })
    fireEvent.change(screen.getByLabelText('Mapeamento salvo'), { target: { value: 'm1' } })
    escolherArquivo()
    await screen.findByRole('table', { name: 'Prévia do arquivo' })

    fireEvent.click(screen.getByRole('button', { name: 'Enviar extrato' }))

    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent(texto)
    expect(alerta).not.toHaveTextContent('aspas sem fechar')
  })

  it.each([
    ['já existe um mapeamento com esse nome', /já existe um mapeamento com esse nome/i],
    ['limite de mapeamentos de CSV atingido', /20 mapeamentos/i],
  ])('explica o 409 ao salvar: %s', async (erro, texto) => {
    mockFetch({ ...rotasBase, 'POST /mapeamentos-csv': () => json({ erro }, 409) })
    await abrirCsv()
    preencher('Nome do mapeamento (o banco, por exemplo)', 'Banco Y')

    fireEvent.click(screen.getByRole('button', { name: 'Salvar mapeamento' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(texto)
  })

  it('barra arquivo acima de 1 MiB antes de enviar ou pedir a prévia', async () => {
    const chamadas = mockFetch(rotasBase)
    await abrirCsv()

    escolherArquivo(new File([new Uint8Array(1024 * 1024 + 1)], 'grande.csv'))

    expect(await screen.findByRole('alert')).toHaveTextContent(/passa de 1 MiB/i)
    expect(screen.getByRole('button', { name: 'Enviar extrato' })).toBeDisabled()
    await new Promise((resolver) => setTimeout(resolver, 800))
    expect(chamadas.some((chamada) => chamada.metodo === 'POST')).toBe(false)
  })

  it('resposta fora do formato vira erro na tela, não prévia', async () => {
    mockFetch({ ...rotasBase, 'POST /importacoes/csv/previa': () => json({ linhas: 'x' }, 200) })
    await abrirCsv()

    escolherArquivo()

    expect(await screen.findByRole('alert')).toHaveTextContent(/não foi possível montar a prévia/i)
  })
})
