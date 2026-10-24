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
const indiceDaApi = rotas.findIndex((rota) => rota.dest !== undefined && rota.src !== undefined && new RegExp(rota.src).test(`${PREFIXO_DA_API}/auth/eu`))
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

// Cabeçalhos de segurança do front (ADR-052). A CSP é a única defesa contra script injetado, então
// o teste quebra se ela perder uma diretiva ou ganhar 'unsafe-eval' ou curinga.
describe('cabeçalhos de segurança', () => {
  const rotaDeCabecalhos = rotas.find((rota) => (rota as { headers?: unknown }).headers !== undefined && rota.src === '^/(.*)$')
  const cabecalhos = (rotaDeCabecalhos as { headers?: Record<string, string> } | undefined)?.headers ?? {}
  const csp = cabecalhos['Content-Security-Policy'] ?? ''

  it('vale para todas as rotas e deixa as seguintes rodarem', () => {
    expect(rotaDeCabecalhos).toBeDefined()
    expect((rotaDeCabecalhos as { continue?: boolean }).continue).toBe(true)
    expect(rotas.indexOf(rotaDeCabecalhos as Rota)).toBeLessThan(indiceDaApi)
  })

  it.each([
    "default-src 'self'",
    "script-src 'self'",
    "connect-src 'self'",
    "img-src 'self' data:",
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
    "frame-ancestors 'none'",
  ])('a CSP tem a diretiva %s', (diretiva) => {
    expect(csp.split(';').map((parte) => parte.trim())).toContain(diretiva)
  })

  it("a CSP não tem 'unsafe-eval', 'unsafe-inline' em script, nem curinga", () => {
    expect(csp).not.toContain('unsafe-eval')
    expect(csp).not.toMatch(/(^|\s)\*(\s|;|$)/)
    expect(csp).not.toMatch(/https?:\/?\/?\*/)
    const script = csp.split(';').map((parte) => parte.trim()).find((parte) => parte.startsWith('script-src')) ?? ''
    expect(script).not.toContain('unsafe-inline')
  })

  it('manda HSTS de dois anos, nosniff, Referrer-Policy e Permissions-Policy', () => {
    expect(cabecalhos['Strict-Transport-Security']).toBe('max-age=63072000; includeSubDomains')
    expect(cabecalhos['X-Content-Type-Options']).toBe('nosniff')
    expect(cabecalhos['Referrer-Policy']).toBe('strict-origin-when-cross-origin')
    expect(cabecalhos['Permissions-Policy']).toMatch(/camera=\(\).*microphone=\(\).*geolocation=\(\)/)
  })

  it('o service worker não fica em cache, para a atualização chegar', () => {
    const rotaDoSw = rotas.find((rota) => rota.src === '^/sw\\.js$') as { headers?: Record<string, string> } | undefined
    expect(rotaDoSw?.headers).toEqual({ 'Cache-Control': 'no-cache' })
  })
})
