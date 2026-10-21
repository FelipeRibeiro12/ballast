import { useCallback, useState } from 'react'

export type Tema = 'claro' | 'escuro' | 'sistema'

export const TEMAS: readonly Tema[] = ['claro', 'escuro', 'sistema']

export const ROTULO_DO_TEMA: Record<Tema, string> = {
  claro: 'Claro',
  escuro: 'Escuro',
  sistema: 'Sistema',
}

const CHAVE = 'ballast.tema'

// localStorage pode lançar (modo privado, cota, política do navegador): sem ele vale o do sistema.
export function lerTema(): Tema {
  try {
    const guardado = window.localStorage.getItem(CHAVE)
    return TEMAS.find((tema) => tema === guardado) ?? 'sistema'
  } catch {
    return 'sistema'
  }
}

// Devolve false quando não deu para guardar; a escolha ainda vale até fechar a aba.
export function salvarTema(tema: Tema): boolean {
  try {
    if (tema === 'sistema') window.localStorage.removeItem(CHAVE)
    else window.localStorage.setItem(CHAVE, tema)
    return true
  } catch {
    return false
  }
}

// "sistema" tira o atributo e deixa o prefers-color-scheme do CSS decidir.
export function aplicarTema(tema: Tema): void {
  const raiz = document.documentElement
  if (tema === 'sistema') delete raiz.dataset.tema
  else raiz.dataset.tema = tema
}

export function useTema(): [Tema, (tema: Tema) => void] {
  const [tema, setTema] = useState<Tema>(lerTema)

  const escolher = useCallback((novo: Tema): void => {
    aplicarTema(novo)
    salvarTema(novo)
    setTema(novo)
  }, [])

  return [tema, escolher]
}
