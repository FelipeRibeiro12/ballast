/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { padraoDoProxy, prefixosDaApi } from './rotasDaApi'

function prefixosDaSpec(): string[] {
  // import.meta.url não é file: no ambiente jsdom; o vitest roda com cwd em web/.
  const caminho = resolve(process.cwd(), '../api/openapi.yaml')
  const paths = [...readFileSync(caminho, 'utf8').matchAll(/^ {2}(\/[^\s:]*):\s*$/gm)]
  return [...new Set(paths.map((m) => m[1].split('/')[1]))].sort()
}

describe('rotas repassadas pelo proxy do Vite', () => {
  it('cobrem todo primeiro segmento de path do openapi.yaml', () => {
    const faltando = prefixosDaSpec().filter((p) => !prefixosDaApi.includes(p))
    expect(faltando).toEqual([])
  })

  it('não têm prefixo que a API não possui', () => {
    const sobrando = prefixosDaApi.filter((p) => !prefixosDaSpec().includes(p))
    expect(sobrando).toEqual([])
  })

  it('casam o segmento inteiro e deixam o resto para o Vite', () => {
    const regex = new RegExp(padraoDoProxy)
    for (const url of ['/contas', '/contas/1', '/lancamentos?de=2026-01-01', '/saldo-projetado', '/auth/eu']) {
      expect(regex.test(url), url).toBe(true)
    }
    for (const url of ['/', '/contasX', '/contas-x', '/src/main.tsx', '/@vite/client', '/index.html', '/authx']) {
      expect(regex.test(url), url).toBe(false)
    }
  })
})
