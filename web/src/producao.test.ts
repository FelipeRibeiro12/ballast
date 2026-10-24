/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { PREFIXO_DA_API, baseDaApi, conferirOrigemDeProducao } from './rotasDaApi'

// O cookie de sessão é SameSite=Lax (ADR-020): front e API têm de ser a mesma origem. Em produção
// o Vercel serve o front e repassa /api/* para a VM (ADR-044). Estes testes quebram se a
// configuração deixar a API em outra origem.

type Rota = {
  src?: string
  dest?: string
  handle?: string
  transforms?: { type: string; op: string; target: { key: string }; args: string; env?: string[] }[]
}

const vercel = JSON.parse(readFileSync(resolve(process.cwd(), 'vercel.json'), 'utf8')) as { routes?: Rota[] }
const rotas = vercel.routes ?? []
const indiceDaApi = rotas.findIndex((rota) => rota.src !== undefined && new RegExp(rota.src).test(`${PREFIXO_DA_API}/auth/eu`))
const rotaDaApi = rotas[indiceDaApi]

describe('produção na mesma origem', () => {
  it('o build de produção chama a API por caminho relativo, no prefixo que o Vercel repassa', () => {
    expect(PREFIXO_DA_API).toMatch(/^\/[a-z]+$/)
    expect(baseDaApi({ PROD: true, VITE_API_URL: undefined })).toBe(PREFIXO_DA_API)
  })

  it('o dev e os testes continuam sem prefixo, pelo proxy do Vite', () => {
    expect(baseDaApi({ PROD: false, VITE_API_URL: undefined })).toBe('')
  })

  it.each(['https://api.exemplo.com', '//api.exemplo.com', 'http://localhost:8080'])(
    'o build de produção recusa VITE_API_URL em outra origem: %s',
    (url) => {
      expect(() => conferirOrigemDeProducao({ VITE_API_URL: url })).toThrow(/mesma origem/)
    },
  )

  it.each([undefined, '', '/api'])('o build de produção aceita VITE_API_URL relativa ou ausente: %s', (url) => {
    expect(() => conferirOrigemDeProducao({ VITE_API_URL: url })).not.toThrow()
  })

  it('o vercel.json repassa o prefixo da API para a VM por HTTPS, com caminho e consulta', () => {
    expect(rotaDaApi).toBeDefined()
    expect(rotaDaApi.dest).toMatch(/^https:\/\/[a-z0-9.-]+\/\$1$/)
    expect(new RegExp(rotaDaApi.src ?? '').exec(`${PREFIXO_DA_API}/lancamentos`)?.[1]).toBe('lancamentos')
  })

  it('a rota da API vem antes dos arquivos estáticos, para /api nunca virar o index.html', () => {
    const indiceDosArquivos = rotas.findIndex((rota) => rota.handle === 'filesystem')
    expect(indiceDosArquivos).toBeGreaterThan(indiceDaApi)
  })

  it('o Vercel manda o segredo de origem, que o Caddy da VM confere', () => {
    expect(rotaDaApi.transforms).toContainEqual({
      type: 'request.headers',
      op: 'set',
      target: { key: 'x-origin-secret' },
      args: '$ORIGIN_SECRET',
      env: ['ORIGIN_SECRET'],
    })
  })
})
