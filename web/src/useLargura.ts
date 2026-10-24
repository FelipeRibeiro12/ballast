import { useEffect, useState } from 'react'
import type { RefObject } from 'react'

const LARGURA_PADRAO = 320

// ResizeObserver não existe no jsdom: lá vale a largura padrão.
export function useLargura(caixa: RefObject<HTMLDivElement | null>): number {
  const [largura, setLargura] = useState(LARGURA_PADRAO)
  useEffect(() => {
    const elemento = caixa.current
    if (elemento === null || typeof ResizeObserver === 'undefined') return
    const observador = new ResizeObserver(([entrada]) => {
      const nova = Math.round(entrada.contentRect.width)
      if (nova > 0) setLargura(nova)
    })
    observador.observe(elemento)
    return () => observador.disconnect()
  }, [caixa])
  return largura
}
