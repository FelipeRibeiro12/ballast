import { useCallback, useEffect, useState } from 'react'

export type Secao = 'inicio' | 'lancamentos' | 'contas' | 'recorrencias'

export const SECOES: readonly Secao[] = ['inicio', 'lancamentos', 'contas', 'recorrencias']

export const ROTULO_DA_SECAO: Record<Secao, string> = {
  inicio: 'Início',
  lancamentos: 'Lançamentos',
  contas: 'Contas',
  recorrencias: 'Recorrências',
}

// Hash desconhecido ou vazio cai no Início: um link velho nunca deixa a tela em branco.
export function lerSecao(hash: string): Secao {
  const nome = hash.replace(/^#\/?/, '')
  return SECOES.find((secao) => secao === nome) ?? 'inicio'
}

export function hashDaSecao(secao: Secao): string {
  return `#/${secao}`
}

// Rota por hash: o botão voltar funciona e o servidor nunca vê o caminho (ADR-033).
export function useRota(): [Secao, (secao: Secao) => void] {
  const [secao, setSecao] = useState<Secao>(() => lerSecao(window.location.hash))

  useEffect(() => {
    function aoMudarOHash(): void {
      setSecao(lerSecao(window.location.hash))
      // O hash não rola a página sozinho, e a seção nova começa do topo.
      window.scrollTo(0, 0)
    }
    window.addEventListener('hashchange', aoMudarOHash)
    return () => window.removeEventListener('hashchange', aoMudarOHash)
  }, [])

  const irPara = useCallback((destino: Secao): void => {
    window.location.hash = hashDaSecao(destino)
  }, [])

  return [secao, irPara]
}
