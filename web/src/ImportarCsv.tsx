import { useEffect, useRef, useState } from 'react'
import type { ChangeEvent } from 'react'
import {
  ArquivoJaImportadoError,
  SessaoExpiradaError,
  deleteMapeamentoCsv,
  fetchMapeamentosCsv,
  postImportacaoCsv,
  postMapeamentoCsv,
  postPreviaCsv,
  putMapeamentoCsv,
} from './api'
import type { Conta, CsvPrevia, MapeamentoCsv, ParametrosDoCsv } from './api'
import { carregar } from './leitura'
import type { Estado } from './leitura'
import EditorDeMapeamento from './EditorDeMapeamento'
import { camposDoMapeamento, camposIniciais, corpoDoMapeamento, parametrosDoMapeamento, problemaDosCampos, problemaDosColunas } from './mapeamentoCsv'
import type { CamposDoMapeamento } from './mapeamentoCsv'
import {
  MENSAGEM_ARQUIVO_GRANDE,
  TETO_DO_ARQUIVO_EM_BYTES,
  mensagemDeCsv,
  mensagemDeMapeamento,
} from './mensagensDeImportacao'
import PreviaCsv from './PreviaCsv'
import { Carregando } from './ui'

// A prévia gasta uma das 10 requisições por minuto do envio (ADR-049): o atraso evita uma chamada por tecla.
const ATRASO_DA_PREVIA_EM_MS = 600

type EstadoDaPrevia =
  | { tipo: 'ociosa' }
  | { tipo: 'carregando' }
  | { tipo: 'erro'; mensagem: string }
  | { tipo: 'ok'; previa: CsvPrevia; parametros: ParametrosDoCsv }

type Ocupado = 'salvando' | 'enviando' | 'apagando' | null

function foiCancelada(erro: unknown): boolean {
  return erro instanceof DOMException && erro.name === 'AbortError'
}

export default function ImportarCsv({
  contas,
  aoAbrir,
  aoSessaoExpirar,
}: {
  contas: Conta[]
  aoAbrir: (aberta: { id: string; aviso: string | null }) => void
  aoSessaoExpirar: () => void
}) {
  const [contaId, setContaId] = useState(contas[0]?.id ?? '')
  const [arquivo, setArquivo] = useState<File | null>(null)
  const [mapeamentos, setMapeamentos] = useState<Estado<MapeamentoCsv[]>>({ tipo: 'carregando' })
  const [selecionadoId, setSelecionadoId] = useState('')
  const [campos, setCampos] = useState<CamposDoMapeamento>(camposIniciais)
  const [confirmandoApagar, setConfirmandoApagar] = useState(false)
  const [ocupado, setOcupado] = useState<Ocupado>(null)
  const [mensagem, setMensagem] = useState<string | null>(null)
  const [previa, setPrevia] = useState<EstadoDaPrevia>({ tipo: 'ociosa' })
  const [tentativaDaPrevia, setTentativaDaPrevia] = useState(0)
  const emVoo = useRef(false)

  const lista = mapeamentos.tipo === 'ok' ? mapeamentos.dados : []
  const selecionado = lista.find((item) => item.id === selecionadoId) ?? null
  const alterado = selecionado !== null && JSON.stringify(campos) !== JSON.stringify(camposDoMapeamento(selecionado))
  const arquivoGrande = arquivo !== null && arquivo.size > TETO_DO_ARQUIVO_EM_BYTES
  const parametros = parametrosDoMapeamento(campos)
  const problemaDasColunas = problemaDosColunas(campos)

  // O efeito da prévia lê os parâmetros daqui e só roda quando a chave deles muda.
  const parametrosAtuais = useRef(parametros)
  useEffect(() => {
    parametrosAtuais.current = parametros
  })
  const chaveDosParametros = JSON.stringify(parametros)

  useEffect(() => {
    let vale = true
    carregar(fetchMapeamentosCsv, 'os mapeamentos', aoSessaoExpirar, (estado) => setMapeamentos(estado), () => vale)
    return () => {
      vale = false
    }
  }, [aoSessaoExpirar])

  useEffect(() => {
    const atuais = parametrosAtuais.current
    if (arquivo === null || arquivoGrande || atuais === null) {
      setPrevia({ tipo: 'ociosa' })
      return
    }
    const controle = new AbortController()
    setPrevia({ tipo: 'carregando' })
    const espera = setTimeout(() => {
      postPreviaCsv(atuais, arquivo, controle.signal).then(
        (dados) => {
          if (!controle.signal.aborted) setPrevia({ tipo: 'ok', previa: dados, parametros: atuais })
        },
        (erro: unknown) => {
          if (controle.signal.aborted || foiCancelada(erro)) return
          if (erro instanceof SessaoExpiradaError) {
            aoSessaoExpirar()
            return
          }
          setPrevia({ tipo: 'erro', mensagem: mensagemDeCsv(erro, 'Não foi possível montar a prévia. Tente de novo.') })
        },
      )
    }, ATRASO_DA_PREVIA_EM_MS)
    return () => {
      clearTimeout(espera)
      controle.abort()
    }
  }, [arquivo, arquivoGrande, chaveDosParametros, tentativaDaPrevia, aoSessaoExpirar])

  function escolherMapeamento(evento: ChangeEvent<HTMLSelectElement>): void {
    const escolhido = lista.find((item) => item.id === evento.target.value) ?? null
    setSelecionadoId(escolhido?.id ?? '')
    setCampos(escolhido === null ? camposIniciais() : camposDoMapeamento(escolhido))
    setConfirmandoApagar(false)
    setMensagem(null)
  }

  // Devolve o mapeamento gravado, ou null se a tela já mostrou o motivo de não ter gravado.
  async function salvar(): Promise<MapeamentoCsv | null> {
    const corpo = corpoDoMapeamento(campos)
    if (corpo === null) {
      setMensagem(problemaDosCampos(campos))
      return null
    }
    try {
      const gravado = selecionado === null ? await postMapeamentoCsv(corpo) : await putMapeamentoCsv(selecionado.id, corpo)
      setMapeamentos((estado) => {
        const atual = estado.tipo === 'ok' ? estado.dados : []
        const existe = atual.some((item) => item.id === gravado.id)
        return { tipo: 'ok', dados: existe ? atual.map((item) => (item.id === gravado.id ? gravado : item)) : [...atual, gravado] }
      })
      setSelecionadoId(gravado.id)
      setCampos(camposDoMapeamento(gravado))
      return gravado
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return null
      }
      setMensagem(mensagemDeMapeamento(erro, 'Não foi possível salvar o mapeamento. Tente de novo.'))
      return null
    }
  }

  async function clicarEmSalvar(): Promise<void> {
    if (emVoo.current) return
    emVoo.current = true
    setOcupado('salvando')
    setMensagem(null)
    try {
      await salvar()
    } finally {
      emVoo.current = false
      setOcupado(null)
    }
  }

  async function apagar(): Promise<void> {
    if (emVoo.current || selecionado === null) return
    emVoo.current = true
    setOcupado('apagando')
    setMensagem(null)
    try {
      await deleteMapeamentoCsv(selecionado.id)
      setMapeamentos((estado) => ({
        tipo: 'ok',
        dados: (estado.tipo === 'ok' ? estado.dados : []).filter((item) => item.id !== selecionado.id),
      }))
      setSelecionadoId('')
      setCampos(camposIniciais())
      setConfirmandoApagar(false)
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      setMensagem(mensagemDeMapeamento(erro, 'Não foi possível apagar o mapeamento. Tente de novo.'))
    } finally {
      emVoo.current = false
      setOcupado(null)
    }
  }

  // Envia com o mapeamento salvo. Se a tela tem um novo ou alterado, grava antes, para o servidor ler como a prévia mostrou.
  async function enviar(): Promise<void> {
    if (emVoo.current || arquivo === null) return
    if (arquivoGrande) {
      setMensagem(MENSAGEM_ARQUIVO_GRANDE)
      return
    }
    emVoo.current = true
    setOcupado('enviando')
    setMensagem(null)
    try {
      const mapeamento = selecionado === null || alterado ? await salvar() : selecionado
      if (mapeamento === null) return
      const detalhe = await postImportacaoCsv(contaId, mapeamento.id, arquivo)
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
      setMensagem(mensagemDeCsv(erro, 'Não foi possível enviar o arquivo. Tente de novo.'))
    } finally {
      emVoo.current = false
      setOcupado(null)
    }
  }

  const aviso = arquivoGrande ? MENSAGEM_ARQUIVO_GRANDE : mensagem

  return (
    <form
      onSubmit={(evento) => {
        evento.preventDefault()
        void enviar()
      }}
      aria-label="Enviar extrato CSV"
    >
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
        <label htmlFor="importar-arquivo">Arquivo do extrato (.csv ou .txt)</label>
        <input
          id="importar-arquivo"
          type="file"
          accept=".csv,.txt"
          onChange={(evento) => setArquivo(evento.target.files?.[0] ?? null)}
        />
        <small>Até 1 MiB, 2000 linhas e 50 colunas.</small>
      </div>

      <h3>Como ler as colunas</h3>
      {mapeamentos.tipo === 'carregando' && <Carregando texto="Carregando mapeamentos..." linhas={1} />}
      {mapeamentos.tipo === 'erro' && (
        <p role="alert" className="erro">
          {mapeamentos.mensagem}
        </p>
      )}
      <div className="campo">
        <label htmlFor="csv-mapeamento">Mapeamento salvo</label>
        <select id="csv-mapeamento" value={selecionadoId} onChange={escolherMapeamento}>
          <option value="">Novo mapeamento</option>
          {lista.map((item) => (
            <option key={item.id} value={item.id}>
              {item.nome}
            </option>
          ))}
        </select>
        <small>O mapeamento fica salvo para o próximo extrato do mesmo banco.</small>
      </div>
      <EditorDeMapeamento campos={campos} aoMudar={setCampos} />
      <div className="item-acoes">
        <button type="button" disabled={ocupado !== null} onClick={() => void clicarEmSalvar()}>
          {ocupado === 'salvando' ? 'Salvando...' : selecionado === null ? 'Salvar mapeamento' : 'Salvar alterações'}
        </button>
        {selecionado !== null &&
          (confirmandoApagar ? (
            <>
              <button type="button" className="perigo" disabled={ocupado !== null} onClick={() => void apagar()}>
                Confirmar exclusão
              </button>
              <button type="button" onClick={() => setConfirmandoApagar(false)}>
                Manter
              </button>
            </>
          ) : (
            <button type="button" className="perigo" disabled={ocupado !== null} onClick={() => setConfirmandoApagar(true)}>
              Apagar mapeamento
            </button>
          ))}
      </div>
      {selecionado !== null && confirmandoApagar && (
        <p className="item-meta">As importações já feitas com este mapeamento não mudam.</p>
      )}

      <h3>Prévia</h3>
      {arquivo === null && <p className="item-meta">Escolha o arquivo para ver como ele será lido.</p>}
      {arquivo !== null && problemaDasColunas !== null && (
        <p className="erro" aria-live="polite">
          {problemaDasColunas}
        </p>
      )}
      {previa.tipo === 'carregando' && <Carregando texto="Lendo as primeiras linhas..." linhas={2} />}
      {previa.tipo === 'erro' && (
        <>
          <p role="alert" className="erro">
            {previa.mensagem}
          </p>
          <button type="button" className="pequeno" onClick={() => setTentativaDaPrevia((n) => n + 1)}>
            Tentar a prévia de novo
          </button>
        </>
      )}
      {previa.tipo === 'ok' && <PreviaCsv previa={previa.previa} parametros={previa.parametros} />}

      {aviso !== null && (
        <p role="alert" className="erro">
          {aviso}
        </p>
      )}
      {alterado && <p className="item-meta">O mapeamento foi alterado. Ele será salvo ao enviar.</p>}
      <button
        type="submit"
        className="primario"
        disabled={ocupado !== null || contaId === '' || arquivo === null || arquivoGrande}
      >
        {ocupado === 'enviando' ? 'Enviando...' : 'Enviar extrato'}
      </button>
    </form>
  )
}
