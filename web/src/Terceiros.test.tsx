import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import Terceiros from './Terceiros'

function terceiro(sobra: Record<string, unknown>): Record<string, unknown> {
  return {
    id: 't1',
    nome: 'Maria',
    arquivado: false,
    compradoCentavos: 90000,
    recebidoCentavos: 0,
    devidoCentavos: 90000,
    devidoNoMesCentavos: 30000,
    ...sobra,
  }
}

function abrir(terceiros: unknown[]): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(() =>
      Promise.resolve(
        new Response(JSON.stringify(terceiros), { status: 200, headers: { 'Content-Type': 'application/json' } }),
      ),
    ),
  )
  render(<Terceiros aoSessaoExpirar={() => {}} aoMudarLancamentos={() => {}} />)
}

afterEach(() => vi.unstubAllGlobals())

describe('Terceiros', () => {
  it('mostra o que deve no mês e deixa o total em segundo plano', async () => {
    abrir([terceiro({})])
    expect(await screen.findByText(/Deve .*300,00.* no mês/)).toBeVisible()
    expect(screen.getByText(/falta .*900,00/)).toBeVisible()
  })

  it('quem pagou tudo de uma vez está em dia no mês', async () => {
    abrir([terceiro({ recebidoCentavos: 90000, devidoCentavos: 0, devidoNoMesCentavos: 0 })])
    expect(await screen.findByText('Em dia no mês')).toBeVisible()
  })

  it('quem pagou a mais aparece com o crédito', async () => {
    abrir([terceiro({ recebidoCentavos: 100000, devidoCentavos: -10000, devidoNoMesCentavos: 0 })])
    expect(await screen.findByText(/Pagou a mais .*100,00/)).toBeVisible()
  })
})
