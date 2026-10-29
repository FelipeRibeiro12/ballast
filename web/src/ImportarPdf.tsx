import { useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { ArquivoJaImportadoError, SessaoExpiradaError, postImportacaoPdf } from './api'
import type { Conta } from './api'
import { MENSAGEM_PDF_GRANDE, TETO_DO_PDF_EM_BYTES, mensagemDePdf } from './mensagensDeImportacao'
import { EstadoVazio } from './ui'

// Fatura em PDF (ADR-073): melhor esforço, sempre cai na revisão. O aviso fica visível antes do envio.
export default function ImportarPdf({
  contas,
  aoAbrir,
  aoSessaoExpirar,
}: {
  contas: Conta[]
  aoAbrir: (aberta: { id: string; aviso: string | null }) => void
  aoSessaoExpirar: () => void
}) {
  const cartoes = contas.filter((conta) => conta.tipo === 'cartao_credito' && !conta.arquivada)
  const [contaId, setContaId] = useState(cartoes[0]?.id ?? '')
  const [arquivo, setArquivo] = useState<File | null>(null)
  const [enviando, setEnviando] = useState(false)
  const [mensagem, setMensagem] = useState<string | null>(null)
  const emVoo = useRef(false)

  async function enviar(evento: FormEvent<HTMLFormElement>): Promise<void> {
    evento.preventDefault()
    if (emVoo.current || arquivo === null) return
    if (arquivo.size > TETO_DO_PDF_EM_BYTES) {
      setMensagem(MENSAGEM_PDF_GRANDE)
      return
    }
    emVoo.current = true
    setEnviando(true)
    setMensagem(null)
    try {
      const detalhe = await postImportacaoPdf(contaId, arquivo)
      aoAbrir({ id: detalhe.id, aviso: null })
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      if (erro instanceof ArquivoJaImportadoError) {
        aoAbrir({ id: erro.importacaoId, aviso: 'Este PDF já foi enviado antes. Abri a importação que já existe.' })
        return
      }
      setMensagem(mensagemDePdf(erro, 'Não foi possível enviar o PDF. Tente de novo.'))
    } finally {
      emVoo.current = false
      setEnviando(false)
    }
  }

  if (cartoes.length === 0) {
    return (
      <EstadoVazio
        titulo="Nenhum cartão de crédito para receber a fatura."
        dica="Crie uma conta de cartão em Contas antes de importar a fatura."
      />
    )
  }

  return (
    <form onSubmit={(evento) => void enviar(evento)} aria-label="Enviar fatura em PDF">
      <p className="item-meta" role="note">
        Leitura de PDF é melhor esforço e pode falhar: o app tenta achar data, descrição e valor de cada linha, e o que
        não reconhecer aparece para você ignorar. Confira todas as linhas antes de confirmar. PDF escaneado (imagem) ou
        com senha não funciona.
      </p>
      <div className="campo">
        <label htmlFor="importar-pdf-conta">Cartão da fatura</label>
        <select id="importar-pdf-conta" value={contaId} onChange={(evento) => setContaId(evento.target.value)}>
          {cartoes.map((conta) => (
            <option key={conta.id} value={conta.id}>
              {conta.nome}
            </option>
          ))}
        </select>
      </div>
      <div className="campo">
        <label htmlFor="importar-pdf-arquivo">Fatura em PDF</label>
        <input
          id="importar-pdf-arquivo"
          type="file"
          accept=".pdf,application/pdf"
          onChange={(evento) => setArquivo(evento.target.files?.[0] ?? null)}
        />
        <small>Até 5 MiB e 30 páginas.</small>
      </div>
      {mensagem !== null && (
        <p role="alert" className="erro">
          {mensagem}
        </p>
      )}
      <button type="submit" className="primario" disabled={enviando || contaId === '' || arquivo === null}>
        {enviando ? 'Enviando...' : 'Enviar fatura'}
      </button>
    </form>
  )
}
