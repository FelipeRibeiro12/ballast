import type { ReactNode } from 'react'
import { IconeVazio } from './icones'

// Texto para leitor de tela e barras para os olhos: o aviso não depende do desenho.
export function Carregando({ texto, linhas = 3 }: { texto: string; linhas?: number }) {
  return (
    <div className="carregando" aria-live="polite">
      <span className="so-leitor">{texto}</span>
      <div aria-hidden="true">
        {Array.from({ length: linhas }, (_, indice) => (
          <div key={indice} className="esqueleto" />
        ))}
      </div>
    </div>
  )
}

export type TomDoSelo = 'previsto' | 'cancelado' | 'neutro' | 'transferencia' | 'paga' | 'vencida' | 'aberta' | 'fechada'

export function Selo({ tom, children }: { tom: TomDoSelo; children: ReactNode }) {
  return <span className={`selo selo-${tom}`}>{children}</span>
}

export function EstadoVazio({ titulo, dica, acao }: { titulo: string; dica?: string; acao?: ReactNode }) {
  return (
    <div className="vazio">
      <IconeVazio />
      <p className="vazio-titulo">{titulo}</p>
      {dica !== undefined && <p className="vazio-dica">{dica}</p>}
      {acao}
    </div>
  )
}
