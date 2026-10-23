import {
  CriptoInvalidoError,
  CriptoRepetidoError,
  MetaInvalidaError,
  OrcamentoInvalidoError,
  ServicoIndisponivelError,
  SessaoExpiradaError,
} from './api'

// Leitura e mensagens comuns às telas de Investimentos (cripto e renda fixa), Orçamento e Metas.
export type Estado<T> = { tipo: 'carregando' } | { tipo: 'erro'; mensagem: string } | { tipo: 'ok'; dados: T }

export const MENSAGEM_INDISPONIVEL = 'Serviço indisponível. Tente de novo em instantes.'

export function mensagemDeLeitura(erro: unknown, oQue: string): string {
  if (erro instanceof ServicoIndisponivelError) return MENSAGEM_INDISPONIVEL
  if (
    (erro instanceof CriptoInvalidoError ||
      erro instanceof OrcamentoInvalidoError ||
      erro instanceof MetaInvalidaError) &&
    erro.mensagemServidor !== null
  ) {
    return erro.mensagemServidor
  }
  return `Não foi possível carregar ${oQue}.`
}

export function mensagemDeEscrita(erro: unknown, padrao: string): string {
  if (
    (erro instanceof CriptoInvalidoError ||
      erro instanceof CriptoRepetidoError ||
      erro instanceof OrcamentoInvalidoError ||
      erro instanceof MetaInvalidaError) &&
    erro.mensagemServidor !== null
  ) {
    return erro.mensagemServidor
  }
  if (erro instanceof ServicoIndisponivelError) return MENSAGEM_INDISPONIVEL
  return padrao
}

// Entrega o resultado em guardar, a menos que a tela já tenha mudado (aindaVale falso).
export function carregar<T>(
  buscar: () => Promise<T>,
  oQue: string,
  aoSessaoExpirar: () => void,
  guardar: (estado: Estado<T>) => void,
  aindaVale: () => boolean,
): void {
  buscar().then(
    (dados) => {
      if (aindaVale()) guardar({ tipo: 'ok', dados })
    },
    (erro: unknown) => {
      if (!aindaVale()) return
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      guardar({ tipo: 'erro', mensagem: mensagemDeLeitura(erro, oQue) })
    },
  )
}
