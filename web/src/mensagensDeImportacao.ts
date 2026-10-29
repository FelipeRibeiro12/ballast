import {
  ArquivoJaImportadoError,
  ImportacaoNaoEncontradaError,
  ImportacaoRecusadaError,
  LimiteTentativasError,
  ServicoIndisponivelError,
} from './api'
import { MENSAGEM_INDISPONIVEL, MENSAGEM_LIMITE } from './leitura'

// Teto do servidor (ADR-069). A tela barra antes de enviar para não gastar a rede à toa.
export const TETO_DO_ARQUIVO_EM_BYTES = 1024 * 1024

export const MENSAGEM_ARQUIVO_GRANDE = 'O arquivo passa de 1 MiB. Exporte um período menor no seu banco.'

// O texto vem do status, não do corpo, para o usuário nunca ver um trecho cru do servidor num erro de arquivo.
export function mensagemDeImportacao(erro: unknown, padrao: string): string {
  if (erro instanceof ServicoIndisponivelError) return MENSAGEM_INDISPONIVEL
  if (erro instanceof LimiteTentativasError) return MENSAGEM_LIMITE
  if (erro instanceof ImportacaoNaoEncontradaError) return 'Não encontrei esta importação. Ela pode ter sido descartada.'
  if (erro instanceof ArquivoJaImportadoError) return 'Este arquivo já foi enviado antes.'
  if (!(erro instanceof ImportacaoRecusadaError)) return padrao
  if (erro.status === 413) return MENSAGEM_ARQUIVO_GRANDE
  if (erro.status === 422) {
    return 'Não consegui ler este arquivo. Ele precisa ser um extrato OFX com até 2000 transações.'
  }
  if (erro.status === 415) return 'O arquivo não pôde ser enviado neste formato.'
  if (erro.status === 409 && erro.mensagemServidor?.startsWith('limite') === true) {
    return 'Você já tem 20 importações pendentes. Confirme ou descarte alguma antes de enviar outro arquivo.'
  }
  if (erro.status === 409) return 'Esta importação já foi confirmada ou está em andamento. Atualize a tela.'
  return erro.mensagemServidor ?? padrao
}
