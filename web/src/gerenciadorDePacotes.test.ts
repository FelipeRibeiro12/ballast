/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

// O Vercel só ativa o Corepack pelo package.json da raiz do repositório (ADR-044); o resto do
// projeto lê o de web/. Versões diferentes fariam o build de produção usar outro pnpm.
function gerenciador(caminho: string): unknown {
  const conteudo = JSON.parse(readFileSync(resolve(process.cwd(), caminho), 'utf8')) as { packageManager?: unknown }
  return conteudo.packageManager
}

describe('versão do pnpm', () => {
  it('é a mesma na raiz e em web/', () => {
    expect(gerenciador('../package.json')).toBe(gerenciador('package.json'))
    expect(gerenciador('package.json')).toMatch(/^pnpm@\d+\.\d+\.\d+$/)
  })
})
