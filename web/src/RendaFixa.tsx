import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import {
  InvestimentoNaoEncontradoError,
  SessaoExpiradaError,
  deleteTituloRendaFixa,
  fetchTitulosRendaFixa,
  postMarcacao,
  postResgate,
  postTituloRendaFixa,
  putTituloRendaFixa,
} from './api'
import type { Conta, TituloRendaFixa, TituloRendaFixaRequest, TitulosRendaFixa } from './api'
import { formatarDataCompleta, hojeLocal } from './datas'
import { formatarDecimal, lerTaxa } from './decimais'
import { centavosParaCampo, formatarCentavos, lerCentavos, lerValorPositivo } from './dinheiro'
import { IconeMais } from './icones'
import { carregar, mensagemDeEscrita } from './leitura'
import type { Estado } from './leitura'
import Painel from './Painel'
import { Carregando, EstadoVazio, Selo } from './ui'

type Tipo = TituloRendaFixa['tipo']
type Indexador = TituloRendaFixa['indexador']

const TIPOS: Tipo[] = ['lci', 'lca', 'cdb']
const INDEXADORES: Indexador[] = ['cdi', 'prefixado', 'ipca']

const ROTULO_DO_TIPO: Record<Tipo, string> = { lci: 'LCI', lca: 'LCA', cdb: 'CDB' }

const ROTULO_DO_INDEXADOR: Record<Indexador, string> = {
  cdi: '% do CDI',
  prefixado: 'Prefixado',
  ipca: 'IPCA +',
}

// O que a taxa quer dizer muda com o indexador (ADR-037).
const ROTULO_DA_TAXA: Record<Indexador, string> = {
  cdi: 'Quanto do CDI, em %',
  prefixado: 'Taxa ao ano, em %',
  ipca: 'Taxa ao ano acima do IPCA, em %',
}

function descreverRendimento(titulo: TituloRendaFixa): string {
  const taxa = formatarDecimal(titulo.taxa)
  if (titulo.indexador === 'cdi') return `${taxa}% do CDI`
  if (titulo.indexador === 'prefixado') return `${taxa}% ao ano`
  return `IPCA + ${taxa}% ao ano`
}

type Abertura =
  | { tipo: 'novo' }
  | { tipo: 'editar'; titulo: TituloRendaFixa }
  | { tipo: 'marcar'; titulo: TituloRendaFixa }
  | { tipo: 'resgatar'; titulo: TituloRendaFixa }

function FormularioDeTitulo({
  contas,
  inicial,
  aoSalvar,
  aoSessaoExpirar,
}: {
  contas: Conta[]
  inicial: TituloRendaFixa | null
  aoSalvar: () => void
  aoSessaoExpirar: () => void
}) {
  const [contaId, setContaId] = useState(inicial?.contaId ?? contas[0]?.id ?? '')
  const [tipo, setTipo] = useState<Tipo>(inicial?.tipo ?? 'cdb')
  const [nome, setNome] = useState(inicial?.nome ?? '')
  const [valor, setValor] = useState(inicial === null ? '' : centavosParaCampo(inicial.valorAplicadoCentavos))
  const [aplicacao, setAplicacao] = useState(inicial?.dataAplicacao ?? hojeLocal())
  const [vencimento, setVencimento] = useState(inicial?.vencimento ?? '')
  const [indexador, setIndexador] = useState<Indexador>(inicial?.indexador ?? 'cdi')
  const [taxa, setTaxa] = useState(inicial === null ? '' : formatarDecimal(inicial.taxa))
  const [enviando, setEnviando] = useState(false)
  const [mensagem, setMensagem] = useState<string | null>(null)
  // O state só atualiza no próximo render; o ref barra o segundo submit síncrono.
  const emVoo = useRef(false)

  async function enviar(evento: FormEvent<HTMLFormElement>): Promise<void> {
    evento.preventDefault()
    if (emVoo.current) return
    const valorAplicadoCentavos = lerValorPositivo(valor)
    if (valorAplicadoCentavos === null) {
      setMensagem('Valor aplicado inválido. Use o formato 1.234,56, maior que zero.')
      return
    }
    const taxaLida = lerTaxa(taxa)
    if (taxaLida === null) {
      setMensagem('Taxa inválida. Use um número como 95 ou 12,5, maior que zero.')
      return
    }
    const dados: TituloRendaFixaRequest = {
      contaId,
      tipo,
      nome,
      valorAplicadoCentavos,
      dataAplicacao: aplicacao,
      vencimento: vencimento === '' ? null : vencimento,
      indexador,
      taxa: taxaLida,
    }
    emVoo.current = true
    setEnviando(true)
    setMensagem(null)
    try {
      if (inicial === null) await postTituloRendaFixa(dados)
      else await putTituloRendaFixa(inicial.id, dados)
      aoSalvar()
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      if (erro instanceof InvestimentoNaoEncontradoError) {
        setMensagem('Esse título não existe mais.')
        return
      }
      setMensagem(mensagemDeEscrita(erro, 'Não foi possível salvar o título. Tente de novo.'))
    } finally {
      emVoo.current = false
      setEnviando(false)
    }
  }

  return (
    <form
      onSubmit={(evento) => void enviar(evento)}
      aria-label={inicial === null ? 'Novo título' : `Editar ${inicial.nome}`}
    >
      <div className="campo">
        <label htmlFor="titulo-conta">Conta</label>
        <select id="titulo-conta" value={contaId} required onChange={(evento) => setContaId(evento.target.value)}>
          {contas.map((conta) => (
            <option key={conta.id} value={conta.id}>
              {conta.nome}
            </option>
          ))}
        </select>
      </div>
      <div className="campo">
        <label htmlFor="titulo-tipo">Tipo</label>
        <select
          id="titulo-tipo"
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
        {tipo !== 'cdb' && <small>Isenta de imposto de renda para pessoa física.</small>}
      </div>
      <div className="campo">
        <label htmlFor="titulo-nome">Nome</label>
        <input
          id="titulo-nome"
          type="text"
          maxLength={100}
          required
          placeholder="CDB Banco X 2027"
          value={nome}
          onChange={(evento) => setNome(evento.target.value)}
        />
      </div>
      <div className="campo">
        <label htmlFor="titulo-valor">Valor aplicado em reais</label>
        <input
          id="titulo-valor"
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
        <label htmlFor="titulo-aplicacao">Data da aplicação</label>
        <input
          id="titulo-aplicacao"
          type="date"
          min="1900-01-01"
          max={hojeLocal()}
          required
          value={aplicacao}
          onChange={(evento) => setAplicacao(evento.target.value)}
        />
      </div>
      <div className="campo">
        <label htmlFor="titulo-vencimento">Vencimento (opcional)</label>
        <input
          id="titulo-vencimento"
          type="date"
          min={aplicacao}
          max="2199-12-31"
          value={vencimento}
          onChange={(evento) => setVencimento(evento.target.value)}
        />
      </div>
      <div className="segmentos" role="group" aria-label="Indexador">
        {INDEXADORES.map((opcao) => (
          <button key={opcao} type="button" aria-pressed={indexador === opcao} onClick={() => setIndexador(opcao)}>
            {ROTULO_DO_INDEXADOR[opcao]}
          </button>
        ))}
      </div>
      <div className="campo">
        <label htmlFor="titulo-taxa">{ROTULO_DA_TAXA[indexador]}</label>
        <input
          id="titulo-taxa"
          type="text"
          inputMode="decimal"
          autoComplete="off"
          required
          value={taxa}
          onChange={(evento) => setTaxa(evento.target.value)}
        />
      </div>
      {mensagem !== null && (
        <p role="alert" className="erro">
          {mensagem}
        </p>
      )}
      <button type="submit" className="primario" disabled={enviando}>
        {enviando ? 'Salvando...' : inicial === null ? 'Cadastrar título' : 'Salvar alterações'}
      </button>
    </form>
  )
}

// Marcação (o valor que o banco mostrou) e resgate (o que caiu na conta): uma data e um valor.
function FormularioDeValorNaData({
  titulo,
  acao,
  aoSalvar,
  aoSessaoExpirar,
}: {
  titulo: TituloRendaFixa
  acao: 'marcar' | 'resgatar'
  aoSalvar: () => void
  aoSessaoExpirar: () => void
}) {
  const [data, setData] = useState(hojeLocal())
  const [valor, setValor] = useState('')
  const [enviando, setEnviando] = useState(false)
  const [mensagem, setMensagem] = useState<string | null>(null)
  const emVoo = useRef(false)

  async function enviar(evento: FormEvent<HTMLFormElement>): Promise<void> {
    evento.preventDefault()
    if (emVoo.current) return
    const centavos = lerCentavos(valor)
    if (centavos === null || centavos < 0) {
      setMensagem('Valor inválido. Use o formato 1.234,56.')
      return
    }
    emVoo.current = true
    setEnviando(true)
    setMensagem(null)
    try {
      if (acao === 'marcar') await postMarcacao(titulo.id, data, centavos)
      else await postResgate(titulo.id, data, centavos)
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

  const rotulo = acao === 'marcar' ? `Valor de ${titulo.nome}` : `Resgate de ${titulo.nome}`
  return (
    <form onSubmit={(evento) => void enviar(evento)} aria-label={rotulo}>
      {acao === 'marcar' && (
        <p role="status">
          Digite o valor bruto que o banco mostra. Ele vira o ponto de partida do cálculo a partir dessa data.
        </p>
      )}
      <div className="campo">
        <label htmlFor="valor-data">Data</label>
        <input
          id="valor-data"
          type="date"
          min={titulo.dataAplicacao}
          max={hojeLocal()}
          required
          value={data}
          onChange={(evento) => setData(evento.target.value)}
        />
      </div>
      <div className="campo">
        <label htmlFor="valor-reais">{acao === 'marcar' ? 'Valor bruto em reais' : 'Valor recebido em reais'}</label>
        <input
          id="valor-reais"
          type="text"
          inputMode="decimal"
          autoComplete="off"
          placeholder="0,00"
          required
          value={valor}
          onChange={(evento) => setValor(evento.target.value)}
        />
      </div>
      {mensagem !== null && (
        <p role="alert" className="erro">
          {mensagem}
        </p>
      )}
      <button type="submit" className="primario" disabled={enviando}>
        {enviando ? 'Salvando...' : acao === 'marcar' ? 'Salvar valor' : 'Registrar resgate'}
      </button>
    </form>
  )
}

function ValoresDoTitulo({ titulo }: { titulo: TituloRendaFixa }) {
  if (titulo.valorBrutoCentavos === null || titulo.valorLiquidoCentavos === null) {
    return <small>Ainda não aplicado nesta data.</small>
  }
  if (titulo.resgate !== null && titulo.valorBrutoCentavos === 0) {
    return (
      <small>
        Resgatado em {formatarDataCompleta(titulo.resgate.data)} por {formatarCentavos(titulo.resgate.valorCentavos)}
      </small>
    )
  }
  const imposto = (titulo.irCentavos ?? 0) + (titulo.iofCentavos ?? 0)
  return (
    <dl className="titulo-valores">
      <div>
        <dt>Bruto</dt>
        <dd>{formatarCentavos(titulo.valorBrutoCentavos)}</dd>
      </div>
      {titulo.tipo === 'cdb' && (
        <div>
          <dt>IR e IOF estimados</dt>
          <dd>{formatarCentavos(imposto)}</dd>
        </div>
      )}
      <div>
        <dt>Rendimento</dt>
        <dd>{formatarCentavos(titulo.rendimentoCentavos ?? 0)}</dd>
      </div>
    </dl>
  )
}

export default function RendaFixa({
  contas,
  data,
  aoMudar,
  aoSessaoExpirar,
}: {
  contas: Conta[]
  data: string
  aoMudar: () => void
  aoSessaoExpirar: () => void
}) {
  const [titulos, setTitulos] = useState<Estado<TitulosRendaFixa>>({ tipo: 'carregando' })
  const [versao, setVersao] = useState(0)
  const [abertura, setAbertura] = useState<Abertura | null>(null)
  const [confirmando, setConfirmando] = useState<string | null>(null)
  const [ocupado, setOcupado] = useState<string | null>(null)
  const [falhaDeAcao, setFalhaDeAcao] = useState<string | null>(null)
  const acaoEmVoo = useRef(false)

  useEffect(() => {
    let vale = true
    carregar(() => fetchTitulosRendaFixa(data), 'os títulos', aoSessaoExpirar, setTitulos, () => vale)
    return () => {
      vale = false
    }
  }, [data, versao, aoSessaoExpirar])

  const nomesDeConta = new Map(contas.map((conta) => [conta.id, conta.nome]))

  function depoisDeMudar(): void {
    setAbertura(null)
    setVersao((n) => n + 1)
    aoMudar()
  }

  async function apagar(titulo: TituloRendaFixa): Promise<void> {
    if (acaoEmVoo.current) return
    acaoEmVoo.current = true
    setOcupado(titulo.id)
    setFalhaDeAcao(null)
    try {
      await deleteTituloRendaFixa(titulo.id)
      setConfirmando(null)
      depoisDeMudar()
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      setConfirmando(null)
      setFalhaDeAcao(mensagemDeEscrita(erro, 'Não foi possível apagar o título. Tente de novo.'))
      if (erro instanceof InvestimentoNaoEncontradoError) setVersao((n) => n + 1)
    } finally {
      acaoEmVoo.current = false
      setOcupado(null)
    }
  }

  return (
    <section aria-labelledby="titulo-renda-fixa">
      <header className="pagina-topo">
        <h3 id="titulo-renda-fixa">Renda fixa</h3>
        {contas.length > 0 && (
          <div className="topo-botoes">
            <button type="button" className="primario" onClick={() => setAbertura({ tipo: 'novo' })}>
              <IconeMais />
              Novo título
            </button>
          </div>
        )}
      </header>
      {titulos.tipo === 'ok' && (
        <p className="aviso-cripto">
          {titulos.dados.indices.cdiAte === null
            ? 'Ainda sem CDI do Banco Central: o valor fica igual ao aplicado até o índice chegar.'
            : `Calculado com o CDI até ${formatarDataCompleta(titulos.dados.indices.cdiAte)}. IR e IOF do CDB são estimativa; o valor do banco corrige o cálculo.`}
        </p>
      )}
      {falhaDeAcao !== null && (
        <p role="alert" className="erro">
          {falhaDeAcao}
        </p>
      )}
      {contas.length === 0 && (
        <EstadoVazio
          titulo="Nenhuma conta de investimento."
          dica="Crie uma conta do tipo Investimento na aba Contas, a corretora ou o banco onde os títulos estão."
        />
      )}
      {titulos.tipo === 'carregando' && <Carregando texto="Carregando títulos..." />}
      {titulos.tipo === 'erro' && (
        <p role="alert" className="erro">
          {titulos.mensagem}
        </p>
      )}
      {contas.length > 0 && titulos.tipo === 'ok' && titulos.dados.titulos.length === 0 && (
        <EstadoVazio
          titulo="Nenhum título cadastrado."
          dica="Cadastre LCI, LCA ou CDB com o valor aplicado e a taxa."
          acao={
            <button type="button" className="primario" onClick={() => setAbertura({ tipo: 'novo' })}>
              Cadastrar o primeiro título
            </button>
          }
        />
      )}
      {titulos.tipo === 'ok' && titulos.dados.titulos.length > 0 && (
        <ul className="lancamentos lista-cartao" aria-label="Títulos">
          {titulos.dados.titulos.map((titulo) => (
            <li key={titulo.id}>
              <span className="item-icone">{ROTULO_DO_TIPO[titulo.tipo]}</span>
              <div className="item-corpo">
                <strong className="item-titulo">{titulo.nome}</strong>
                <span className="item-meta">
                  {descreverRendimento(titulo)} · {nomesDeConta.get(titulo.contaId) ?? 'Conta'}
                </span>
                <small>
                  {formatarCentavos(titulo.valorAplicadoCentavos)} em {formatarDataCompleta(titulo.dataAplicacao)}
                  {titulo.vencimento !== null && ` · vence em ${formatarDataCompleta(titulo.vencimento)}`}
                </small>
                {titulo.ultimaMarcacao !== null && (
                  <small>
                    Banco mostrou {formatarCentavos(titulo.ultimaMarcacao.valorCentavos)} em{' '}
                    {formatarDataCompleta(titulo.ultimaMarcacao.data)}
                  </small>
                )}
                <ValoresDoTitulo titulo={titulo} />
                {titulo.resgate !== null && (
                  <div className="item-selos">
                    <Selo tom="paga">Resgatado</Selo>
                  </div>
                )}
              </div>
              <div className="item-lado">
                <span className="item-valor">
                  {titulo.valorLiquidoCentavos === null ? '—' : formatarCentavos(titulo.valorLiquidoCentavos)}
                </span>
              </div>
              {/* Numa linha própria: ao lado do valor, quatro botões espremiam o texto em 360px. */}
              {confirmando === titulo.id ? (
                <div className="titulo-acoes">
                  <button
                    type="button"
                    className="pequeno perigo"
                    disabled={ocupado !== null}
                    onClick={() => void apagar(titulo)}
                  >
                    {ocupado === titulo.id ? 'Apagando...' : 'Confirmar'}
                  </button>
                  <button type="button" className="pequeno discreto" onClick={() => setConfirmando(null)}>
                    Manter
                  </button>
                </div>
              ) : (
                <div className="titulo-acoes">
                  {titulo.resgate === null && (
                    <>
                      <button
                        type="button"
                        className="pequeno"
                        aria-label={`Informar valor de ${titulo.nome}`}
                        onClick={() => setAbertura({ tipo: 'marcar', titulo })}
                      >
                        Valor do banco
                      </button>
                      <button
                        type="button"
                        className="pequeno"
                        aria-label={`Resgatar ${titulo.nome}`}
                        onClick={() => setAbertura({ tipo: 'resgatar', titulo })}
                      >
                        Resgatar
                      </button>
                    </>
                  )}
                  <button
                    type="button"
                    className="pequeno"
                    aria-label={`Editar ${titulo.nome}`}
                    onClick={() => setAbertura({ tipo: 'editar', titulo })}
                  >
                    Editar
                  </button>
                  <button
                    type="button"
                    className="pequeno perigo"
                    aria-label={`Apagar ${titulo.nome}`}
                    disabled={ocupado !== null}
                    onClick={() => {
                      setFalhaDeAcao(null)
                      setConfirmando(titulo.id)
                    }}
                  >
                    Apagar
                  </button>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
      {(abertura?.tipo === 'novo' || abertura?.tipo === 'editar') && contas.length > 0 && (
        <Painel
          titulo={abertura.tipo === 'novo' ? 'Novo título' : `Editar ${abertura.titulo.nome}`}
          aoFechar={() => setAbertura(null)}
        >
          <FormularioDeTitulo
            contas={contas}
            inicial={abertura.tipo === 'editar' ? abertura.titulo : null}
            aoSalvar={depoisDeMudar}
            aoSessaoExpirar={aoSessaoExpirar}
          />
        </Painel>
      )}
      {(abertura?.tipo === 'marcar' || abertura?.tipo === 'resgatar') && (
        <Painel
          titulo={abertura.tipo === 'marcar' ? 'Valor do banco' : 'Resgate'}
          aoFechar={() => setAbertura(null)}
        >
          <FormularioDeValorNaData
            titulo={abertura.titulo}
            acao={abertura.tipo}
            aoSalvar={depoisDeMudar}
            aoSessaoExpirar={aoSessaoExpirar}
          />
        </Painel>
      )}
    </section>
  )
}
