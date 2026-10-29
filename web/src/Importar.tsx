import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { ArquivoJaImportadoError, SessaoExpiradaError, fetchImportacoes, postImportacao } from './api'
import type { Conta, Importacao } from './api'
import { formatarDataCompleta, diaEmSaoPaulo } from './datas'
import { carregar } from './leitura'
import type { Estado } from './leitura'
import {
  MENSAGEM_ARQUIVO_GRANDE,
  TETO_DO_ARQUIVO_EM_BYTES,
  mensagemDeImportacao,
} from './mensagensDeImportacao'
import ImportarCsv from './ImportarCsv'
import ImportarPdf from './ImportarPdf'
import RevisaoDeImportacao from './RevisaoDeImportacao'
import { Carregando, EstadoVazio } from './ui'

type Aberta = { id: string; aviso: string | null }

function FormularioDeEnvio({
  contas,
  aoAbrir,
  aoSessaoExpirar,
}: {
  contas: Conta[]
  aoAbrir: (aberta: Aberta) => void
  aoSessaoExpirar: () => void
}) {
  const [contaId, setContaId] = useState(contas[0]?.id ?? '')
  const [arquivo, setArquivo] = useState<File | null>(null)
  const [enviando, setEnviando] = useState(false)
  const [mensagem, setMensagem] = useState<string | null>(null)
  const emVoo = useRef(false)

  async function enviar(evento: FormEvent<HTMLFormElement>): Promise<void> {
    evento.preventDefault()
    if (emVoo.current || arquivo === null) return
    if (arquivo.size > TETO_DO_ARQUIVO_EM_BYTES) {
      setMensagem(MENSAGEM_ARQUIVO_GRANDE)
      return
    }
    emVoo.current = true
    setEnviando(true)
    setMensagem(null)
    try {
      const detalhe = await postImportacao(contaId, arquivo)
      aoAbrir({ id: detalhe.id, aviso: null })
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      if (erro instanceof ArquivoJaImportadoError) {
        aoAbrir({ id: erro.importacaoId, aviso: 'Este arquivo já foi enviado antes. Abri a importação que já existe.' })
        return
      }
      setMensagem(mensagemDeImportacao(erro, 'Não foi possível enviar o arquivo. Tente de novo.'))
    } finally {
      emVoo.current = false
      setEnviando(false)
    }
  }

  return (
    <form onSubmit={(evento) => void enviar(evento)} aria-label="Enviar extrato">
      <div className="campo">
        <label htmlFor="importar-conta">Conta do extrato</label>
        <select id="importar-conta" value={contaId} onChange={(evento) => setContaId(evento.target.value)}>
          {contas.map((conta) => (
            <option key={conta.id} value={conta.id}>
              {conta.nome}
            </option>
          ))}
        </select>
      </div>
      <div className="campo">
        <label htmlFor="importar-arquivo">Arquivo do extrato (.ofx ou .ofc)</label>
        <input
          id="importar-arquivo"
          type="file"
          accept=".ofx,.ofc"
          onChange={(evento) => setArquivo(evento.target.files?.[0] ?? null)}
        />
        <small>Até 1 MiB e 2000 transações.</small>
      </div>
      {mensagem !== null && (
        <p role="alert" className="erro">
          {mensagem}
        </p>
      )}
      <button type="submit" className="primario" disabled={enviando || contaId === '' || arquivo === null}>
        {enviando ? 'Enviando...' : 'Enviar extrato'}
      </button>
    </form>
  )
}

// Enviar o extrato (OFX, CSV) ou a fatura (PDF), retomar uma importação pendente e revisar linha a linha (ADR-008, ADR-069).
export default function Importar({
  contas,
  aoSessaoExpirar,
  aoMudarLancamentos,
}: {
  contas: Conta[] | null
  aoSessaoExpirar: () => void
  aoMudarLancamentos: () => void
}) {
  const [versao, setVersao] = useState(0)
  const [estado, setEstado] = useState<Estado<Importacao[]>>({ tipo: 'carregando' })
  const [aberta, setAberta] = useState<Aberta | null>(null)
  const [formato, setFormato] = useState<'ofx' | 'csv' | 'pdf'>('ofx')

  useEffect(() => {
    let vale = true
    carregar(fetchImportacoes, 'as importações', aoSessaoExpirar, setEstado, () => vale)
    return () => {
      vale = false
    }
  }, [versao, aoSessaoExpirar])

  if (aberta !== null) {
    const nomeDaConta = (id: string): string => contas?.find((conta) => conta.id === id)?.nome ?? 'Conta'
    return (
      <RevisaoDeImportacao
        importacaoId={aberta.id}
        aviso={aberta.aviso}
        nomeDaConta={nomeDaConta}
        aoVoltar={() => {
          setAberta(null)
          setEstado({ tipo: 'carregando' })
          setVersao((n) => n + 1)
        }}
        aoMudarLancamentos={aoMudarLancamentos}
        aoSessaoExpirar={aoSessaoExpirar}
      />
    )
  }

  const pendentes = estado.tipo === 'ok' ? estado.dados.filter((item) => item.status === 'pendente') : []

  return (
    <section aria-labelledby="titulo-importar">
      <header className="pagina-topo">
        <h2 id="titulo-importar">Importar extrato</h2>
      </header>
      <p className="item-meta">
        O arquivo não fica guardado: o app lê as transações e descarta o conteúdo. Nada entra no extrato antes de você
        revisar e confirmar.
      </p>
      {contas === null && <Carregando texto="Carregando contas..." linhas={1} />}
      {contas !== null && contas.length === 0 && (
        <EstadoVazio titulo="Nenhuma conta para receber o extrato." dica="Crie uma conta em Contas antes de importar." />
      )}
      {contas !== null && contas.length > 0 && (
        <>
          <fieldset>
            <legend>Formato do arquivo</legend>
            <label>
              <input type="radio" name="formato-do-extrato" checked={formato === 'ofx'} onChange={() => setFormato('ofx')} />{' '}
              OFX (extrato do banco)
            </label>{' '}
            <label>
              <input type="radio" name="formato-do-extrato" checked={formato === 'csv'} onChange={() => setFormato('csv')} />{' '}
              CSV (planilha)
            </label>{' '}
            <label>
              <input type="radio" name="formato-do-extrato" checked={formato === 'pdf'} onChange={() => setFormato('pdf')} />{' '}
              PDF (fatura do cartão, melhor esforço)
            </label>
          </fieldset>
          {formato === 'ofx' ? (
            <FormularioDeEnvio contas={contas} aoAbrir={setAberta} aoSessaoExpirar={aoSessaoExpirar} />
          ) : formato === 'csv' ? (
            <ImportarCsv contas={contas} aoAbrir={setAberta} aoSessaoExpirar={aoSessaoExpirar} />
          ) : (
            <ImportarPdf contas={contas} aoAbrir={setAberta} aoSessaoExpirar={aoSessaoExpirar} />
          )}
        </>
      )}
      <h3>Importações pendentes</h3>
      {estado.tipo === 'carregando' && <Carregando texto="Carregando importações..." />}
      {estado.tipo === 'erro' && (
        <p role="alert" className="erro">
          {estado.mensagem}
        </p>
      )}
      {estado.tipo === 'ok' && pendentes.length === 0 && (
        <EstadoVazio titulo="Nenhuma importação pendente." dica="Envie um arquivo para começar." />
      )}
      {pendentes.length > 0 && (
        <ul className="lancamentos lista-cartao" aria-label="Importações pendentes">
          {pendentes.map((item) => {
            const nome = contas?.find((conta) => conta.id === item.contaId)?.nome ?? 'Conta'
            const data = formatarDataCompleta(diaEmSaoPaulo(item.criadoEm))
            return (
              <li key={item.id}>
                <div className="item-corpo">
                  <strong className="item-titulo">{`${nome}, enviada em ${data}`}</strong>
                  <span className="item-meta">
                    {item.totalLinhas} linhas: {item.totalImportar} para criar, {item.totalConciliar} para conciliar,{' '}
                    {item.totalIgnorar} para ignorar
                  </span>
                </div>
                <div className="item-lado">
                  <button
                    type="button"
                    className="pequeno"
                    aria-label={`Revisar importação de ${nome} enviada em ${data}`}
                    onClick={() => setAberta({ id: item.id, aviso: null })}
                  >
                    Revisar
                  </button>
                </div>
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}
