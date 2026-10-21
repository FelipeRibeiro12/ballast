import type { Categoria, Conta } from './api'

// A restrição vale só para despesa em benefício com lista não vazia (ADR-034); receita vê todas.
export function categoriasDaConta(
  categorias: Categoria[],
  tipo: Categoria['tipo'],
  conta: Conta | undefined,
): { categorias: Categoria[]; restrita: boolean } {
  const permitidas = conta?.categoriasPermitidas ?? []
  const restrita = tipo === 'despesa' && permitidas.length > 0
  const doTipo = categorias.filter((categoria) => categoria.tipo === tipo)
  return { categorias: restrita ? doTipo.filter((categoria) => permitidas.includes(categoria.id)) : doTipo, restrita }
}

// Sem restrição, escolha que sumiu cai na primeira da lista. Com restrição, fica vazio para o usuário
// escolher de novo: trocar de conta não pode trocar a categoria dele por uma que ele não viu.
export function categoriaEfetiva(escolhida: string, disponiveis: Categoria[], restrita: boolean): string {
  if (disponiveis.some((categoria) => categoria.id === escolhida)) return escolhida
  return restrita ? '' : (disponiveis[0]?.id ?? '')
}
