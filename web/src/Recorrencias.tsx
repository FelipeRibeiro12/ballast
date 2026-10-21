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
import { somarLinhas, validarDivisao, valoresGerados } from './divisao'
import type { LinhaDeParcela, ModoDivisao } from './divisao'
import { categoriaEfetiva, categoriasDaConta } from './categoriasDaConta'
import { formatarCentavos, lerValorPositivo } from './dinheiro'
import { IconeMais, IconeRelogio } from './icones'
import Painel from './Painel'
import { formatarPercentual } from './percentual'
import { descreverParcelas, descreverRegra } from './regra'
import { Carregando, EstadoVazio, Selo } from './ui'

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

// Mesmos limites da API (ADR-027).
const MINIMO_DE_PARCELAS = 2
const MAXIMO_DE_PARCELAS = 10

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

// A chave é a identidade da linha: remover uma do meio não pode trocar o que foi digitado nas outras.
type LinhaComChave = LinhaDeParcela & { chave: number }

function linhaVazia(chave: number): LinhaComChave {
  return { chave, dia: '', valor: '' }
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
  const [dividir, setDividir] = useState(false)
  const [modoDivisao, setModoDivisao] = useState<ModoDivisao>('percentual')
  const [linhas, setLinhas] = useState<LinhaComChave[]>([linhaVazia(1), linhaVazia(2)])
  const proximaChave = useRef(3)
  const [enviando, setEnviando] = useState(false)
  const [mensagem, setMensagem] = useState<string | null>(null)
  // O state só atualiza no próximo render; o ref barra o segundo submit síncrono.
  const emVoo = useRef(false)

  const contaId = contas.some((c) => c.id === contaEscolhida) ? contaEscolhida : (contas[0]?.id ?? '')
  const { categorias: categoriasDoTipo, restrita: categoriasRestritas } = categoriasDaConta(
    categorias,
    tipo,
    contas.find((c) => c.id === contaId),
  )
  // Ao trocar o tipo a escolha antiga some da lista; cai na primeira em vez de guardar id inválido.
  const categoriaId = categoriaEfetiva(categoriaEscolhida, categoriasDoTipo, categoriasRestritas)
  const categoriaForaDaConta = categoriasRestritas && categoriaEscolhida !== '' && categoriaId === ''
  // A divisão só existe em mensal; trocar a frequência esconde o bloco sem perder o que foi digitado.
  const dividindo = dividir && frequencia === 'mensal'
  const valorTotal = lerValorPositivo(valor)

  function mudarLinha(chave: number, campo: 'dia' | 'valor', texto: string): void {
    setLinhas((atuais) => atuais.map((linha) => (linha.chave === chave ? { ...linha, [campo]: texto } : linha)))
  }

  function mudarModo(novo: ModoDivisao): void {
    setModoDivisao(novo)
    // "40" em percentual não significa nada em reais, então o campo recomeça vazio.
    setLinhas((atuais) => atuais.map((linha) => ({ ...linha, valor: '' })))
  }

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
    if (frequencia === 'mensal' && !dividindo && diaDoMesLido === null) {
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
    const divisaoLida = dividindo ? validarDivisao(modoDivisao, linhas, valorCentavos) : null
    if (divisaoLida?.tipo === 'erro') {
      setMensagem(divisaoLida.mensagem)
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
      // Com divisão a API recusa diaDoMes na regra: os dias vêm das parcelas.
      ...(frequencia === 'mensal' && !dividindo && diaDoMesLido !== null ? { diaDoMes: diaDoMesLido } : {}),
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
        ...(divisaoLida?.tipo === 'ok' ? { divisao: divisaoLida.divisao } : {}),
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

  const geradas = dividindo && modoDivisao === 'percentual' ? valoresGerados(valorTotal, linhas) : null
  const somaDasLinhas = somarLinhas(modoDivisao, linhas)
  const resumoDaSoma =
    modoDivisao === 'percentual'
      ? `Soma: ${formatarPercentual(Number(somaDasLinhas))} (precisa dar 100%)`
      : `Soma: ${formatarCentavos(somaDasLinhas)} (valor total: ${valorTotal === null ? 'não informado' : formatarCentavos(valorTotal)})`
  const descricaoDoErro = mensagem === null ? undefined : 'erro-recorrencia'

  return (
    <form onSubmit={(evento) => void enviar(evento)} aria-label="Nova recorrência">
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
          aria-describedby={categoriasRestritas ? 'recorrencia-aviso-categoria' : undefined}
          onChange={(evento) => setCategoriaEscolhida(evento.target.value)}
        >
          {categoriaId === '' && (
            <option value="" disabled>
              Escolha uma categoria
            </option>
          )}
          {categoriasDoTipo.map((categoria) => (
            <option key={categoria.id} value={categoria.id}>
              {categoria.nome}
            </option>
          ))}
        </select>
        {categoriasRestritas && (
          <small id="recorrencia-aviso-categoria" aria-live="polite">
            {categoriaForaDaConta
              ? 'A categoria escolhida não vale nesta conta. Escolha outra.'
              : 'Esta conta só aceita algumas categorias de despesa.'}
          </small>
        )}
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
        {contas.find((c) => c.id === contaId)?.cartao != null && (
          <small>As ocorrências entram na fatura de cada mês.</small>
        )}
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
      {frequencia === 'mensal' && !dividindo && (
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
      {frequencia === 'mensal' && (
        <div className="campo">
          <label htmlFor="recorrencia-dividir">
            <input
              id="recorrencia-dividir"
              type="checkbox"
              checked={dividir}
              onChange={(evento) => setDividir(evento.target.checked)}
            />{' '}
            Dividir em vários dias
          </label>
        </div>
      )}
      {dividindo && (
        <fieldset>
          <legend>Parcelas</legend>
          <div className="campo">
            <label htmlFor="recorrencia-modo-divisao">Dividir por</label>
            <select
              id="recorrencia-modo-divisao"
              value={modoDivisao}
              onChange={(evento) => {
                if (evento.target.value === 'percentual' || evento.target.value === 'valor') {
                  mudarModo(evento.target.value)
                }
              }}
            >
              <option value="percentual">Percentual</option>
              <option value="valor">Valor</option>
            </select>
          </div>
          {linhas.map((linha, indice) => {
            const numero = indice + 1
            return (
              <div className="campo" key={linha.chave}>
                <label htmlFor={`parcela-dia-${linha.chave}`}>{`Dia do mês da parcela ${numero}`}</label>
                <input
                  id={`parcela-dia-${linha.chave}`}
                  type="number"
                  inputMode="numeric"
                  min={1}
                  max={31}
                  step={1}
                  required
                  value={linha.dia}
                  onChange={(evento) => mudarLinha(linha.chave, 'dia', evento.target.value)}
                />
                <label htmlFor={`parcela-valor-${linha.chave}`}>
                  {`${modoDivisao === 'percentual' ? 'Percentual' : 'Valor'} da parcela ${numero}`}
                </label>
                <input
                  id={`parcela-valor-${linha.chave}`}
                  type="text"
                  inputMode="decimal"
                  autoComplete="off"
                  placeholder="0,00"
                  required
                  value={linha.valor}
                  onChange={(evento) => mudarLinha(linha.chave, 'valor', evento.target.value)}
                />
                {geradas !== null && <small>{`Gera ${formatarCentavos(geradas[indice])}`}</small>}
                {linhas.length > MINIMO_DE_PARCELAS && (
                  <button
                    type="button"
                    className="pequeno perigo"
                    onClick={() => setLinhas((atuais) => atuais.filter((l) => l.chave !== linha.chave))}
                  >
                    {`Remover parcela ${numero}`}
                  </button>
                )}
              </div>
            )
          })}
          {linhas.length < MAXIMO_DE_PARCELAS && (
            <button
              type="button"
              className="pequeno"
              onClick={() => {
                setLinhas((atuais) => [...atuais, linhaVazia(proximaChave.current)])
                proximaChave.current += 1
              }}
            >
              Adicionar parcela
            </button>
          )}
          <p aria-live="polite">{resumoDaSoma}</p>
        </fieldset>
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
  aoCarregarParcelas,
  aoMudarOcorrencias,
}: {
  aoSessaoExpirar: () => void
  // Contas ativas, vindas da tela de Contas; null até a primeira carga.
  contas: Conta[] | null
  // Sobe quando a lista de lançamentos mexeu nas ocorrências (muda a próxima data).
  versao: number
  // Entrega os ids das recorrências ativas para a lista do mês avisar que apagar faz voltar.
  aoCarregarAtivas: (ids: ReadonlySet<string>) => void
  // Entrega o total de parcelas de cada recorrência com divisão, ativa ou não: as já realizadas continuam na lista do mês.
  aoCarregarParcelas: (totais: ReadonlyMap<string, number>) => void
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
  const [painelAberto, setPainelAberto] = useState(false)
  const acaoEmVoo = useRef(false)

  useEffect(() => {
    // Descarta a resposta antiga se outra busca já começou.
    let ativo = true
    fetchRecorrencias().then(
      (recorrencias) => {
        if (!ativo) return
        setEstado({ tipo: 'ok', recorrencias })
        aoCarregarAtivas(new Set(recorrencias.filter((r) => r.ativo).map((r) => r.id)))
        aoCarregarParcelas(
          new Map(
            recorrencias.flatMap((r): [string, number][] => (r.divisao === null ? [] : [[r.id, r.divisao.parcelas.length]])),
          ),
        )
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
  }, [tentativa, versao, aoSessaoExpirar, aoCarregarAtivas, aoCarregarParcelas])

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

  const semContas = contas !== null && contas.length === 0

  return (
    <section aria-labelledby="titulo-recorrencias">
      <header className="pagina-topo">
        <h2 id="titulo-recorrencias">Recorrências</h2>
        <button
          type="button"
          className="primario"
          disabled={contas === null || semContas}
          onClick={() => setPainelAberto(true)}
        >
          <IconeMais />
          Nova recorrência
        </button>
      </header>
      {/* Sem as contas a lista mostraria "Conta arquivada" para todo mundo por um instante. */}
      {(estado.tipo === 'carregando' || (estado.tipo === 'ok' && contas === null)) && (
        <Carregando texto="Carregando recorrências..." />
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
      {semContas && <p className="vazio-dica">Crie uma conta antes de criar uma recorrência.</p>}
      {estado.tipo === 'ok' && contas !== null && estado.recorrencias.length === 0 && (
        <EstadoVazio
          titulo="Nenhuma recorrência."
          dica="Aluguel, salário e assinaturas entram sozinhos como previstos."
          acao={
            semContas ? undefined : (
              <button type="button" className="primario" onClick={() => setPainelAberto(true)}>
                Criar a primeira recorrência
              </button>
            )
          }
        />
      )}
      {estado.tipo === 'ok' && contas !== null && estado.recorrencias.length > 0 && (
        <ul className="recorrencias">
          {ordenadas.map((recorrencia) => (
            <li
              key={recorrencia.id}
              className={recorrencia.ativo ? 'cartao recorrencia-cartao' : 'cartao recorrencia-cartao desativada'}
            >
              <div className="recorrencia-topo">
                <strong>{recorrencia.titulo}</strong>
                <span className={`item-valor ${recorrencia.tipo}`}>
                  {recorrencia.tipo === 'receita' ? '+' : '−'} {formatarCentavos(recorrencia.valorCentavos)}
                </span>
              </div>
              <p className="recorrencia-regra">{descreverRegra(recorrencia.regra, recorrencia.divisao)}</p>
              {recorrencia.divisao !== null && (
                <ul className="parcelas" aria-label={`Parcelas de ${recorrencia.titulo}`}>
                  {descreverParcelas(recorrencia.divisao).map((texto) => (
                    <li key={texto}>{texto}</li>
                  ))}
                </ul>
              )}
              <div className="recorrencia-meta">
                <span>{nomesDeConta.get(recorrencia.contaId) ?? 'Conta arquivada'}</span>
                {recorrencia.ativo ? (
                  <span className="proxima">
                    <IconeRelogio tamanho={14} />
                    {recorrencia.proximaOcorrencia === null
                      ? 'Sem próximas'
                      : `Próxima: ${formatarDataCompleta(recorrencia.proximaOcorrencia)}`}
                  </span>
                ) : (
                  <Selo tom="cancelado">Desativada</Selo>
                )}
              </div>
              {recorrencia.ativo && (
                <div className="item-acoes">
                  {confirmando === recorrencia.id ? (
                    <>
                      <span className="aviso-acao">As previstas de hoje em diante somem.</span>
                      <button
                        type="button"
                        className="pequeno perigo forte"
                        disabled={desativando !== null}
                        aria-label={`Confirmar desativar ${recorrencia.titulo}`}
                        onClick={() => void desativar(recorrencia)}
                      >
                        Confirmar desativar
                      </button>
                      <button
                        type="button"
                        className="pequeno"
                        disabled={desativando !== null}
                        onClick={() => setConfirmando(null)}
                      >
                        Manter
                      </button>
                    </>
                  ) : (
                    <button
                      type="button"
                      className="pequeno perigo"
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
      {painelAberto && contas !== null && contas.length > 0 && (
        <Painel titulo="Nova recorrência" aoFechar={() => setPainelAberto(false)}>
          {apoio.tipo === 'ok' ? (
            <FormularioDeRecorrencia
              key={versaoDoFormulario}
              contas={contas}
              categorias={apoio.categorias}
              aoSalvar={() => {
                setVersaoDoFormulario((n) => n + 1)
                setPainelAberto(false)
                recarregar()
                aoMudarOcorrencias()
              }}
              aoSessaoExpirar={aoSessaoExpirar}
            />
          ) : (
            <Carregando texto="Preparando o formulário..." linhas={4} />
          )}
        </Painel>
      )}
    </section>
  )
}
