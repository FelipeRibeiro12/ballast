import { hashDaSecao } from './rota'

type SecaoDePlanejamento = 'orcamento' | 'metas' | 'relatorios' | 'categorias' | 'terceiros'

const ATALHOS: readonly { secao: SecaoDePlanejamento; rotulo: string }[] = [
  { secao: 'orcamento', rotulo: 'Orçamento' },
  { secao: 'metas', rotulo: 'Metas' },
  { secao: 'relatorios', rotulo: 'Relatórios' },
  { secao: 'categorias', rotulo: 'Categorias' },
  { secao: 'terceiros', rotulo: 'Terceiros' },
]

// Orçamento, Metas e Relatórios ficam fora da barra de navegação (ADR-043): estes links levam de
// uma à outra.
export default function AtalhosDePlanejamento({ atual }: { atual: SecaoDePlanejamento }) {
  return (
    <nav className="segmentos" aria-label="Planejamento">
      {ATALHOS.map(({ secao, rotulo }) => (
        <a key={secao} href={hashDaSecao(secao)} aria-current={atual === secao ? 'page' : undefined}>
          {rotulo}
        </a>
      ))}
    </nav>
  )
}
