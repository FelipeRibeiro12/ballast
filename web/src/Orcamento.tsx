import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { SessaoExpiradaError, deleteOrcamento, fetchCategorias, fetchOrcamentos, putOrcamento } from './api'
import type { Categoria, Orcamento as OrcamentoDoMes, OrcamentosDoMes } from './api'
import { mesDeHoje, mesIso, rotuloDoMes, somarMeses } from './datas'
import type { Mes } from './datas'
import { centavosParaCampo, formatarCentavos, lerValorPositivo } from './dinheiro'
import { IconeMais, IconeSetaDireita, IconeSetaEsquerda } from './icones'
import { carregar, mensagemDeEscrita } from './leitura'
import type { Estado } from './leitura'
import MenuDeAcoes from './MenuDeAcoes'
import Painel from './Painel'
import { Carregando, EstadoVazio, Selo } from './ui'

// A faixa do CHECK da V18.
const ANO_MINIMO = 1900
const ANO_MAXIMO = 2199

function lerMesIso(texto: string): Mes {
  const [ano, mes] = texto.split('-').map(Number)
  return { ano, mes }
}

// Só para a largura da barra: percentual inteiro do teto já gasto, em BigInt, até 100.
function percentualGasto(tetoCentavos: number, gastoCentavos: number): number {
  const teto = BigInt(tetoCentavos)
  const gasto = BigInt(gastoCentavos)
  if (teto <= 0n || gasto <= 0n) return 0
  if (gasto >= teto) return 100
  return Number((gasto * 100n) / teto)
}

function FormularioDeTeto({
  mes,
  categorias,
  inicial,
  aoSalvar,
  aoSessaoExpirar,
}: {
  mes: Mes
  categorias: Categoria[]
  inicial: OrcamentoDoMes | null
  aoSalvar: () => void
  aoSessaoExpirar: () => void
}) {
  const [categoriaId, setCategoriaId] = useState(inicial?.categoriaId ?? categorias[0]?.id ?? '')
  const [valor, setValor] = useState(inicial === null ? '' : centavosParaCampo(inicial.tetoCentavos))
  const [enviando, setEnviando] = useState(false)
  const [mensagem, setMensagem] = useState<string | null>(null)
  // O state só atualiza no próximo render; o ref barra o segundo submit síncrono.
  const emVoo = useRef(false)

  async function enviar(evento: FormEvent<HTMLFormElement>): Promise<void> {
    evento.preventDefault()
    if (emVoo.current) return
    const centavos = lerValorPositivo(valor)
    if (centavos === null) {
      setMensagem('Valor inválido. Use o formato 1.234,56.')
      return
    }
    if (categoriaId === '') {
      setMensagem('Escolha uma categoria.')
      return
    }
    emVoo.current = true
    setEnviando(true)
    setMensagem(null)
    try {
      await putOrcamento(categoriaId, centavos, mesIso(mes))
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
    <form onSubmit={(evento) => void enviar(evento)} aria-label="Teto do orçamento">
      {inicial === null && (
        <div className="campo">
          <label htmlFor="orcamento-categoria">Categoria</label>
          <select id="orcamento-categoria" value={categoriaId} onChange={(evento) => setCategoriaId(evento.target.value)}>
            {categorias.map((categoria) => (
              <option key={categoria.id} value={categoria.id}>
                {categoria.nome}
              </option>
            ))}
          </select>
        </div>
      )}
      <div className="campo">
        <label htmlFor="orcamento-teto">Teto por mês</label>
        <input
          id="orcamento-teto"
          type="text"
          inputMode="decimal"
          autoComplete="off"
          placeholder="0,00"
          required
          value={valor}
          onChange={(evento) => setValor(evento.target.value)}
        />
      </div>
      <p role="status">Vale a partir de {rotuloDoMes(mes)}, até você mudar.</p>
      {mensagem !== null && (
        <p role="alert" className="erro">
          {mensagem}
        </p>
      )}
      <button type="submit" className="primario" disabled={enviando}>
        {enviando ? 'Salvando...' : 'Salvar teto'}
      </button>
    </form>
  )
}

export function ConsumoDoMes({ orcamento }: { orcamento: OrcamentoDoMes }) {
  const usado = percentualGasto(orcamento.tetoCentavos, orcamento.gastoCentavos)
  const passou = orcamento.restanteCentavos < 0
  return (
    <>
      <small>
        {formatarCentavos(orcamento.gastoCentavos)} de {formatarCentavos(orcamento.tetoCentavos)}
      </small>
      <div className={passou ? 'barra critica' : usado >= 80 ? 'barra atencao' : 'barra'} aria-hidden="true">
        <div style={{ width: `${usado}%` }} />
      </div>
      <small>
        {passou
          ? `Passou ${formatarCentavos(-orcamento.restanteCentavos)} do teto`
          : `Falta ${formatarCentavos(orcamento.restanteCentavos)}`}
      </small>
      {orcamento.previstoCentavos > 0 && (
        <small>Mais {formatarCentavos(orcamento.previstoCentavos)} previstos no mês</small>
      )}
    </>
  )
}

type Abertura = { tipo: 'novo' } | { tipo: 'editar'; orcamento: OrcamentoDoMes }

// Teto por categoria de despesa com vigência (ADR-043): o que se grava aqui vale do mês na tela
// em diante, até a próxima mudança.
export default function Orcamento({ aoSessaoExpirar }: { aoSessaoExpirar: () => void }) {
  const [mes, setMes] = useState<Mes>(() => mesDeHoje())
  const [versao, setVersao] = useState(0)
  const [estado, setEstado] = useState<Estado<OrcamentosDoMes>>({ tipo: 'carregando' })
  const [categorias, setCategorias] = useState<Estado<Categoria[]>>({ tipo: 'carregando' })
  const [abertura, setAbertura] = useState<Abertura | null>(null)
  const [confirmando, setConfirmando] = useState<string | null>(null)
  const [falha, setFalha] = useState<string | null>(null)

  useEffect(() => {
    let vale = true
    carregar(fetchCategorias, 'as categorias', aoSessaoExpirar, setCategorias, () => vale)
    return () => {
      vale = false
    }
  }, [aoSessaoExpirar])

  useEffect(() => {
    let vale = true
    carregar(() => fetchOrcamentos(mesIso(mes)), 'o orçamento', aoSessaoExpirar, setEstado, () => vale)
    return () => {
      vale = false
    }
  }, [mes, versao, aoSessaoExpirar])

  function navegar(delta: number): void {
    setEstado({ tipo: 'carregando' })
    setConfirmando(null)
    setFalha(null)
    setMes((atual) => somarMeses(atual, delta))
  }

  async function encerrar(orcamento: OrcamentoDoMes): Promise<void> {
    setFalha(null)
    try {
      await deleteOrcamento(orcamento.categoriaId, mesIso(mes))
      setConfirmando(null)
      setVersao((n) => n + 1)
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      setFalha(mensagemDeEscrita(erro, 'Não foi possível encerrar. Tente de novo.'))
    }
  }

  const despesas = categorias.tipo === 'ok' ? categorias.dados.filter((categoria) => categoria.tipo === 'despesa') : []
  const comTeto = new Set(estado.tipo === 'ok' ? estado.dados.orcamentos.map((orcamento) => orcamento.categoriaId) : [])
  const semTeto = despesas.filter((categoria) => !comTeto.has(categoria.id))
  const rotulo = rotuloDoMes(mes)

  return (
    <section aria-labelledby="titulo-orcamento">
      <header className="pagina-topo">
        <h2 id="titulo-orcamento">Orçamento</h2>
        <button
          type="button"
          className="primario botao-novo"
          disabled={estado.tipo !== 'ok' || categorias.tipo !== 'ok' || semTeto.length === 0}
          onClick={() => setAbertura({ tipo: 'novo' })}
        >
          <IconeMais />
          Novo teto
        </button>
      </header>
      <div className="mes-barra">
        <div className="mes-nav">
          <button
            type="button"
            aria-label="Mês anterior"
            disabled={mes.ano <= ANO_MINIMO && mes.mes === 1}
            onClick={() => navegar(-1)}
          >
            <IconeSetaEsquerda />
          </button>
          <strong aria-live="polite">{rotulo}</strong>
          <button
            type="button"
            aria-label="Próximo mês"
            disabled={mes.ano >= ANO_MAXIMO && mes.mes === 12}
            onClick={() => navegar(1)}
          >
            <IconeSetaDireita />
          </button>
        </div>
      </div>
      {estado.tipo === 'carregando' && <Carregando texto="Carregando orçamento..." />}
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
      {estado.tipo === 'ok' && estado.dados.orcamentos.length === 0 && (
        <EstadoVazio
          titulo="Nenhum orçamento neste mês."
          dica="Defina um teto por categoria de despesa. Ele vale para os meses seguintes até você mudar."
        />
      )}
      {estado.tipo === 'ok' && estado.dados.orcamentos.length > 0 && (
        <ul className="lancamentos lista-cartao" aria-label="Orçamentos">
          {estado.dados.orcamentos.map((orcamento) => {
            const nome = orcamento.categoriaNome
            return (
              <li key={orcamento.categoriaId}>
                <div className="item-corpo">
                  <strong className="item-titulo">{nome}</strong>
                  <ConsumoDoMes orcamento={orcamento} />
                  {orcamento.desde !== mesIso(mes) && (
                    <small>Teto desde {rotuloDoMes(lerMesIso(orcamento.desde))}</small>
                  )}
                  {orcamento.restanteCentavos < 0 && (
                    <div className="item-selos">
                      <Selo tom="vencida">Acima do teto</Selo>
                    </div>
                  )}
                </div>
                <div className="item-lado">
                  {confirmando === orcamento.categoriaId ? (
                    <div className="item-botoes">
                      <button
                        type="button"
                        className="pequeno perigo"
                        aria-label={`Confirmar encerrar ${nome} a partir de ${rotulo}`}
                        onClick={() => void encerrar(orcamento)}
                      >
                        Encerrar
                      </button>
                      <button type="button" className="pequeno" onClick={() => setConfirmando(null)}>
                        Manter
                      </button>
                    </div>
                  ) : (
                    <MenuDeAcoes rotulo={`Mais ações de ${nome}`}>
                      <button
                        type="button"
                        aria-label={`Editar teto de ${nome}`}
                        onClick={() => setAbertura({ tipo: 'editar', orcamento })}
                      >
                        Editar teto
                      </button>
                      <button
                        type="button"
                        className="perigo"
                        aria-label={`Encerrar orçamento de ${nome}`}
                        onClick={() => {
                          setFalha(null)
                          setConfirmando(orcamento.categoriaId)
                        }}
                      >
                        Encerrar a partir deste mês
                      </button>
                    </MenuDeAcoes>
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
            abertura.tipo === 'novo'
              ? 'Novo teto'
              : `Teto de ${abertura.orcamento.categoriaNome}`
          }
          aoFechar={() => setAbertura(null)}
        >
          <FormularioDeTeto
            mes={mes}
            categorias={semTeto}
            inicial={abertura.tipo === 'editar' ? abertura.orcamento : null}
            aoSalvar={() => {
              setAbertura(null)
              setVersao((n) => n + 1)
            }}
            aoSessaoExpirar={aoSessaoExpirar}
          />
        </Painel>
      )}
    </section>
  )
}
