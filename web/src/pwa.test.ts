/// <reference types="node" />
import { existsSync } from 'node:fs'
import { resolve } from 'node:path'
import { opcoesDoPwa } from './pwa.ts'

const pastaPublic = resolve(process.cwd(), 'public')

describe('opcoesDoPwa', () => {
  it('pede confirmação antes de trocar a versão e não liga o service worker no dev', () => {
    expect(opcoesDoPwa.registerType).toBe('prompt')
    expect(opcoesDoPwa.strategies).toBe('generateSW')
    expect(opcoesDoPwa.devOptions?.enabled).toBe(false)
  })

  it('não guarda resposta de rede em cache', () => {
    expect(opcoesDoPwa.workbox).toBeDefined()
    expect(opcoesDoPwa.workbox).not.toHaveProperty('runtimeCaching')
  })

  it('faz precache só do shell do app', () => {
    expect(opcoesDoPwa.workbox?.globPatterns).toEqual(['**/*.{html,js,css,svg,png,webmanifest}'])
    expect(opcoesDoPwa.workbox?.navigateFallback).toBe('/index.html')
  })

  describe('denylist do navigateFallback', () => {
    const denylist = opcoesDoPwa.workbox?.navigateFallbackDenylist ?? []
    const cai = (caminho: string): boolean => denylist.some((regex) => regex.test(caminho))

    it.each(['/contas', '/contas/1', '/lancamentos?de=x', '/auth/eu', '/healthz', '/saldo-projetado'])(
      'deixa %s fora do fallback',
      (caminho) => {
        expect(cai(caminho)).toBe(true)
      },
    )

    it.each(['/', '/index.html', '/contasX'])('mantém %s no fallback', (caminho) => {
      expect(cai(caminho)).toBe(false)
    })
  })

  describe('manifest', () => {
    const manifest = opcoesDoPwa.manifest

    it('traz os campos do ADR-030', () => {
      expect(manifest).toMatchObject({
        name: 'Ballast',
        short_name: 'Ballast',
        lang: 'pt-BR',
        start_url: '/',
        scope: '/',
        display: 'standalone',
        theme_color: '#1a4d8f',
        background_color: '#ffffff',
      })
      expect(manifest && manifest.description).toBeTruthy()
    })

    it('declara os ícones de 192, 512 e 512 maskable, todos existentes em public', () => {
      const icones = manifest ? (manifest.icons ?? []) : []
      expect(icones.map((i) => [i.src, i.sizes, i.purpose])).toEqual([
        ['pwa-192x192.png', '192x192', 'any'],
        ['pwa-512x512.png', '512x512', 'any'],
        ['maskable-icon-512x512.png', '512x512', 'maskable'],
      ])
      for (const icone of icones) {
        expect(existsSync(resolve(pastaPublic, icone.src))).toBe(true)
      }
    })

    it('tem o ícone do iOS em public', () => {
      expect(existsSync(resolve(pastaPublic, 'apple-touch-icon-180x180.png'))).toBe(true)
    })
  })
})
