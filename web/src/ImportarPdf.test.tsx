import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Conta } from './api'
import Importar from './Importar'

const base: Conta = {
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
const cartao: Conta = {
  ...base,
  id: 'k1',
  nome: 'Cartão Roxo',
  tipo: 'cartao_credito',
  cartao: { limiteCentavos: 100000, diaFechamento: 10, diaVencimento: 20, contaPagamentoId: 'c1', disponivelCentavos: 100000 },
}

function json(corpo: unknown, status: number): Response {
  return new Response(JSON.stringify(corpo), { status, headers: { 'Content-Type': 'application/json' } })
}

const linha = {
  id: 'l1',
  ordem: 1,
  data: '2026-10-05',
  tipo: 'despesa',
  valorCentavos: 1000,
  titulo: 'CAFE',
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
const detalhe = {
  id: 'i1',
  contaId: 'k1',
  status: 'pendente',
  totalLinhas: 1,
  totalInvalidas: 0,
  totalImportar: 1,
  totalConciliar: 0,
  totalIgnorar: 0,
  criadoEm: '2026-10-29T12:00:00Z',
  confirmadaEm: null,
  linhas: [linha],
}

type Chamada = { url: string; metodo: string; init: RequestInit | undefined }

function mockFetch(rotas: Record<string, () => Response>): Chamada[] {
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

const rotasBase = {
  'GET /importacoes': () => json([], 200),
  'GET /categorias': () => json([], 200),
}

async function abrirAbaPdf(contas: Conta[]): Promise<void> {
  render(<Importar contas={contas} aoSessaoExpirar={vi.fn()} aoMudarLancamentos={vi.fn()} />)
  await screen.findByText(/nenhuma importação pendente/i)
  fireEvent.click(screen.getByRole('radio', { name: /PDF/i }))
}

function enviar(arquivo: File): void {
  fireEvent.change(screen.getByLabelText('Fatura em PDF'), { target: { files: [arquivo] } })
  fireEvent.click(screen.getByRole('button', { name: 'Enviar fatura' }))
}

describe('Importar PDF de fatura', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('avisa que é melhor esforço e oferece só cartões', async () => {
    mockFetch(rotasBase)
    await abrirAbaPdf([base, cartao])

    expect(screen.getByRole('note')).toHaveTextContent(/melhor esforço e pode falhar/i)
    expect(screen.getByRole('option', { name: 'Cartão Roxo' })).toBeVisible()
    expect(screen.queryByRole('option', { name: 'Nubank' })).toBeNull()
  })

  it('explica quando não há cartão', async () => {
    mockFetch(rotasBase)
    await abrirAbaPdf([base])

    expect(screen.getByText(/nenhum cartão de crédito/i)).toBeVisible()
  })

  it('envia o PDF cru para a rota própria e abre a revisão', async () => {
    const chamadas = mockFetch({
      ...rotasBase,
      'POST /importacoes/pdf?conta_id=k1': () => json(detalhe, 201),
      'GET /importacoes/i1': () => json(detalhe, 200),
    })
    await abrirAbaPdf([base, cartao])

    enviar(new File(['%PDF-1.7'], 'fatura.pdf', { type: 'application/pdf' }))

    expect(await screen.findByText('CAFE')).toBeVisible()
    const envio = chamadas.find((chamada) => chamada.metodo === 'POST')
    expect(envio?.init?.headers).toEqual({ 'Content-Type': 'application/octet-stream' })
    expect(envio?.init?.body).toBeInstanceOf(File)
  })

  it('barra PDF acima de 5 MiB sem chamar a API', async () => {
    const chamadas = mockFetch(rotasBase)
    await abrirAbaPdf([cartao])

    enviar(new File([new Uint8Array(5 * 1024 * 1024 + 1)], 'grande.pdf'))

    expect(await screen.findByRole('alert')).toHaveTextContent(/passa de 5 MiB/i)
    expect(chamadas.some((chamada) => chamada.metodo === 'POST')).toBe(false)
  })

  it('mostra texto fixo no 422, sem o corpo do servidor', async () => {
    mockFetch({ ...rotasBase, 'POST /importacoes/pdf?conta_id=k1': () => json({ erro: 'trecho secreto' }, 422) })
    await abrirAbaPdf([cartao])

    enviar(new File(['%PDF-1.7'], 'f.pdf'))

    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent(/não consegui ler esta fatura/i)
    expect(alerta).not.toHaveTextContent('secreto')
  })

  it('abre a importação existente com aviso no 409', async () => {
    mockFetch({
      ...rotasBase,
      'POST /importacoes/pdf?conta_id=k1': () => json({ erro: 'arquivo já importado', importacaoId: 'i1' }, 409),
      'GET /importacoes/i1': () => json(detalhe, 200),
    })
    await abrirAbaPdf([cartao])

    enviar(new File(['%PDF-1.7'], 'f.pdf'))

    expect(await screen.findByText(/este pdf já foi enviado antes/i)).toBeVisible()
  })
})
