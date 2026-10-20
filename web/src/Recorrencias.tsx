import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import {
  RecorrenciaInvalidaError,
  RecorrenciaNaoEncontradaError,
  ServicoIndisponivelError,
  SessaoExpiradaError,
  desativarRecorrencia,
  fetchCategorias,
  fetchRecorrencias,
  postRecorrencia,
} from './api'
import type { Categoria, Conta, Recorrencia, RegraRecorrencia } from './api'
import { formatarDataCompleta, hojeLocal } from './datas'
import { formatarCentavos, lerValorPositivo } from './dinheiro'
import { descreverRegra } from './regra'

type Tipo = Recorrencia['tipo']
type Frequencia = RegraRecorrencia['frequencia']
type Termino = 'nenhum' | 'data' | 'vezes'

const TIPOS: Tipo[] = ['receita', 'despesa']
const ROTULO_DO_TIPO: Record<Tipo, string> = { receita: 'Receita', despesa: 'Despesa' }

const FREQUENCIAS: Frequencia[] = ['diaria', 'semanal', 'mensal', 'anual']
const ROTULO_DA_FREQUENCIA: Record<Frequencia, string> = {
  diaria: 'Diária',
  semanal: 'Semanal',
  mensal: 'Mensal',
  anual: 'Anual',
}

const DIAS_DA_SEMANA = ['Domingo', 'Segunda-feira', 'Terça-feira', 'Quarta-feira', 'Quinta-feira', 'Sexta-feira', 'Sábado']

const MENSAGEM_INDISPONIVEL = 'Serviço indisponível. Tente de novo em instantes.'
const MENSAGEM_NAO_EXISTE = 'Essa recorrência não existe mais.'
const MENSAGEM_VALOR_INVALIDO =
  'Valor inválido. Use o formato 1.234,56, maior que zero e até 90.071.992.547.409,91.'

// Mesmos limites que a API recusa; validar aqui poupa a ida e volta.
const ANO_MINIMO = 1900
const ANO_MAXIMO = 2199
const MAXIMO_DE_VEZES = 10000

type EstadoLista =
  | { tipo: 'carregando' }
  | { tipo: 'erro'; mensagem: string }
  | { tipo: 'ok'; recorrencias: Recorrencia[] }

type EstadoCategorias =
  | { tipo: 'carregando' }
  | { tipo: 'erro'; mensagem: string }
  | { tipo: 'ok'; categorias: Categoria[] }

function mensagemDeLeitura(erro: unknown, alvo: string): string {
  if (erro instanceof ServicoIndisponivelError) return MENSAGEM_INDISPONIVEL
  return `Não foi possível carregar ${alvo}.`
}

function mensagemDeCriacao(erro: unknown): string {
  if (erro instanceof RecorrenciaInvalidaError && erro.mensagemServidor !== null) {
    return erro.mensagemServidor
  }
  if (erro instanceof ServicoIndisponivelError) return MENSAGEM_INDISPONIVEL
  return 'Não foi possível salvar a recorrência. Tente de novo.'
}

function mensagemDeDesativacao(erro: unknown): string {
  if (erro instanceof RecorrenciaNaoEncontradaError) return MENSAGEM_NAO_EXISTE
  if (erro instanceof RecorrenciaInvalidaError && erro.mensagemServidor !== null) {
    return erro.mensagemServidor
  }
  if (erro instanceof ServicoIndisponivelError) return MENSAGEM_INDISPONIVEL
  return 'Não foi possível desativar a recorrência. Tente de novo.'
}

// Texto digitado só vale se for inteiro puro na faixa; o type=number do navegador aceita "1e2".
function lerInteiro(texto: string, minimo: number, maximo: number): number | null {
  if (!/^\d+$/.test(texto.trim())) return null
  const numero = Number(texto)
  return numero >= minimo && numero <= maximo ? numero : null
}

function dataNaFaixa(data: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(data)) return false
  const ano = Number(data.slice(0, 4))
  return ano >= ANO_MINIMO && ano <= ANO_MAXIMO
}

function FormularioDeRecorrencia({
  contas,
  categorias,
  aoSalvar,
  aoSessaoExpirar,
}: {
  contas: Conta[]
  categorias: Categoria[]
  aoSalvar: () => void
  aoSessaoExpirar: () => void
}) {
  const [tipo, setTipo] = useState<Tipo>('despesa')
  const [titulo, setTitulo] = useState('')
  const [valor, setValor] = useState('')
  const [categoriaEscolhida, setCategoriaEscolhida] = useState('')
  const [contaEscolhida, setContaEscolhida] = useState('')
  const [descricao, setDescricao] = useState('')
  const [frequencia, setFrequencia] = useState<Frequencia>('mensal')
  const [intervalo, setIntervalo] = useState('1')
  const [diaDoMes, setDiaDoMes] = useState(() => String(Number(hojeLocal().slice(8, 10))))
  const [diaDaSemana, setDiaDaSemana] = useState(() => String(new Date().getDay()))
  const [inicio, setInicio] = useState(hojeLocal())
  const [termino, setTermino] = useState<Termino>('nenhum')
  const [dataFinal, setDataFinal] = useState('')
  const [vezes, setVezes] = useState('')
  const [enviando, setEnviando] = useState(false)
  const [mensagem, setMensagem] = useState<string | null>(null)
  // O state só atualiza no próximo render; o ref barra o segundo submit síncrono.
  const emVoo = useRef(false)

  const categoriasDoTipo = categorias.filter((categoria) => categoria.tipo === tipo)
  // Ao trocar o tipo a escolha antiga some da lista; cai na primeira em vez de guardar id inválido.
  const categoriaId = categoriasDoTipo.some((c) => c.id === categoriaEscolhida)
    ? categoriaEscolhida
    : (categoriasDoTipo[0]?.id ?? '')
  const contaId = contas.some((c) => c.id === contaEscolhida) ? contaEscolhida : (contas[0]?.id ?? '')

  async function enviar(evento: FormEvent<HTMLFormElement>): Promise<void> {
    evento.preventDefault()
    if (emVoo.current) return
    const valorCentavos = lerValorPositivo(valor)
    if (valorCentavos === null) {
      setMensagem(MENSAGEM_VALOR_INVALIDO)
      return
    }
    const intervaloLido = lerInteiro(intervalo, 1, 99)
    if (intervaloLido === null) {
      setMensagem('Intervalo inválido. Use um número inteiro de 1 a 99.')
      return
    }
    const diaDoMesLido = lerInteiro(diaDoMes, 1, 31)
    if (frequencia === 'mensal' && diaDoMesLido === null) {
      setMensagem('Dia do mês inválido. Use um número inteiro de 1 a 31.')
      return
    }
    if (!dataNaFaixa(inicio)) {
      setMensagem(`Início inválido. Use uma data de ${ANO_MINIMO} a ${ANO_MAXIMO}.`)
      return
    }
    const vezesLido = lerInteiro(vezes, 1, MAXIMO_DE_VEZES)
    if (termino === 'data' && (!dataNaFaixa(dataFinal) || dataFinal < inicio)) {
      setMensagem('Data final inválida. Use uma data igual ou posterior ao início.')
      return
    }
    if (termino === 'vezes' && vezesLido === null) {
      setMensagem(`Número de vezes inválido. Use um inteiro de 1 a ${MAXIMO_DE_VEZES}.`)
      return
    }
    emVoo.current = true
    setEnviando(true)
    setMensagem(null)
    // Só os campos da frequência escolhida e um único fim: a API recusa o resto com 400.
    const regra = {
      frequencia,
      intervalo: intervaloLido,
      inicio,
      ...(frequencia === 'mensal' && diaDoMesLido !== null ? { diaDoMes: diaDoMesLido } : {}),
      ...(frequencia === 'semanal' ? { diaDaSemana: Number(diaDaSemana) } : {}),
      ...(termino === 'data' ? { fim: dataFinal } : {}),
      ...(termino === 'vezes' && vezesLido !== null ? { ocorrenciasMax: vezesLido } : {}),
    }
    try {
      await postRecorrencia({
        tipo,
        titulo: titulo.trim(),
        ...(descricao.trim() === '' ? {} : { descricao: descricao.trim() }),
        valorCentavos,
        categoriaId,
        contaId,
        regra,
      })
      aoSalvar()
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      setMensagem(mensagemDeCriacao(erro))
    } finally {
      emVoo.current = false
      setEnviando(false)
    }
  }

  const descricaoDoErro = mensagem === null ? undefined : 'erro-recorrencia'

  return (
    <form onSubmit={(evento) => void enviar(evento)} aria-label="Nova recorrência">
      <h3>Nova recorrência</h3>
      <div className="campo">
        <label htmlFor="recorrencia-tipo">Tipo</label>
        <select
          id="recorrencia-tipo"
          value={tipo}
          onChange={(evento) => {
            const escolhido = TIPOS.find((opcao) => opcao === evento.target.value)
            if (escolhido !== undefined) setTipo(escolhido)
          }}
        >
          {TIPOS.map((opcao) => (
            <option key={opcao} value={opcao}>
              {ROTULO_DO_TIPO[opcao]}
            </option>
          ))}
        </select>
      </div>
      <div className="campo">
        <label htmlFor="recorrencia-titulo">Título</label>
        <input
          id="recorrencia-titulo"
          type="text"
          maxLength={100}
          required
          value={titulo}
          aria-describedby={descricaoDoErro}
          onChange={(evento) => setTitulo(evento.target.value)}
        />
      </div>
      <div className="campo">
        <label htmlFor="recorrencia-valor">Valor em reais</label>
        <input
          id="recorrencia-valor"
          type="text"
          inputMode="decimal"
          autoComplete="off"
          placeholder="0,00"
          required
          value={valor}
          aria-describedby={descricaoDoErro}
          onChange={(evento) => setValor(evento.target.value)}
        />
      </div>
      <div className="campo">
        <label htmlFor="recorrencia-categoria">Categoria</label>
        <select
          id="recorrencia-categoria"
          value={categoriaId}
          required
          onChange={(evento) => setCategoriaEscolhida(evento.target.value)}
        >
          {categoriasDoTipo.map((categoria) => (
            <option key={categoria.id} value={categoria.id}>
              {categoria.nome}
            </option>
          ))}
        </select>
      </div>
      <div className="campo">
        <label htmlFor="recorrencia-conta">Conta</label>
        <select
          id="recorrencia-conta"
          value={contaId}
          required
          onChange={(evento) => setContaEscolhida(evento.target.value)}
        >
          {contas.map((conta) => (
            <option key={conta.id} value={conta.id}>
              {conta.nome}
            </option>
          ))}
        </select>
      </div>
      <div className="campo">
        <label htmlFor="recorrencia-descricao">Descrição (opcional)</label>
        <input
          id="recorrencia-descricao"
          type="text"
          maxLength={500}
          value={descricao}
          onChange={(evento) => setDescricao(evento.target.value)}
        />
      </div>
      <div className="campo">
        <label htmlFor="recorrencia-frequencia">Frequência</label>
        <select
          id="recorrencia-frequencia"
          value={frequencia}
          onChange={(evento) => {
            const escolhida = FREQUENCIAS.find((opcao) => opcao === evento.target.value)
            if (escolhida !== undefined) setFrequencia(escolhida)
          }}
        >
          {FREQUENCIAS.map((opcao) => (
            <option key={opcao} value={opcao}>
              {ROTULO_DA_FREQUENCIA[opcao]}
            </option>
          ))}
        </select>
      </div>
      <div className="campo">
        <label htmlFor="recorrencia-intervalo">Intervalo</label>
        <input
          id="recorrencia-intervalo"
          type="number"
          inputMode="numeric"
          min={1}
          max={99}
          step={1}
          required
          value={intervalo}
          aria-describedby="ajuda-intervalo"
          onChange={(evento) => setIntervalo(evento.target.value)}
        />
        <small id="ajuda-intervalo">Repete a cada tantos períodos, de 1 a 99.</small>
      </div>
      {frequencia === 'mensal' && (
        <div className="campo">
          <label htmlFor="recorrencia-dia-do-mes">Dia do mês</label>
          <input
            id="recorrencia-dia-do-mes"
            type="number"
            inputMode="numeric"
            min={1}
            max={31}
            step={1}
            required
            value={diaDoMes}
            aria-describedby="ajuda-dia-do-mes"
            onChange={(evento) => setDiaDoMes(evento.target.value)}
          />
          <small id="ajuda-dia-do-mes">Meses curtos usam o último dia.</small>
        </div>
      )}
      {frequencia === 'semanal' && (
        <div className="campo">
          <label htmlFor="recorrencia-dia-da-semana">Dia da semana</label>
          <select
            id="recorrencia-dia-da-semana"
            value={diaDaSemana}
            onChange={(evento) => setDiaDaSemana(evento.target.value)}
          >
            {DIAS_DA_SEMANA.map((nome, indice) => (
              <option key={nome} value={String(indice)}>
                {nome}
              </option>
            ))}
          </select>
        </div>
      )}
      <div className="campo">
        <label htmlFor="recorrencia-inicio">Início</label>
        <input
          id="recorrencia-inicio"
          type="date"
          required
          min="1900-01-01"
          max="2199-12-31"
          value={inicio}
          onChange={(evento) => setInicio(evento.target.value)}
        />
      </div>
      <div className="campo">
        <label htmlFor="recorrencia-termino">Término</label>
        <select
          id="recorrencia-termino"
          value={termino}
          onChange={(evento) => {
            const escolhido = (['nenhum', 'data', 'vezes'] as const).find((opcao) => opcao === evento.target.value)
            if (escolhido !== undefined) setTermino(escolhido)
          }}
        >
          <option value="nenhum">Sem fim</option>
          <option value="data">Até a data</option>
          <option value="vezes">Número de vezes</option>
        </select>
      </div>
      {termino === 'data' && (
        <div className="campo">
          <label htmlFor="recorrencia-data-final">Data final</label>
          <input
            id="recorrencia-data-final"
            type="date"
            required
            min={inicio}
            max="2199-12-31"
            value={dataFinal}
            onChange={(evento) => setDataFinal(evento.target.value)}
          />
        </div>
      )}
      {termino === 'vezes' && (
        <div className="campo">
          <label htmlFor="recorrencia-vezes">Número de vezes</label>
          <input
            id="recorrencia-vezes"
            type="number"
            inputMode="numeric"
            min={1}
            max={MAXIMO_DE_VEZES}
            step={1}
            required
            value={vezes}
            onChange={(evento) => setVezes(evento.target.value)}
          />
        </div>
      )}
      {mensagem !== null && (
        <p id="erro-recorrencia" role="alert" className="erro">
          {mensagem}
        </p>
      )}
      <button type="submit" disabled={enviando}>
        {enviando ? 'Salvando...' : 'Adicionar recorrência'}
      </button>
    </form>
  )
}

export default function Recorrencias({
  aoSessaoExpirar,
  contas,
  versao,
  aoCarregarAtivas,
  aoMudarOcorrencias,
}: {
  aoSessaoExpirar: () => void
  // Contas ativas, vindas da tela de Contas; null até a primeira carga.
  contas: Conta[] | null
  // Sobe quando a lista de lançamentos mexeu nas ocorrências (muda a próxima data).
  versao: number
  // Entrega os ids das recorrências ativas para a lista do mês avisar que apagar faz voltar.
  aoCarregarAtivas: (ids: ReadonlySet<string>) => void
  // Criar e desativar mudam as previstas do mês e, ao confirmar, o saldo.
  aoMudarOcorrencias: () => void
}) {
  const [estado, setEstado] = useState<EstadoLista>({ tipo: 'carregando' })
  const [apoio, setApoio] = useState<EstadoCategorias>({ tipo: 'carregando' })
  const [tentativa, setTentativa] = useState(0)
  const [tentativaDoApoio, setTentativaDoApoio] = useState(0)
  const [confirmando, setConfirmando] = useState<string | null>(null)
  const [desativando, setDesativando] = useState<string | null>(null)
  const [falhaDeAcao, setFalhaDeAcao] = useState<string | null>(null)
  // Muda a key do formulário para limpar os campos depois de salvar.
  const [versaoDoFormulario, setVersaoDoFormulario] = useState(0)
  const acaoEmVoo = useRef(false)

  useEffect(() => {
    // Descarta a resposta antiga se outra busca já começou.
    let ativo = true
    fetchRecorrencias().then(
      (recorrencias) => {
        if (!ativo) return
        setEstado({ tipo: 'ok', recorrencias })
        aoCarregarAtivas(new Set(recorrencias.filter((r) => r.ativo).map((r) => r.id)))
      },
      (erro: unknown) => {
        if (!ativo) return
        if (erro instanceof SessaoExpiradaError) {
          aoSessaoExpirar()
          return
        }
        setEstado({ tipo: 'erro', mensagem: mensagemDeLeitura(erro, 'as recorrências') })
      },
    )
    return () => {
      ativo = false
    }
  }, [tentativa, versao, aoSessaoExpirar, aoCarregarAtivas])

  useEffect(() => {
    let ativo = true
    fetchCategorias().then(
      (categorias) => {
        if (ativo) setApoio({ tipo: 'ok', categorias })
      },
      (erro: unknown) => {
        if (!ativo) return
        if (erro instanceof SessaoExpiradaError) {
          aoSessaoExpirar()
          return
        }
        setApoio({ tipo: 'erro', mensagem: mensagemDeLeitura(erro, 'as categorias') })
      },
    )
    return () => {
      ativo = false
    }
  }, [tentativaDoApoio, aoSessaoExpirar])

  function recarregar(): void {
    setEstado({ tipo: 'carregando' })
    setTentativa((n) => n + 1)
  }

  async function desativar(recorrencia: Recorrencia): Promise<void> {
    if (acaoEmVoo.current) return
    acaoEmVoo.current = true
    setDesativando(recorrencia.id)
    setFalhaDeAcao(null)
    try {
      await desativarRecorrencia(recorrencia.id)
      setConfirmando(null)
      recarregar()
      aoMudarOcorrencias()
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      setFalhaDeAcao(mensagemDeDesativacao(erro))
      setConfirmando(null)
      // A lista na tela está velha: busca de novo o que o servidor tem.
      if (erro instanceof RecorrenciaNaoEncontradaError) recarregar()
    } finally {
      acaoEmVoo.current = false
      setDesativando(null)
    }
  }

  const nomesDeConta = new Map<string, string>()
  if (contas !== null) contas.forEach((conta) => nomesDeConta.set(conta.id, conta.nome))

  // Ativas primeiro, cada grupo na ordem que a API mandou.
  const ordenadas =
    estado.tipo === 'ok'
      ? [...estado.recorrencias.filter((r) => r.ativo), ...estado.recorrencias.filter((r) => !r.ativo)]
      : []

  return (
    <section aria-labelledby="titulo-recorrencias">
      <h2 id="titulo-recorrencias">Recorrências</h2>
      {/* Sem as contas a lista mostraria "Conta arquivada" para todo mundo por um instante. */}
      {(estado.tipo === 'carregando' || (estado.tipo === 'ok' && contas === null)) && (
        <p>Carregando recorrências...</p>
      )}
      {estado.tipo === 'erro' && (
        <>
          <p role="alert" className="erro">
            {estado.mensagem}
          </p>
          <button type="button" onClick={recarregar}>
            Tentar de novo
          </button>
        </>
      )}
      {falhaDeAcao !== null && (
        <p role="alert" className="erro">
          {falhaDeAcao}
        </p>
      )}
      {estado.tipo === 'ok' && contas !== null && estado.recorrencias.length === 0 && <p>Nenhuma recorrência.</p>}
      {estado.tipo === 'ok' && contas !== null && estado.recorrencias.length > 0 && (
        <ul className="recorrencias">
          {ordenadas.map((recorrencia) => (
            <li key={recorrencia.id} className={recorrencia.ativo ? undefined : 'desativada'}>
              <div className="recorrencia-dados">
                <strong>{recorrencia.titulo}</strong>
                <span className={recorrencia.tipo}>
                  {recorrencia.tipo === 'receita' ? '+' : '−'} {formatarCentavos(recorrencia.valorCentavos)}
                </span>
                <span>{nomesDeConta.get(recorrencia.contaId) ?? 'Conta arquivada'}</span>
                <span>{descreverRegra(recorrencia.regra)}</span>
                {recorrencia.ativo ? (
                  <span>
                    {recorrencia.proximaOcorrencia === null
                      ? 'Sem próximas'
                      : `Próxima: ${formatarDataCompleta(recorrencia.proximaOcorrencia)}`}
                  </span>
                ) : (
                  <span className="marca">Desativada</span>
                )}
              </div>
              {recorrencia.ativo && (
                <div className="lancamento-acoes">
                  {confirmando === recorrencia.id ? (
                    <>
                      <span>As previstas de hoje em diante somem.</span>
                      <button
                        type="button"
                        disabled={desativando !== null}
                        aria-label={`Confirmar desativar ${recorrencia.titulo}`}
                        onClick={() => void desativar(recorrencia)}
                      >
                        Confirmar desativar
                      </button>
                      <button type="button" disabled={desativando !== null} onClick={() => setConfirmando(null)}>
                        Manter
                      </button>
                    </>
                  ) : (
                    <button
                      type="button"
                      disabled={desativando !== null}
                      aria-label={`Desativar ${recorrencia.titulo}`}
                      onClick={() => {
                        setFalhaDeAcao(null)
                        setConfirmando(recorrencia.id)
                      }}
                    >
                      Desativar
                    </button>
                  )}
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
      {(apoio.tipo === 'carregando' || (apoio.tipo === 'ok' && contas === null)) && (
        <p>Preparando o formulário...</p>
      )}
      {apoio.tipo === 'erro' && (
        <>
          <p role="alert" className="erro">
            {apoio.mensagem}
          </p>
          <button
            type="button"
            onClick={() => {
              setApoio({ tipo: 'carregando' })
              setTentativaDoApoio((n) => n + 1)
            }}
          >
            Tentar de novo
          </button>
        </>
      )}
      {contas !== null && contas.length === 0 && <p>Crie uma conta antes de criar uma recorrência.</p>}
      {contas !== null && contas.length > 0 && apoio.tipo === 'ok' && (
        <FormularioDeRecorrencia
          key={versaoDoFormulario}
          contas={contas}
          categorias={apoio.categorias}
          aoSalvar={() => {
            setVersaoDoFormulario((n) => n + 1)
            recarregar()
            aoMudarOcorrencias()
          }}
          aoSessaoExpirar={aoSessaoExpirar}
        />
      )}
    </section>
  )
}
