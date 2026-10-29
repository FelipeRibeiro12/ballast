import {
  ArquivoJaImportadoError,
  ImportacaoNaoEncontradaError,
  ImportacaoRecusadaError,
  LimiteTentativasError,
  MapeamentoNaoEncontradoError,
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
    return 'Não consegui ler este arquivo. Ele precisa ser um extrato OFX ou CSV com até 2000 transações.'
  }
  if (erro.status === 415) return 'O arquivo não pôde ser enviado neste formato.'
  if (erro.status === 409 && erro.mensagemServidor?.startsWith('limite') === true) {
    return 'Você já tem 20 importações pendentes. Confirme ou descarte alguma antes de enviar outro arquivo.'
  }
  if (erro.status === 409) return 'Esta importação já foi confirmada ou está em andamento. Atualize a tela.'
  return erro.mensagemServidor ?? padrao
}

// Envio e prévia de CSV. 400, 404 e 422 têm texto próprio: o corpo do servidor nunca vai para a tela.
export function mensagemDeCsv(erro: unknown, padrao: string): string {
  if (erro instanceof MapeamentoNaoEncontradoError) {
    return 'Não encontrei a conta ou o mapeamento. Atualize a tela e escolha de novo.'
  }
  if (erro instanceof ImportacaoRecusadaError) {
    if (erro.status === 422) {
      return 'Não consegui ler este arquivo com esse mapeamento. Confira o separador e as colunas. O CSV pode ter até 2000 linhas e 50 colunas.'
    }
    if (erro.status === 400) return 'O servidor não aceitou os dados do mapeamento ou da conta. Confira os campos.'
  }
  return mensagemDeImportacao(erro, padrao)
}

// Salvar e apagar mapeamento. O 409 tem dois motivos, separados pelo começo da mensagem fixa do servidor.
export function mensagemDeMapeamento(erro: unknown, padrao: string): string {
  if (erro instanceof MapeamentoNaoEncontradoError) return 'Não encontrei este mapeamento. Ele pode já ter sido apagado.'
  if (erro instanceof ImportacaoRecusadaError) {
    if (erro.status === 409) {
      return erro.mensagemServidor?.startsWith('limite') === true
        ? 'Você já tem 20 mapeamentos. Apague algum antes de criar outro.'
        : 'Já existe um mapeamento com esse nome. Escolha outro.'
    }
    if (erro.status === 400) return 'O servidor não aceitou os dados do mapeamento. Confira o nome e as colunas.'
  }
  return mensagemDeImportacao(erro, padrao)
}

// Teto do PDF de fatura no servidor (ADR-073).
export const TETO_DO_PDF_EM_BYTES = 5 * 1024 * 1024

export const MENSAGEM_PDF_GRANDE = 'O PDF passa de 5 MiB. Baixe a fatura de novo no app do banco.'

// Envio de PDF. O 422 junta as causas conhecidas porque o corpo do servidor nunca vai para a tela.
export function mensagemDePdf(erro: unknown, padrao: string): string {
  if (erro instanceof ImportacaoRecusadaError) {
    if (erro.status === 413) return MENSAGEM_PDF_GRANDE
    if (erro.status === 422) {
      return 'Não consegui ler esta fatura. O PDF precisa ter texto (não pode ser imagem escaneada), não ter senha e ter até 30 páginas. A leitura é melhor esforço e pode falhar. Se o banco oferece OFX ou CSV, use um deles.'
    }
    if (erro.status === 400) return 'O servidor não aceitou a conta. Escolha uma conta de cartão de crédito.'
  }
  return mensagemDeImportacao(erro, padrao)
}
