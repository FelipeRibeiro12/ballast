import { useEffect, useRef, useState } from 'react'
import {
  ImportacaoRecusadaError,
  SessaoExpiradaError,
  deleteImportacao,
  fetchCategorias,
  fetchImportacao,
  fetchLancamentos,
  postConfirmacaoDeImportacao,
  putImportacaoLinha,
} from './api'
import type {
  Categoria,
  ImportacaoConfirmacao,
  ImportacaoDetalhe,
  ImportacaoLinha,
  ImportacaoLinhaRequest,
  Lancamento,
} from './api'
import { formatarCentavos } from './dinheiro'
import { formatarDataCompleta, somarDias } from './datas'
import { carregar } from './leitura'
import type { Estado } from './leitura'
import { mensagemDeImportacao } from './mensagensDeImportacao'
import { Carregando, Selo } from './ui'

type Dados = { detalhe: ImportacaoDetalhe; categorias: Categoria[] }

const ROTULO_DA_ACAO = { importar: 'Importar', ignorar: 'Ignorar', conciliar: 'Conciliar' } as const

const MOTIVO_DA_LINHA: Record<string, string> = {
  ja_importada: 'Já importada antes',
  duplicada_no_arquivo: 'Repetida no arquivo',
}

// Quando a proposta já ignorou a linha, só "ignorar" vale (ADR-069): inválida, repetida ou já importada.
function linhaTravada(linha: ImportacaoLinha): boolean {
  return !linha.valida || linha.proposta === 'ignorar'
}

function textoDoMotivo(linha: ImportacaoLinha): string | null {
  if (!linha.valida) return linha.erro === null ? 'Linha que o app não conseguiu ler' : `Linha inválida: ${linha.erro}`
  if (linha.motivo === null) return null
  return MOTIVO_DA_LINHA[linha.motivo] ?? null
}

function textoDaProposta(linha: ImportacaoLinha): string {
  if (linha.proposta === 'conciliar') return 'Proposta: conciliar com um lançamento que você já tem (mesmo valor, data próxima).'
  if (linha.proposta === 'importar') return 'Proposta: criar um lançamento novo.'
  return 'Proposta: ignorar.'
}

function LinhaDeRevisao({
  linha,
  categorias,
  candidato,
  candidatoDisponivel,
  bloqueada,
  salvando,
  erro,
  aoSalvar,
}: {
  linha: ImportacaoLinha
  categorias: Categoria[]
  candidato: Lancamento | undefined
  candidatoDisponivel: boolean
  bloqueada: boolean
  salvando: boolean
  erro: string | null
  aoSalvar: (dados: ImportacaoLinhaRequest) => void
}) {
  const [titulo, setTitulo] = useState(linha.titulo)
  const travada = linhaTravada(linha)
  const desabilitada = bloqueada || salvando
  const motivo = textoDoMotivo(linha)
  const sinal = linha.tipo === 'receita' ? '+' : '−'
  const opcoes = travada ? ['ignorar'] : candidatoDisponivel ? ['importar', 'ignorar', 'conciliar'] : ['importar', 'ignorar']
  const categoriasDoTipo = categorias.filter((categoria) => categoria.tipo === linha.tipo)
  const idBase = `linha-${linha.id}`

  function mudarAcao(valor: string): void {
    if (valor === 'importar') aoSalvar({ acao: 'importar' })
    else if (valor === 'ignorar') aoSalvar({ acao: 'ignorar' })
    else if (valor === 'conciliar' && candidatoDisponivel) aoSalvar({ acao: 'conciliar' })
  }

  function salvarTitulo(): void {
    const limpo = titulo.trim()
    if (limpo === '' || limpo === linha.titulo) {
      setTitulo(linha.titulo)
      return
    }
    aoSalvar({ acao: 'importar', categoriaId: linha.categoriaId, titulo: limpo })
  }

  return (
    <li>
      <div className="item-corpo">
        <strong className="item-titulo">{linha.titulo}</strong>
        <span className="item-meta">
          {linha.data === null ? 'Sem data' : formatarDataCompleta(linha.data)}
          {linha.descricao !== null && linha.descricao !== '' ? `, ${linha.descricao}` : ''}
        </span>
        {motivo !== null && (
          <div className="item-selos">
            <Selo tom="neutro">{motivo}</Selo>
          </div>
        )}
        {!travada && <span className="item-meta">{textoDaProposta(linha)}</span>}
        {linha.acao === 'conciliar' && (
          <span className="item-meta">
            {candidato === undefined
              ? 'Concilia com um lançamento existente.'
              : `Concilia com: ${candidato.titulo}, ${formatarDataCompleta(candidato.dataCompetencia)}, ${formatarCentavos(candidato.valorCentavos)}`}
          </span>
        )}
        <div className="linha-importacao-campos">
          <div className="campo">
            <label htmlFor={`${idBase}-acao`}>{`Ação da linha ${linha.ordem}`}</label>
            <select
              id={`${idBase}-acao`}
              value={linha.acao}
              disabled={desabilitada || travada}
              onChange={(evento) => mudarAcao(evento.target.value)}
            >
              {opcoes.map((acao) => (
                <option key={acao} value={acao}>
                  {acao === 'importar' || acao === 'ignorar' || acao === 'conciliar' ? ROTULO_DA_ACAO[acao] : acao}
                </option>
              ))}
            </select>
          </div>
          {linha.acao === 'importar' && (
            <>
              <div className="campo">
                <label htmlFor={`${idBase}-titulo`}>{`Título da linha ${linha.ordem}`}</label>
                <input
                  id={`${idBase}-titulo`}
                  type="text"
                  maxLength={100}
                  value={titulo}
                  disabled={desabilitada}
                  onChange={(evento) => setTitulo(evento.target.value)}
                  onBlur={salvarTitulo}
                />
              </div>
              <div className="campo">
                <label htmlFor={`${idBase}-categoria`}>{`Categoria da linha ${linha.ordem}`}</label>
                <select
                  id={`${idBase}-categoria`}
                  value={linha.categoriaId ?? ''}
                  disabled={desabilitada}
                  onChange={(evento) =>
                    aoSalvar({ acao: 'importar', categoriaId: evento.target.value, titulo: linha.titulo })
                  }
                >
                  {categoriasDoTipo.map((categoria) => (
                    <option key={categoria.id} value={categoria.id}>
                      {categoria.nome}
                    </option>
                  ))}
                </select>
              </div>
            </>
          )}
        </div>
        {erro !== null && (
          <p role="alert" className="erro">
            {erro}
          </p>
        )}
      </div>
      <div className="item-lado">
        <span className={`item-valor ${linha.tipo ?? ''}`}>
          {linha.valorCentavos === null ? '—' : `${sinal} ${formatarCentavos(linha.valorCentavos)}`}
        </span>
      </div>
    </li>
  )
}

function contar(linhas: ImportacaoLinha[], acao: ImportacaoLinha['acao']): number {
  return linhas.filter((linha) => linha.valida && linha.acao === acao).length
}

export default function RevisaoDeImportacao({
  importacaoId,
  aviso,
  nomeDaConta,
  aoVoltar,
  aoMudarLancamentos,
  aoSessaoExpirar,
}: {
  importacaoId: string
  aviso: string | null
  nomeDaConta: (contaId: string) => string
  aoVoltar: () => void
  aoMudarLancamentos: () => void
  aoSessaoExpirar: () => void
}) {
  const [estado, setEstado] = useState<Estado<Dados>>({ tipo: 'carregando' })
  const [linhas, setLinhas] = useState<ImportacaoLinha[]>([])
  // Lançamento candidato de cada linha no momento em que a tela abriu: a API não lista outros.
  const [candidatosDasLinhas, setCandidatosDasLinhas] = useState<Record<string, string>>({})
  const [lancamentos, setLancamentos] = useState<Lancamento[]>([])
  const [falhaDosCandidatos, setFalhaDosCandidatos] = useState(false)
  const [salvando, setSalvando] = useState<string | null>(null)
  const [errosDasLinhas, setErrosDasLinhas] = useState<Record<string, string>>({})
  const [confirmando, setConfirmando] = useState(false)
  const [descartando, setDescartando] = useState(false)
  const [confirmandoDescarte, setConfirmandoDescarte] = useState(false)
  const [falha, setFalha] = useState<string | null>(null)
  const [resumo, setResumo] = useState<ImportacaoConfirmacao | null>(null)
  const emVoo = useRef(false)
  // Uma chave por tentativa de confirmar: um retry depois de falha de rede reusa a mesma (ADR-056).
  const chaveDaConfirmacao = useRef<string | null>(null)

  useEffect(() => {
    let vale = true
    carregar(
      async () => {
        const [detalhe, categorias] = await Promise.all([fetchImportacao(importacaoId), fetchCategorias()])
        return { detalhe, categorias }
      },
      'a importação',
      aoSessaoExpirar,
      (novo) => {
        setEstado(novo)
        if (novo.tipo !== 'ok') return
        setLinhas(novo.dados.detalhe.linhas)
        setCandidatosDasLinhas(
          Object.fromEntries(
            novo.dados.detalhe.linhas.flatMap((linha) => (linha.lancamentoId === null ? [] : [[linha.id, linha.lancamentoId]])),
          ),
        )
      },
      () => vale,
    )
    return () => {
      vale = false
    }
  }, [importacaoId, aoSessaoExpirar])

  const detalhe = estado.tipo === 'ok' ? estado.dados.detalhe : null
  const pendente = detalhe?.status === 'pendente'
  const idsDosCandidatos = Object.values(candidatosDasLinhas)
  const temCandidatos = pendente === true && idsDosCandidatos.length > 0

  useEffect(() => {
    if (!temCandidatos || detalhe === null) return
    const datas = detalhe.linhas.flatMap((linha) => (linha.data === null || linha.lancamentoId === null ? [] : [linha.data]))
    if (datas.length === 0) return
    const ordenadas = [...datas].sort()
    let vale = true
    // A conciliação aceita data a até 3 dias (ADR-069): a janela cobre todo candidato possível.
    fetchLancamentos(somarDias(ordenadas[0], -3), somarDias(ordenadas[ordenadas.length - 1], 3), detalhe.contaId).then(
      (itens) => {
        if (vale) setLancamentos(itens)
      },
      (erro: unknown) => {
        if (!vale) return
        if (erro instanceof SessaoExpiradaError) {
          aoSessaoExpirar()
          return
        }
        setFalhaDosCandidatos(true)
      },
    )
    return () => {
      vale = false
    }
  }, [temCandidatos, detalhe, aoSessaoExpirar])

  async function salvarLinha(linha: ImportacaoLinha, dados: ImportacaoLinhaRequest): Promise<void> {
    if (salvando !== null) return
    setSalvando(linha.id)
    setErrosDasLinhas(({ [linha.id]: _descartado, ...resto }) => resto)
    try {
      const nova = await putImportacaoLinha(importacaoId, linha.id, dados)
      setLinhas((atuais) => atuais.map((item) => (item.id === nova.id ? nova : item)))
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      const mensagem = mensagemDeImportacao(erro, 'Não foi possível salvar esta linha. Tente de novo.')
      setErrosDasLinhas((atuais) => ({ ...atuais, [linha.id]: mensagem }))
    } finally {
      setSalvando(null)
    }
  }

  async function confirmar(): Promise<void> {
    if (emVoo.current) return
    emVoo.current = true
    setConfirmando(true)
    setFalha(null)
    chaveDaConfirmacao.current ??= crypto.randomUUID()
    try {
      const resposta = await postConfirmacaoDeImportacao(importacaoId, chaveDaConfirmacao.current)
      setResumo(resposta)
      aoMudarLancamentos()
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      // Recusa do servidor desfez tudo: a próxima confirmação é outra intenção, com chave nova.
      if (erro instanceof ImportacaoRecusadaError) chaveDaConfirmacao.current = null
      setFalha(mensagemDeImportacao(erro, 'Não foi possível confirmar. Tente de novo.'))
    } finally {
      emVoo.current = false
      setConfirmando(false)
    }
  }

  async function descartar(): Promise<void> {
    if (emVoo.current) return
    emVoo.current = true
    setDescartando(true)
    setFalha(null)
    try {
      await deleteImportacao(importacaoId)
      aoVoltar()
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      setConfirmandoDescarte(false)
      setFalha(mensagemDeImportacao(erro, 'Não foi possível descartar. Tente de novo.'))
    } finally {
      emVoo.current = false
      setDescartando(false)
    }
  }

  const rotuloVoltar = (
    <button type="button" className="pequeno" onClick={aoVoltar}>
      Voltar às importações
    </button>
  )

  if (resumo !== null) {
    return (
      <section aria-labelledby="titulo-revisao">
        <header className="pagina-topo">
          <h2 id="titulo-revisao">Importação confirmada</h2>
        </header>
        <p role="status">
          {resumo.criadas} lançamentos criados, {resumo.conciliadas} conciliados, {resumo.ignoradas} ignorados
          {resumo.duplicadas > 0 ? `, ${resumo.duplicadas} já existiam` : ''}.
        </p>
        {rotuloVoltar}
      </section>
    )
  }

  const bloqueada = !pendente || confirmando || descartando
  const total = {
    criar: contar(linhas, 'importar'),
    conciliar: contar(linhas, 'conciliar'),
    ignorar: linhas.length - contar(linhas, 'importar') - contar(linhas, 'conciliar'),
  }

  return (
    <section aria-labelledby="titulo-revisao">
      <header className="pagina-topo">
        <h2 id="titulo-revisao">Revisar importação</h2>
        {rotuloVoltar}
      </header>
      {aviso !== null && (
        <p role="status" className="aviso-nota">
          {aviso}
        </p>
      )}
      {estado.tipo === 'carregando' && <Carregando texto="Carregando importação..." />}
      {estado.tipo === 'erro' && (
        <p role="alert" className="erro">
          {estado.mensagem}
        </p>
      )}
      {detalhe !== null && (
        <>
          <p className="item-meta">{`Conta: ${nomeDaConta(detalhe.contaId)}. Nada entra no extrato antes de você confirmar.`}</p>
          {detalhe.status === 'confirmada' && (
            <p role="status">Esta importação já foi confirmada. As linhas abaixo são só para consulta.</p>
          )}
          {falhaDosCandidatos && (
            <p role="alert" className="erro">
              Não foi possível carregar os lançamentos para conciliar. As linhas de conciliação seguem válidas.
            </p>
          )}
          <p role="status" aria-label="Resumo da importação">
            {total.criar} para criar, {total.conciliar} para conciliar, {total.ignorar} para ignorar
          </p>
          {linhas.length === 0 && <p>Nenhuma transação neste arquivo.</p>}
          {linhas.length > 0 && (
            <ul className="lancamentos lista-cartao" aria-label="Linhas do extrato">
              {linhas.map((linha) => (
                <LinhaDeRevisao
                  key={linha.id}
                  linha={linha}
                  categorias={estado.tipo === 'ok' ? estado.dados.categorias : []}
                  candidato={lancamentos.find((item) => item.id === (linha.lancamentoId ?? candidatosDasLinhas[linha.id]))}
                  candidatoDisponivel={candidatosDasLinhas[linha.id] !== undefined}
                  bloqueada={bloqueada}
                  salvando={salvando !== null}
                  erro={errosDasLinhas[linha.id] ?? null}
                  aoSalvar={(dados) =>
                    void salvarLinha(
                      linha,
                      dados.acao === 'conciliar' ? { ...dados, lancamentoId: candidatosDasLinhas[linha.id] } : dados,
                    )
                  }
                />
              ))}
            </ul>
          )}
          {falha !== null && (
            <p role="alert" className="erro">
              {falha}
            </p>
          )}
          {pendente && (
            <div className="item-botoes">
              <button
                type="button"
                className="primario"
                disabled={confirmando || descartando || salvando !== null}
                onClick={() => void confirmar()}
              >
                {confirmando ? 'Confirmando...' : 'Confirmar importação'}
              </button>
              {confirmandoDescarte ? (
                <>
                  <button
                    type="button"
                    className="perigo"
                    disabled={descartando || confirmando}
                    onClick={() => void descartar()}
                  >
                    Confirmar descarte
                  </button>
                  <button type="button" onClick={() => setConfirmandoDescarte(false)}>
                    Manter
                  </button>
                </>
              ) : (
                <button type="button" className="perigo" disabled={confirmando} onClick={() => setConfirmandoDescarte(true)}>
                  Descartar importação
                </button>
              )}
            </div>
          )}
        </>
      )}
    </section>
  )
}
