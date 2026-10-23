import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { SessaoExpiradaError, deleteAporte, deleteMeta, fetchAportes, fetchMetas, postAporte, postMeta, putMeta } from './api'
import type { AporteMeta, Meta, MetaRequest } from './api'
import { formatarDataCompleta, hojeLocal, lerMesIso, rotuloDoMes } from './datas'
import { centavosParaCampo, formatarCentavos, lerValorPositivo } from './dinheiro'
import { IconeMais } from './icones'
import { carregar, mensagemDeEscrita } from './leitura'
import type { Estado } from './leitura'
import MenuDeAcoes from './MenuDeAcoes'
import Painel from './Painel'
import AtalhosDePlanejamento from './Planejamento'
import { Carregando, EstadoVazio, Selo } from './ui'

// Só para a largura da barra: percentual inteiro do alvo já guardado, em BigInt, até 100.
function percentualGuardado(alvoCentavos: number, acumuladoCentavos: number): number {
  const alvo = BigInt(alvoCentavos)
  const acumulado = BigInt(acumuladoCentavos)
  if (alvo <= 0n || acumulado <= 0n) return 0
  if (acumulado >= alvo) return 100
  return Number((acumulado * 100n) / alvo)
}

function mesPorExtenso(mes: string): string {
  return rotuloDoMes(lerMesIso(mes))
}

// Campo opcional: vazio vira null; com texto, só valor positivo passa.
function lerOpcional(texto: string): number | null | 'invalido' {
  if (texto.trim() === '') return null
  return lerValorPositivo(texto) ?? 'invalido'
}

function FormularioDeMeta({
  inicial,
  aoSalvar,
  aoSessaoExpirar,
}: {
  inicial: Meta | null
  aoSalvar: () => void
  aoSessaoExpirar: () => void
}) {
  const [titulo, setTitulo] = useState(inicial?.titulo ?? '')
  const [alvo, setAlvo] = useState(inicial === null ? '' : centavosParaCampo(inicial.valorAlvoCentavos))
  const [dataAlvo, setDataAlvo] = useState(inicial?.dataAlvo ?? '')
  const [aporte, setAporte] = useState(
    inicial?.aporteMensalCentavos == null ? '' : centavosParaCampo(inicial.aporteMensalCentavos),
  )
  const [enviando, setEnviando] = useState(false)
  const [mensagem, setMensagem] = useState<string | null>(null)
  // O state só atualiza no próximo render; o ref barra o segundo submit síncrono.
  const emVoo = useRef(false)

  async function enviar(evento: FormEvent<HTMLFormElement>): Promise<void> {
    evento.preventDefault()
    if (emVoo.current) return
    const alvoCentavos = lerValorPositivo(alvo)
    const aporteCentavos = lerOpcional(aporte)
    if (alvoCentavos === null || aporteCentavos === 'invalido') {
      setMensagem('Valor inválido. Use o formato 1.234,56.')
      return
    }
    const dados: MetaRequest = {
      titulo,
      valorAlvoCentavos: alvoCentavos,
      dataAlvo: dataAlvo === '' ? null : dataAlvo,
      aporteMensalCentavos: aporteCentavos,
    }
    emVoo.current = true
    setEnviando(true)
    setMensagem(null)
    try {
      if (inicial === null) await postMeta(dados)
      else await putMeta(inicial.id, dados)
      aoSalvar()
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      setMensagem(mensagemDeEscrita(erro, 'Não foi possível salvar. Tente de novo.'))
    } finally {
      emVoo.current = false
      setEnviando(false)
    }
  }

  return (
    <form onSubmit={(evento) => void enviar(evento)} aria-label="Meta">
      <div className="campo">
        <label htmlFor="meta-titulo">Título</label>
        <input
          id="meta-titulo"
          type="text"
          maxLength={100}
          required
          value={titulo}
          onChange={(evento) => setTitulo(evento.target.value)}
        />
      </div>
      <div className="campo">
        <label htmlFor="meta-alvo">Valor alvo</label>
        <input
          id="meta-alvo"
          type="text"
          inputMode="decimal"
          autoComplete="off"
          placeholder="0,00"
          required
          value={alvo}
          onChange={(evento) => setAlvo(evento.target.value)}
        />
      </div>
      <div className="campo">
        <label htmlFor="meta-data">Data alvo (opcional)</label>
        <input
          id="meta-data"
          type="date"
          min="1900-01-01"
          max="2199-12-31"
          value={dataAlvo}
          onChange={(evento) => setDataAlvo(evento.target.value)}
        />
      </div>
      <div className="campo">
        <label htmlFor="meta-aporte">Aporte por mês (opcional)</label>
        <input
          id="meta-aporte"
          type="text"
          inputMode="decimal"
          autoComplete="off"
          placeholder="0,00"
          value={aporte}
          onChange={(evento) => setAporte(evento.target.value)}
        />
      </div>
      <p role="status">O aporte por mês é o seu plano: nada é lançado sozinho.</p>
      {mensagem !== null && (
        <p role="alert" className="erro">
          {mensagem}
        </p>
      )}
      <button type="submit" className="primario" disabled={enviando}>
        {enviando ? 'Salvando...' : 'Salvar meta'}
      </button>
    </form>
  )
}

function FormularioDeAporte({
  meta,
  aoSalvar,
  aoSessaoExpirar,
}: {
  meta: Meta
  aoSalvar: () => void
  aoSessaoExpirar: () => void
}) {
  const [sentido, setSentido] = useState<'guardar' | 'retirar'>('guardar')
  const [valor, setValor] = useState(meta.aporteMensalCentavos === null ? '' : centavosParaCampo(meta.aporteMensalCentavos))
  const [data, setData] = useState(hojeLocal())
  const [enviando, setEnviando] = useState(false)
  const [mensagem, setMensagem] = useState<string | null>(null)
  const emVoo = useRef(false)

  async function enviar(evento: FormEvent<HTMLFormElement>): Promise<void> {
    evento.preventDefault()
    if (emVoo.current) return
    const centavos = lerValorPositivo(valor)
    if (centavos === null) {
      setMensagem('Valor inválido. Use o formato 1.234,56.')
      return
    }
    emVoo.current = true
    setEnviando(true)
    setMensagem(null)
    try {
      await postAporte(meta.id, sentido === 'guardar' ? centavos : -centavos, data)
      aoSalvar()
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      setMensagem(mensagemDeEscrita(erro, 'Não foi possível registrar. Tente de novo.'))
    } finally {
      emVoo.current = false
      setEnviando(false)
    }
  }

  return (
    <form onSubmit={(evento) => void enviar(evento)} aria-label="Aporte">
      <fieldset className="campo">
        <legend>Movimento</legend>
        <label>
          <input type="radio" name="aporte-sentido" checked={sentido === 'guardar'} onChange={() => setSentido('guardar')} />
          Guardar
        </label>
        <label>
          <input type="radio" name="aporte-sentido" checked={sentido === 'retirar'} onChange={() => setSentido('retirar')} />
          Retirar
        </label>
      </fieldset>
      <div className="campo">
        <label htmlFor="aporte-valor">Valor</label>
        <input
          id="aporte-valor"
          type="text"
          inputMode="decimal"
          autoComplete="off"
          placeholder="0,00"
          required
          value={valor}
          onChange={(evento) => setValor(evento.target.value)}
        />
      </div>
      <div className="campo">
        <label htmlFor="aporte-data">Data</label>
        <input
          id="aporte-data"
          type="date"
          min="1900-01-01"
          max={hojeLocal()}
          required
          value={data}
          onChange={(evento) => setData(evento.target.value)}
        />
      </div>
      {mensagem !== null && (
        <p role="alert" className="erro">
          {mensagem}
        </p>
      )}
      <button type="submit" className="primario" disabled={enviando}>
        {enviando ? 'Salvando...' : 'Registrar'}
      </button>
    </form>
  )
}

function HistoricoDeAportes({
  meta,
  aoMudar,
  aoSessaoExpirar,
}: {
  meta: Meta
  aoMudar: () => void
  aoSessaoExpirar: () => void
}) {
  const [estado, setEstado] = useState<Estado<AporteMeta[]>>({ tipo: 'carregando' })
  const [versao, setVersao] = useState(0)
  const [confirmando, setConfirmando] = useState<string | null>(null)
  const [falha, setFalha] = useState<string | null>(null)

  useEffect(() => {
    let vale = true
    carregar(() => fetchAportes(meta.id), 'os aportes', aoSessaoExpirar, setEstado, () => vale)
    return () => {
      vale = false
    }
  }, [meta.id, versao, aoSessaoExpirar])

  async function apagar(aporte: AporteMeta): Promise<void> {
    setFalha(null)
    try {
      await deleteAporte(meta.id, aporte.id)
      setConfirmando(null)
      setVersao((n) => n + 1)
      aoMudar()
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      setFalha(mensagemDeEscrita(erro, 'Não foi possível apagar. Tente de novo.'))
    }
  }

  if (estado.tipo === 'carregando') return <Carregando texto="Carregando aportes..." />
  if (estado.tipo === 'erro') {
    return (
      <p role="alert" className="erro">
        {estado.mensagem}
      </p>
    )
  }
  if (estado.dados.length === 0) return <EstadoVazio titulo="Nenhum aporte ainda." />
  return (
    <>
      {falha !== null && (
        <p role="alert" className="erro">
          {falha}
        </p>
      )}
      <ul className="lancamentos" aria-label={`Aportes de ${meta.titulo}`}>
        {estado.dados.map((aporte) => {
          const dia = formatarDataCompleta(aporte.data)
          return (
            <li key={aporte.id}>
              <div className="item-corpo">
                <span className="item-titulo">{aporte.valorCentavos < 0 ? 'Retirada' : 'Aporte'}</span>
                <span className="item-meta">{dia}</span>
              </div>
              <div className="item-lado">
                <span className={`item-valor ${aporte.valorCentavos < 0 ? 'despesa' : 'receita'}`}>
                  {aporte.valorCentavos < 0 ? '−' : '+'} {formatarCentavos(Math.abs(aporte.valorCentavos))}
                </span>
                {confirmando === aporte.id ? (
                  <button
                    type="button"
                    className="pequeno perigo"
                    aria-label={`Confirmar apagar aporte de ${dia}`}
                    onClick={() => void apagar(aporte)}
                  >
                    Confirmar
                  </button>
                ) : (
                  <button
                    type="button"
                    className="pequeno"
                    aria-label={`Apagar aporte de ${dia}`}
                    onClick={() => setConfirmando(aporte.id)}
                  >
                    Apagar
                  </button>
                )}
              </div>
            </li>
          )
        })}
      </ul>
    </>
  )
}

function Projecao({ meta }: { meta: Meta }) {
  if (meta.atingida) return null
  return (
    <>
      {meta.necessarioPorMesCentavos !== null && meta.dataAlvo !== null && (
        <small>
          Para chegar em {formatarDataCompleta(meta.dataAlvo)}: {formatarCentavos(meta.necessarioPorMesCentavos)} por mês
        </small>
      )}
      {meta.aporteMensalCentavos !== null && meta.chegadaPeloPlano !== null && (
        <small>
          Guardando {formatarCentavos(meta.aporteMensalCentavos)} por mês, chega em {mesPorExtenso(meta.chegadaPeloPlano)}
        </small>
      )}
      {meta.chegadaPeloRitmo !== null ? (
        <small>
          No ritmo dos últimos 3 meses ({formatarCentavos(meta.ritmoMensalCentavos)} por mês), chega em{' '}
          {mesPorExtenso(meta.chegadaPeloRitmo)}
        </small>
      ) : (
        <small>Sem aporte nos últimos 3 meses</small>
      )}
    </>
  )
}

type Abertura =
  | { tipo: 'nova' }
  | { tipo: 'editar'; meta: Meta }
  | { tipo: 'aportar'; meta: Meta }
  | { tipo: 'aportes'; meta: Meta }

// Metas com aportes registrados à mão (ADR-045). A projeção vem pronta da API.
export default function Metas({ aoSessaoExpirar }: { aoSessaoExpirar: () => void }) {
  const [estado, setEstado] = useState<Estado<Meta[]>>({ tipo: 'carregando' })
  const [versao, setVersao] = useState(0)
  const [abertura, setAbertura] = useState<Abertura | null>(null)
  const [confirmando, setConfirmando] = useState<string | null>(null)
  const [falha, setFalha] = useState<string | null>(null)

  useEffect(() => {
    let vale = true
    carregar(fetchMetas, 'as metas', aoSessaoExpirar, setEstado, () => vale)
    return () => {
      vale = false
    }
  }, [versao, aoSessaoExpirar])

  function recarregar(): void {
    setVersao((n) => n + 1)
  }

  async function apagar(meta: Meta): Promise<void> {
    setFalha(null)
    try {
      await deleteMeta(meta.id)
      setConfirmando(null)
      recarregar()
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      setFalha(mensagemDeEscrita(erro, 'Não foi possível apagar. Tente de novo.'))
    }
  }

  return (
    <section aria-labelledby="titulo-metas">
      <header className="pagina-topo">
        <h2 id="titulo-metas">Metas</h2>
        <button type="button" className="primario botao-novo" onClick={() => setAbertura({ tipo: 'nova' })}>
          <IconeMais />
          Nova meta
        </button>
      </header>
      <AtalhosDePlanejamento atual="metas" />
      {estado.tipo === 'carregando' && <Carregando texto="Carregando metas..." />}
      {estado.tipo === 'erro' && (
        <p role="alert" className="erro">
          {estado.mensagem}
        </p>
      )}
      {falha !== null && (
        <p role="alert" className="erro">
          {falha}
        </p>
      )}
      {estado.tipo === 'ok' && estado.dados.length === 0 && (
        <EstadoVazio
          titulo="Nenhuma meta ainda."
          dica="Uma meta tem valor alvo e, se quiser, data e quanto pretende guardar por mês."
        />
      )}
      {estado.tipo === 'ok' && estado.dados.length > 0 && (
        <ul className="lancamentos lista-cartao" aria-label="Metas">
          {estado.dados.map((meta) => {
            const guardado = percentualGuardado(meta.valorAlvoCentavos, meta.acumuladoCentavos)
            return (
              <li key={meta.id}>
                <div className="item-corpo">
                  <strong className="item-titulo">{meta.titulo}</strong>
                  <small>
                    {formatarCentavos(meta.acumuladoCentavos)} de {formatarCentavos(meta.valorAlvoCentavos)}
                  </small>
                  <div className={meta.atingida ? 'barra' : 'barra atencao'} aria-hidden="true">
                    <div style={{ width: `${guardado}%` }} />
                  </div>
                  {!meta.atingida && <small>Faltam {formatarCentavos(meta.faltaCentavos)}</small>}
                  <Projecao meta={meta} />
                  {(meta.atingida || meta.prazoVencido) && (
                    <div className="item-selos">
                      {meta.atingida && <Selo tom="paga">Atingida</Selo>}
                      {meta.prazoVencido && <Selo tom="vencida">Prazo vencido</Selo>}
                    </div>
                  )}
                </div>
                <div className="item-lado">
                  {confirmando === meta.id ? (
                    <div className="item-botoes">
                      <button
                        type="button"
                        className="pequeno perigo"
                        aria-label={`Confirmar apagar ${meta.titulo} e os aportes dela`}
                        onClick={() => void apagar(meta)}
                      >
                        Apagar
                      </button>
                      <button type="button" className="pequeno" onClick={() => setConfirmando(null)}>
                        Manter
                      </button>
                    </div>
                  ) : (
                    <div className="item-botoes">
                      <button
                        type="button"
                        className="pequeno"
                        aria-label={`Aportar em ${meta.titulo}`}
                        onClick={() => setAbertura({ tipo: 'aportar', meta })}
                      >
                        Aportar
                      </button>
                      <MenuDeAcoes rotulo={`Mais ações de ${meta.titulo}`}>
                        <button
                          type="button"
                          aria-label={`Ver aportes de ${meta.titulo}`}
                          onClick={() => setAbertura({ tipo: 'aportes', meta })}
                        >
                          Ver aportes
                        </button>
                        <button
                          type="button"
                          aria-label={`Editar ${meta.titulo}`}
                          onClick={() => setAbertura({ tipo: 'editar', meta })}
                        >
                          Editar
                        </button>
                        <button
                          type="button"
                          className="perigo"
                          aria-label={`Apagar ${meta.titulo}`}
                          onClick={() => {
                            setFalha(null)
                            setConfirmando(meta.id)
                          }}
                        >
                          Apagar
                        </button>
                      </MenuDeAcoes>
                    </div>
                  )}
                </div>
              </li>
            )
          })}
        </ul>
      )}
      {abertura !== null && (
        <Painel
          titulo={
            abertura.tipo === 'nova'
              ? 'Nova meta'
              : abertura.tipo === 'editar'
                ? `Editar ${abertura.meta.titulo}`
                : abertura.tipo === 'aportar'
                  ? `Aporte em ${abertura.meta.titulo}`
                  : `Aportes de ${abertura.meta.titulo}`
          }
          aoFechar={() => setAbertura(null)}
        >
          {(abertura.tipo === 'nova' || abertura.tipo === 'editar') && (
            <FormularioDeMeta
              inicial={abertura.tipo === 'editar' ? abertura.meta : null}
              aoSalvar={() => {
                setAbertura(null)
                recarregar()
              }}
              aoSessaoExpirar={aoSessaoExpirar}
            />
          )}
          {abertura.tipo === 'aportar' && (
            <FormularioDeAporte
              meta={abertura.meta}
              aoSalvar={() => {
                setAbertura(null)
                recarregar()
              }}
              aoSessaoExpirar={aoSessaoExpirar}
            />
          )}
          {abertura.tipo === 'aportes' && (
            <HistoricoDeAportes meta={abertura.meta} aoMudar={recarregar} aoSessaoExpirar={aoSessaoExpirar} />
          )}
        </Painel>
      )}
    </section>
  )
}
