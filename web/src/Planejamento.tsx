import { hashDaSecao } from './rota'

// Orçamento e Metas ficam fora da barra de navegação (ADR-043): estes links levam de uma à outra.
export default function AtalhosDePlanejamento({ atual }: { atual: 'orcamento' | 'metas' }) {
  return (
    <nav className="segmentos" aria-label="Planejamento">
      <a href={hashDaSecao('orcamento')} aria-current={atual === 'orcamento' ? 'page' : undefined}>
        Orçamento
      </a>
      <a href={hashDaSecao('metas')} aria-current={atual === 'metas' ? 'page' : undefined}>
        Metas
      </a>
    </nav>
  )
}
