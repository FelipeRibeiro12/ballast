import { act, renderHook } from '@testing-library/react'
import { aplicarTema, lerTema, salvarTema, useTema } from './tema'

describe('tema', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    window.localStorage.clear()
    delete document.documentElement.dataset.tema
  })

  it('sem nada guardado vale o do sistema', () => {
    expect(lerTema()).toBe('sistema')
  })

  it('lê o tema guardado', () => {
    window.localStorage.setItem('ballast.tema', 'escuro')
    expect(lerTema()).toBe('escuro')
  })

  it('ignora valor guardado que não é um tema', () => {
    window.localStorage.setItem('ballast.tema', 'roxo')
    expect(lerTema()).toBe('sistema')
  })

  it('cai no sistema quando o armazenamento lança ao ler', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('bloqueado', 'SecurityError')
    })
    expect(lerTema()).toBe('sistema')
  })

  it('salvar devolve false quando o armazenamento lança, sem derrubar a tela', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('cheio', 'QuotaExceededError')
    })
    expect(salvarTema('escuro')).toBe(false)
  })

  it('aplica claro e escuro no elemento raiz e tira o atributo no sistema', () => {
    aplicarTema('escuro')
    expect(document.documentElement.dataset.tema).toBe('escuro')
    aplicarTema('claro')
    expect(document.documentElement.dataset.tema).toBe('claro')
    aplicarTema('sistema')
    expect(document.documentElement.dataset.tema).toBeUndefined()
  })

  it('o hook aplica, guarda e devolve a escolha, mesmo com o armazenamento quebrado', () => {
    const { result } = renderHook(() => useTema())
    act(() => result.current[1]('escuro'))
    expect(result.current[0]).toBe('escuro')
    expect(document.documentElement.dataset.tema).toBe('escuro')
    expect(window.localStorage.getItem('ballast.tema')).toBe('escuro')

    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('cheio', 'QuotaExceededError')
    })
    act(() => result.current[1]('claro'))
    expect(result.current[0]).toBe('claro')
    expect(document.documentElement.dataset.tema).toBe('claro')
  })

  it('voltar para o sistema apaga o valor guardado', () => {
    window.localStorage.setItem('ballast.tema', 'escuro')
    const { result } = renderHook(() => useTema())
    act(() => result.current[1]('sistema'))
    expect(window.localStorage.getItem('ballast.tema')).toBeNull()
  })
})
