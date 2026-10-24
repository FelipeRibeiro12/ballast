// Primeiro segmento de cada rota da API. O teste rotasDaApi.test.ts compara esta lista com o openapi.yaml.
export const prefixosDaApi: readonly string[] = [
  'ativos-cripto',
  'auth',
  'categorias',
  'compras-parceladas',
  'contas',
  'healthz',
  'indices',
  'lancamentos',
  'metas',
  'movimentos-cripto',
  'orcamentos',
  'patrimonio',
  'posicoes-cripto',
  'recorrencias',
  'relatorios',
  'saldo-projetado',
  'titulos-renda-fixa',
  'transferencias',
]

// Chave com '^' vira regex no proxy do Vite. Exige '/', '?' ou fim logo depois do segmento,
// para '/contas' casar e '/contasX' não.
export const padraoDoProxy = `^/(${prefixosDaApi.join('|')})(/|\\?|$)`
