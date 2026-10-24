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
  'posso-gastar',
  'recorrencias',
  'relatorios',
  'saldo-projetado',
  'titulos-renda-fixa',
  'transferencias',
]

// Chave com '^' vira regex no proxy do Vite. Exige '/', '?' ou fim logo depois do segmento,
// para '/contas' casar e '/contasX' não.
export const padraoDoProxy = `^/(${prefixosDaApi.join('|')})(/|\\?|$)`

// Em produção o front chama a API por este prefixo, na mesma origem, e o vercel.json repassa para
// a VM sem ele (ADR-044). Mesma origem por causa do cookie SameSite=Lax (ADR-020).
export const PREFIXO_DA_API = '/api'

// O e2e serve o build de produção pelo vite preview: este padrão tira o prefixo como o Vercel.
export const padraoDoPrefixo = `^${PREFIXO_DA_API}(/|\\?|$)`

type AmbienteDoBuild = { PROD: boolean; VITE_API_URL: string | undefined }

// VITE_API_URL explícita vence. Sem ela, o build de produção usa o prefixo; o dev e o vitest
// chamam a raiz, que o proxy do Vite repassa.
export function baseDaApi(ambiente: AmbienteDoBuild): string {
  return ambiente.VITE_API_URL ?? (ambiente.PROD ? PREFIXO_DA_API : '')
}

// Roda no vite.config.ts do build de produção: uma URL com esquema ou host poria a API em outra
// origem, e o navegador deixaria de mandar o cookie de sessão.
export function conferirOrigemDeProducao(ambiente: { VITE_API_URL: string | undefined }): void {
  const url = ambiente.VITE_API_URL
  if (url !== undefined && url !== '' && !/^\/(?!\/)/.test(url)) {
    throw new Error(`VITE_API_URL tem de ser um caminho na mesma origem, como ${PREFIXO_DA_API}; veio ${url}`)
  }
}
