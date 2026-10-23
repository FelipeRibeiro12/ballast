import { act, renderHook } from '@testing-library/react'
import { hashDaSecao, lerSecao, useRota } from './rota'

describe('lerSecao', () => {
  it.each([
    ['#/inicio', 'inicio'],
    ['#/lancamentos', 'lancamentos'],
    ['#/contas', 'contas'],
    ['#/recorrencias', 'recorrencias'],
    // Fora da barra de navegação, mas com rota: o cartão do Início leva até ela.
    ['#/orcamento', 'orcamento'],
    ['#/metas', 'metas'],
  ])('lê %s', (hash, esperada) => {
    expect(lerSecao(hash)).toBe(esperada)
  })

  it.each(['', '#', '#/', '#/nao-existe', '#/contas/outra', '#contas-sem-barra-errada/'])(
    'cai no Início para "%s"',
    (hash) => {
      expect(lerSecao(hash)).toBe('inicio')
    },
  )

  it('aceita o hash sem a barra', () => {
    expect(lerSecao('#contas')).toBe('contas')
  })
})

describe('useRota', () => {
  beforeEach(() => {
    window.location.hash = ''
  })

  it('começa pela seção do hash atual', () => {
    window.location.hash = hashDaSecao('contas')
    const { result } = renderHook(() => useRota())
    expect(result.current[0]).toBe('contas')
  })

  it('segue o hashchange, como no botão voltar', () => {
    const { result } = renderHook(() => useRota())
    expect(result.current[0]).toBe('inicio')
    act(() => {
      window.location.hash = '#/recorrencias'
      window.dispatchEvent(new HashChangeEvent('hashchange'))
    })
    expect(result.current[0]).toBe('recorrencias')
  })

  it('irPara grava o hash da seção', () => {
    const { result } = renderHook(() => useRota())
    act(() => result.current[1]('lancamentos'))
    expect(window.location.hash).toBe('#/lancamentos')
  })
})
