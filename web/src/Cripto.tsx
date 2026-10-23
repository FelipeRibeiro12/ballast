import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import {
  CriptoNaoEncontradoError,
  SessaoExpiradaError,
  deleteMovimentoCripto,
  fetchAtivosCripto,
  fetchMovimentosCripto,
  fetchPosicoesCripto,
  postAtivoCripto,
  postCotacao,
  postMovimentoCripto,
} from './api'
import type { AtivoCripto, AtivoCriptoRequest, Conta, MovimentoCripto, PosicoesCripto } from './api'
import { formatarDecimal, lerPreco, lerQuantidade } from './decimais'
import { diaEmSaoPaulo, formatarDataCompleta, formatarDiaMes, hojeLocal } from './datas'
import { formatarCentavos, lerValorPositivo } from './dinheiro'
import { IconeMais } from './icones'
import { carregar, mensagemDeEscrita } from './leitura'
import type { Estado } from './leitura'
import Painel from './Painel'
import { Carregando, EstadoVazio, Selo } from './ui'


type Rede = AtivoCripto['rede']

const REDES: Rede[] = ['bitcoin', 'ethereum', 'base', 'arbitrum', 'polygon', 'solana']

const ROTULO_DA_REDE: Record<Rede, string> = {
  bitcoin: 'Bitcoin',
  ethereum: 'Ethereum',
  base: 'Base',
  arbitrum: 'Arbitrum',
  polygon: 'Polygon',
  solana: 'Solana',
}

// Casas do ativo nativo de cada rede: satoshi, lamport e wei.
const DECIMAIS_DO_NATIVO: Record<Rede, number> = {
  bitcoin: 8,
  ethereum: 18,
  base: 18,
  arbitrum: 18,
  polygon: 18,
  solana: 9,
}

type Abertura = { tipo: 'movimento' } | { tipo: 'ativo' } | { tipo: 'cotacao'; ativo: AtivoCripto }

function rotuloDoAtivo(ativo: AtivoCripto | undefined): string {
  if (ativo === undefined) return 'Ativo'
  return `${ativo.simbolo} · ${ROTULO_DA_REDE[ativo.rede]}`
}

function NovoAtivo({ aoCriar, aoSessaoExpirar }: { aoCriar: () => void; aoSessaoExpirar: () => void }) {
  const [rede, setRede] = useState<Rede>('bitcoin')
  const [contrato, setContrato] = useState('')
  const [simbolo, setSimbolo] = useState('')
  const [nome, setNome] = useState('')
  const [decimais, setDecimais] = useState(String(DECIMAIS_DO_NATIVO.bitcoin))
  const [enviando, setEnviando] = useState(false)
  const [mensagem, setMensagem] = useState<string | null>(null)
  // O state só atualiza no próximo render; o ref barra o segundo submit síncrono.
  const emVoo = useRef(false)

  const aceitaContrato = rede !== 'bitcoin'

  async function enviar(evento: FormEvent<HTMLFormElement>): Promise<void> {
    evento.preventDefault()
    if (emVoo.current) return
    if (!/^\d{1,2}$/.test(decimais.trim()) || Number(decimais) > 18) {
      setMensagem('Casas decimais devem ser um número de 0 a 18.')
      return
    }
    const dados: AtivoCriptoRequest = {
      rede,
      contrato: aceitaContrato && contrato.trim() !== '' ? contrato.trim() : null,
      simbolo,
      nome: nome.trim() === '' ? null : nome,
      decimais: Number(decimais),
    }
    emVoo.current = true
    setEnviando(true)
    setMensagem(null)
    try {
      await postAtivoCripto(dados)
      aoCriar()
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      setMensagem(mensagemDeEscrita(erro, 'Não foi possível cadastrar o ativo. Tente de novo.'))
    } finally {
      emVoo.current = false
      setEnviando(false)
    }
  }

  return (
    <form onSubmit={(evento) => void enviar(evento)} aria-label="Novo ativo">
      <div className="campo">
        <label htmlFor="ativo-rede">Rede</label>
        <select
          id="ativo-rede"
          value={rede}
          onChange={(evento) => {
            const escolhida = REDES.find((opcao) => opcao === evento.target.value)
            if (escolhida === undefined) return
            setRede(escolhida)
            setDecimais(String(DECIMAIS_DO_NATIVO[escolhida]))
          }}
        >
          {REDES.map((opcao) => (
            <option key={opcao} value={opcao}>
              {ROTULO_DA_REDE[opcao]}
            </option>
          ))}
        </select>
      </div>
      {aceitaContrato && (
        <div className="campo">
          <label htmlFor="ativo-contrato">Contrato do token (opcional)</label>
          <input
            id="ativo-contrato"
            type="text"
            autoComplete="off"
            spellCheck={false}
            maxLength={64}
            value={contrato}
            onChange={(evento) => setContrato(evento.target.value)}
          />
          <small>Vazio para a moeda nativa da rede. O contrato identifica o token, não a sua carteira.</small>
        </div>
      )}
      <div className="campo">
        <label htmlFor="ativo-simbolo">Símbolo</label>
        <input
          id="ativo-simbolo"
          type="text"
          autoComplete="off"
          maxLength={20}
          required
          value={simbolo}
          onChange={(evento) => setSimbolo(evento.target.value)}
        />
      </div>
      <div className="campo">
        <label htmlFor="ativo-nome">Nome (opcional)</label>
        <input
          id="ativo-nome"
          type="text"
          autoComplete="off"
          maxLength={100}
          value={nome}
          onChange={(evento) => setNome(evento.target.value)}
        />
      </div>
      <div className="campo">
        <label htmlFor="ativo-decimais">Casas decimais</label>
        <input
          id="ativo-decimais"
          type="number"
          inputMode="numeric"
          min={0}
          max={18}
          required
          value={decimais}
          onChange={(evento) => setDecimais(evento.target.value)}
        />
      </div>
      {mensagem !== null && (
        <p role="alert" className="erro">
          {mensagem}
        </p>
      )}
      <button type="submit" className="primario" disabled={enviando}>
        {enviando ? 'Salvando...' : 'Cadastrar ativo'}
      </button>
    </form>
  )
}

function NovoMovimento({
  contas,
  ativos,
  aoCriar,
  aoSessaoExpirar,
}: {
  contas: Conta[]
  ativos: AtivoCripto[]
  aoCriar: () => void
  aoSessaoExpirar: () => void
}) {
  const [contaId, setContaId] = useState(contas[0]?.id ?? '')
  const [ativoId, setAtivoId] = useState(ativos[0]?.id ?? '')
  const [tipo, setTipo] = useState<MovimentoCripto['tipo']>('compra')
  const [quantidade, setQuantidade] = useState('')
  const [data, setData] = useState(hojeLocal())
  const [valor, setValor] = useState('')
  const [enviando, setEnviando] = useState(false)
  const [mensagem, setMensagem] = useState<string | null>(null)
  // O state só atualiza no próximo render; o ref barra o segundo submit síncrono.
  const emVoo = useRef(false)

  async function enviar(evento: FormEvent<HTMLFormElement>): Promise<void> {
    evento.preventDefault()
    if (emVoo.current) return
    const quantidadeLida = lerQuantidade(quantidade)
    if (quantidadeLida === null) {
      setMensagem('Quantidade inválida. Use até 18 casas, com vírgula ou ponto, maior que zero.')
      return
    }
    const custoCentavos = lerValorPositivo(valor)
    if (custoCentavos === null) {
      setMensagem('Valor inválido. Use o formato 1.234,56, maior que zero.')
      return
    }
    emVoo.current = true
    setEnviando(true)
    setMensagem(null)
    try {
      await postMovimentoCripto({ contaId, ativoId, tipo, quantidade: quantidadeLida, data, custoCentavos })
      aoCriar()
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      setMensagem(mensagemDeEscrita(erro, 'Não foi possível registrar o movimento. Tente de novo.'))
    } finally {
      emVoo.current = false
      setEnviando(false)
    }
  }

  return (
    <form onSubmit={(evento) => void enviar(evento)} aria-label="Novo movimento">
      <div className="segmentos" role="group" aria-label="Tipo de movimento">
        <button type="button" aria-pressed={tipo === 'compra'} onClick={() => setTipo('compra')}>
          Compra
        </button>
        <button type="button" aria-pressed={tipo === 'venda'} onClick={() => setTipo('venda')}>
          Venda
        </button>
      </div>
      <div className="campo">
        <label htmlFor="movimento-conta">Conta</label>
        <select id="movimento-conta" value={contaId} required onChange={(evento) => setContaId(evento.target.value)}>
          {contas.map((conta) => (
            <option key={conta.id} value={conta.id}>
              {conta.nome}
            </option>
          ))}
        </select>
      </div>
      <div className="campo">
        <label htmlFor="movimento-ativo">Ativo</label>
        <select id="movimento-ativo" value={ativoId} required onChange={(evento) => setAtivoId(evento.target.value)}>
          {ativos.map((ativo) => (
            <option key={ativo.id} value={ativo.id}>
              {rotuloDoAtivo(ativo)}
            </option>
          ))}
        </select>
      </div>
      <div className="campo">
        <label htmlFor="movimento-quantidade">Quantidade</label>
        <input
          id="movimento-quantidade"
          type="text"
          inputMode="decimal"
          autoComplete="off"
          placeholder="0,00000000"
          required
          value={quantidade}
          onChange={(evento) => setQuantidade(evento.target.value)}
        />
      </div>
      <div className="campo">
        <label htmlFor="movimento-data">Data</label>
        <input
          id="movimento-data"
          type="date"
          min="1900-01-01"
          max="2199-12-31"
          required
          value={data}
          onChange={(evento) => setData(evento.target.value)}
        />
      </div>
      <div className="campo">
        <label htmlFor="movimento-valor">{tipo === 'compra' ? 'Quanto pagou, em reais' : 'Quanto recebeu, em reais'}</label>
        <input
          id="movimento-valor"
          type="text"
          inputMode="decimal"
          autoComplete="off"
          placeholder="0,00"
          required
          value={valor}
          onChange={(evento) => setValor(evento.target.value)}
        />
        <small>O dinheiro na sua conta corrente não muda: lance a saída ou a entrada lá, se quiser.</small>
      </div>
      {mensagem !== null && (
        <p role="alert" className="erro">
          {mensagem}
        </p>
      )}
      <button type="submit" className="primario" disabled={enviando}>
        {enviando ? 'Salvando...' : tipo === 'compra' ? 'Registrar compra' : 'Registrar venda'}
      </button>
    </form>
  )
}

function NovaCotacao({
  ativo,
  aoSalvar,
  aoSessaoExpirar,
}: {
  ativo: AtivoCripto
  aoSalvar: () => void
  aoSessaoExpirar: () => void
}) {
  const [preco, setPreco] = useState('')
  const [enviando, setEnviando] = useState(false)
  const [mensagem, setMensagem] = useState<string | null>(null)
  // O state só atualiza no próximo render; o ref barra o segundo submit síncrono.
  const emVoo = useRef(false)

  async function enviar(evento: FormEvent<HTMLFormElement>): Promise<void> {
    evento.preventDefault()
    if (emVoo.current) return
    const precoLido = lerPreco(preco)
    if (precoLido === null) {
      setMensagem('Preço inválido. Use o formato 350.000,50, com até 8 casas, maior que zero.')
      return
    }
    emVoo.current = true
    setEnviando(true)
    setMensagem(null)
    try {
      // Sem momento: a API grava o instante da requisição.
      await postCotacao(ativo.id, { preco: precoLido })
      aoSalvar()
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      if (erro instanceof CriptoNaoEncontradoError) {
        setMensagem('Esse ativo não existe mais.')
        return
      }
      setMensagem(mensagemDeEscrita(erro, 'Não foi possível salvar a cotação. Tente de novo.'))
    } finally {
      emVoo.current = false
      setEnviando(false)
    }
  }

  return (
    <form onSubmit={(evento) => void enviar(evento)} aria-label={`Cotação de ${ativo.simbolo}`}>
      <div className="campo">
        <label htmlFor="cotacao-preco">Preço de 1 {ativo.simbolo} em reais</label>
        <input
          id="cotacao-preco"
          type="text"
          inputMode="decimal"
          autoComplete="off"
          placeholder="0,00"
          required
          value={preco}
          onChange={(evento) => setPreco(evento.target.value)}
        />
        <small>Vale a partir de agora, até a próxima cotação.</small>
      </div>
      {mensagem !== null && (
        <p role="alert" className="erro">
          {mensagem}
        </p>
      )}
      <button type="submit" className="primario" disabled={enviando}>
        {enviando ? 'Salvando...' : 'Salvar cotação'}
      </button>
    </form>
  )
}

// A data e o patrimônio ficam na tela de Investimentos; quem desenha remonta este componente quando
// a data muda (key), e aoMudar avisa para o patrimônio ser buscado de novo.
export default function Cripto({
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
  const [versao, setVersao] = useState(0)
  const [abertura, setAbertura] = useState<Abertura | null>(null)
  const [confirmando, setConfirmando] = useState<string | null>(null)
  const [apagando, setApagando] = useState<string | null>(null)
  const [falhaDeAcao, setFalhaDeAcao] = useState<string | null>(null)
  const acaoEmVoo = useRef(false)

  const [ativos, setAtivos] = useState<Estado<AtivoCripto[]>>({ tipo: 'carregando' })
  const [movimentos, setMovimentos] = useState<Estado<MovimentoCripto[]>>({ tipo: 'carregando' })
  const [posicoes, setPosicoes] = useState<Estado<PosicoesCripto>>({ tipo: 'carregando' })

  useEffect(() => {
    let vale = true
    carregar(fetchAtivosCripto, 'os ativos', aoSessaoExpirar, setAtivos, () => vale)
    carregar(fetchMovimentosCripto, 'os movimentos', aoSessaoExpirar, setMovimentos, () => vale)
    return () => {
      vale = false
    }
  }, [versao, aoSessaoExpirar])

  useEffect(() => {
    let vale = true
    carregar(() => fetchPosicoesCripto(data), 'as posições', aoSessaoExpirar, setPosicoes, () => vale)
    return () => {
      vale = false
    }
  }, [data, versao, aoSessaoExpirar])

  const ativosPorId = new Map(ativos.tipo === 'ok' ? ativos.dados.map((ativo) => [ativo.id, ativo]) : [])
  const nomesDeConta = new Map(contas.map((conta) => [conta.id, conta.nome]))
  const listaDeAtivos = ativos.tipo === 'ok' ? ativos.dados : []
  const podeMovimentar = contas.length > 0 && listaDeAtivos.length > 0

  function depoisDeMudar(): void {
    setAbertura(null)
    setVersao((n) => n + 1)
    aoMudar()
  }

  async function apagar(movimento: MovimentoCripto): Promise<void> {
    if (acaoEmVoo.current) return
    acaoEmVoo.current = true
    setApagando(movimento.id)
    setFalhaDeAcao(null)
    try {
      await deleteMovimentoCripto(movimento.id)
      setConfirmando(null)
      setVersao((n) => n + 1)
      aoMudar()
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      setConfirmando(null)
      if (erro instanceof CriptoNaoEncontradoError) {
        setFalhaDeAcao('Esse movimento não existe mais.')
        setVersao((n) => n + 1)
        return
      }
      setFalhaDeAcao(mensagemDeEscrita(erro, 'Não foi possível apagar o movimento. Tente de novo.'))
    } finally {
      acaoEmVoo.current = false
      setApagando(null)
    }
  }

  return (
    <section aria-labelledby="titulo-cripto">
      <header className="pagina-topo">
        <h3 id="titulo-cripto">Cripto</h3>
        <div className="topo-botoes">
          <button type="button" className="discreto" onClick={() => setAbertura({ tipo: 'ativo' })}>
            Novo ativo
          </button>
          {podeMovimentar && (
            <button type="button" className="primario" onClick={() => setAbertura({ tipo: 'movimento' })}>
              <IconeMais />
              Novo movimento
            </button>
          )}
        </div>
      </header>
      <p className="aviso-cripto">
        O Ballast só guarda o que você digita. Ele nunca pede frase de recuperação (seed) nem chave privada.
      </p>
      {falhaDeAcao !== null && (
        <p role="alert" className="erro">
          {falhaDeAcao}
        </p>
      )}

      <h3 className="titulo-cartao">Posições</h3>
      {contas.length === 0 && (
        <EstadoVazio
          titulo="Nenhuma conta cripto."
          dica="Crie uma conta do tipo Cripto na aba Contas para registrar compras e vendas."
        />
      )}
      {contas.length > 0 && ativos.tipo === 'ok' && ativos.dados.length === 0 && (
        <EstadoVazio
          titulo="Nenhum ativo cadastrado."
          dica="Cadastre o ativo pela rede e pelo contrato, não só pelo símbolo."
          acao={
            <button type="button" className="primario" onClick={() => setAbertura({ tipo: 'ativo' })}>
              Cadastrar o primeiro ativo
            </button>
          }
        />
      )}
      {posicoes.tipo === 'carregando' && <Carregando texto="Carregando posições..." />}
      {posicoes.tipo === 'erro' && (
        <p role="alert" className="erro">
          {posicoes.mensagem}
        </p>
      )}
      {posicoes.tipo === 'ok' && posicoes.dados.posicoes.length === 0 && podeMovimentar && (
        <EstadoVazio titulo="Nenhuma posição nesta data." dica="Registre uma compra para começar." />
      )}
      {posicoes.tipo === 'ok' && posicoes.dados.posicoes.length > 0 && (
        <ul className="lancamentos lista-cartao" aria-label="Posições">
          {posicoes.dados.posicoes.map((posicao) => {
            const ativo = ativosPorId.get(posicao.ativoId)
            return (
              <li key={`${posicao.contaId}-${posicao.ativoId}`}>
                <span className="item-icone">{ativo?.simbolo.slice(0, 3) ?? '?'}</span>
                <div className="item-corpo">
                  <strong className="item-titulo">
                    {formatarDecimal(posicao.quantidade)} {ativo?.simbolo ?? ''}
                  </strong>
                  <span className="item-meta">
                    {rotuloDoAtivo(ativo)} · {nomesDeConta.get(posicao.contaId) ?? 'Conta'}
                  </span>
                  <small>Compras menos vendas {formatarCentavos(posicao.custoCentavos)}</small>
                  {posicao.cotacao === null ? (
                    <div className="item-selos">
                      <Selo tom="neutro">Sem cotação</Selo>
                    </div>
                  ) : (
                    <small>
                      Cotação R$ {formatarDecimal(posicao.cotacao.preco)} em{' '}
                      {formatarDiaMes(diaEmSaoPaulo(posicao.cotacao.momento))}
                    </small>
                  )}
                </div>
                <div className="item-lado">
                  <span className="item-valor">
                    {posicao.valorCentavos === null ? '—' : formatarCentavos(posicao.valorCentavos)}
                  </span>
                  {ativo !== undefined && (
                    <div className="item-botoes">
                      <button
                        type="button"
                        className="pequeno"
                        aria-label={`Informar cotação de ${ativo.simbolo}`}
                        onClick={() => setAbertura({ tipo: 'cotacao', ativo })}
                      >
                        Cotação
                      </button>
                    </div>
                  )}
                </div>
              </li>
            )
          })}
        </ul>
      )}

      <h3 className="titulo-cartao">Movimentos</h3>
      {movimentos.tipo === 'carregando' && <Carregando texto="Carregando movimentos..." />}
      {movimentos.tipo === 'erro' && (
        <p role="alert" className="erro">
          {movimentos.mensagem}
        </p>
      )}
      {movimentos.tipo === 'ok' && movimentos.dados.length === 0 && <p>Nenhum movimento ainda.</p>}
      {movimentos.tipo === 'ok' && movimentos.dados.length > 0 && (
        <ul className="lancamentos lista-cartao" aria-label="Movimentos">
          {movimentos.dados.map((movimento) => {
            const ativo = ativosPorId.get(movimento.ativoId)
            const descricao = `${movimento.tipo === 'compra' ? 'Compra' : 'Venda'} de ${formatarDecimal(movimento.quantidade)} ${ativo?.simbolo ?? ''}`
            return (
              <li key={movimento.id}>
                <span className={`item-icone ${movimento.tipo === 'compra' ? 'despesa' : 'receita'}`}>
                  {movimento.tipo === 'compra' ? 'C' : 'V'}
                </span>
                <div className="item-corpo">
                  <strong className="item-titulo">{descricao}</strong>
                  <span className="item-meta">
                    {formatarDataCompleta(movimento.data)} · {nomesDeConta.get(movimento.contaId) ?? 'Conta arquivada'}
                  </span>
                </div>
                <div className="item-lado">
                  <span className="item-valor">{formatarCentavos(movimento.custoCentavos)}</span>
                  {confirmando === movimento.id ? (
                    <div className="item-botoes">
                      <button
                        type="button"
                        className="pequeno perigo"
                        disabled={apagando !== null}
                        onClick={() => void apagar(movimento)}
                      >
                        {apagando === movimento.id ? 'Apagando...' : 'Confirmar'}
                      </button>
                      <button type="button" className="pequeno discreto" onClick={() => setConfirmando(null)}>
                        Manter
                      </button>
                    </div>
                  ) : (
                    <div className="item-botoes">
                      <button
                        type="button"
                        className="pequeno"
                        aria-label={`Apagar ${descricao}`}
                        disabled={apagando !== null}
                        onClick={() => {
                          setFalhaDeAcao(null)
                          setConfirmando(movimento.id)
                        }}
                      >
                        Apagar
                      </button>
                    </div>
                  )}
                </div>
              </li>
            )
          })}
        </ul>
      )}

      <h3 className="titulo-cartao">Ativos</h3>
      {ativos.tipo === 'carregando' && <Carregando texto="Carregando ativos..." linhas={2} />}
      {ativos.tipo === 'erro' && (
        <p role="alert" className="erro">
          {ativos.mensagem}
        </p>
      )}
      {ativos.tipo === 'ok' && ativos.dados.length > 0 && (
        <ul className="lancamentos lista-cartao" aria-label="Ativos">
          {ativos.dados.map((ativo) => (
            <li key={ativo.id}>
              <span className="item-icone">{ativo.simbolo.slice(0, 3)}</span>
              <div className="item-corpo">
                <strong className="item-titulo">{ativo.nome ?? ativo.simbolo}</strong>
                <span className="item-meta">
                  {rotuloDoAtivo(ativo)} · {ativo.contrato === null ? 'nativo' : 'token'}
                </span>
                {ativo.contrato !== null && <small className="contrato">{ativo.contrato}</small>}
              </div>
              <div className="item-lado">
                <div className="item-botoes">
                  <button
                    type="button"
                    className="pequeno"
                    aria-label={`Informar cotação de ${ativo.simbolo}`}
                    onClick={() => setAbertura({ tipo: 'cotacao', ativo })}
                  >
                    Cotação
                  </button>
                </div>
              </div>
            </li>
          ))}
        </ul>
      )}

      {abertura?.tipo === 'ativo' && (
        <Painel titulo="Novo ativo" aoFechar={() => setAbertura(null)}>
          <NovoAtivo aoCriar={depoisDeMudar} aoSessaoExpirar={aoSessaoExpirar} />
        </Painel>
      )}
      {abertura?.tipo === 'movimento' && podeMovimentar && (
        <Painel titulo="Novo movimento" aoFechar={() => setAbertura(null)}>
          <NovoMovimento
            contas={contas}
            ativos={listaDeAtivos}
            aoCriar={depoisDeMudar}
            aoSessaoExpirar={aoSessaoExpirar}
          />
        </Painel>
      )}
      {abertura?.tipo === 'cotacao' && (
        <Painel titulo={`Cotação de ${abertura.ativo.simbolo}`} aoFechar={() => setAbertura(null)}>
          <NovaCotacao ativo={abertura.ativo} aoSalvar={depoisDeMudar} aoSessaoExpirar={aoSessaoExpirar} />
        </Painel>
      )}
    </section>
  )
}
