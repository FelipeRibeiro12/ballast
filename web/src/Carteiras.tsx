import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import {
  InvestimentoNaoEncontradoError,
  LimiteTentativasError,
  SessaoExpiradaError,
  deleteCarteira,
  fetchCarteiras,
  postCarteira,
  postDesafioPosse,
  postSincronizarCarteira,
  postVerificarPosse,
  putAtivoCripto,
  putCustoMovimento,
} from './api'
import type { AtivoCripto, Carteira, Conta, MovimentoCripto } from './api'
import { formatarDecimal } from './decimais'
import { diaEmSaoPaulo, formatarDataCompleta, horaEmSaoPaulo } from './datas'
import { lerValorPositivo } from './dinheiro'
import type { CarteiraDescoberta, FamiliaDeCarteira } from './conexaoDeCarteira'
import { EnderecoDiferenteError, ehRecusaDoUsuario } from './errosDeCarteira'
import { MENSAGEM_LIMITE, carregar, mensagemDeEscrita } from './leitura'
import type { Estado } from './leitura'
import Painel from './Painel'
import { Carregando, EstadoVazio, Selo } from './ui'

type RedeDaCarteira = Carteira['rede']

const REDES_DA_CARTEIRA: RedeDaCarteira[] = ['ethereum', 'base', 'solana']

const ROTULO_DA_REDE: Record<RedeDaCarteira, string> = {
  ethereum: 'Ethereum',
  base: 'Base',
  solana: 'Solana',
}

// Acima disso nenhum endereço das três redes cabe; o servidor valida o formato de verdade.
const TAMANHO_MAXIMO_DO_ENDERECO = 128

function abreviarEndereco(endereco: string): string {
  if (endereco.length <= 14) return endereco
  return `${endereco.slice(0, 6)}…${endereco.slice(-4)}`
}

function descreverInstante(instante: string): string {
  return `${formatarDataCompleta(diaEmSaoPaulo(instante))} às ${horaEmSaoPaulo(instante)}`
}

function familiaDaRede(rede: RedeDaCarteira): FamiliaDeCarteira {
  return rede === 'solana' ? 'solana' : 'evm'
}

// No EVM a mesma conta vale em Ethereum e Base: mantém a escolha do usuário entre as duas.
function redeAposConectar(familia: FamiliaDeCarteira, atual: RedeDaCarteira): RedeDaCarteira {
  if (familia === 'solana') return 'solana'
  return atual === 'solana' ? 'ethereum' : atual
}

function mensagemDeFalha(erro: unknown, padrao: string): string {
  if (ehRecusaDoUsuario(erro)) return 'Você recusou o pedido na carteira. Nada foi feito.'
  if (erro instanceof EnderecoDiferenteError) {
    return 'A conta ativa na carteira não é a deste endereço. Troque de conta na carteira e tente de novo.'
  }
  if (erro instanceof LimiteTentativasError) return MENSAGEM_LIMITE
  return mensagemDeEscrita(erro, padrao)
}

type EstadoDaDescoberta =
  | { tipo: 'carregando' }
  | { tipo: 'erro' }
  | { tipo: 'ok'; carteiras: CarteiraDescoberta[] }

const ROTULO_DA_FAMILIA: Record<FamiliaDeCarteira, string> = {
  evm: 'Ethereum e Base',
  solana: 'Solana',
}

// O módulo com wagmi, viem e wallet-standard só é baixado quando este componente aparece, ou seja,
// depois do clique do usuário (ADR-072).
function EscolhaDeCarteira({
  familia,
  mensagemVazia,
  ocupada,
  aoEscolher,
  aoCancelar,
}: {
  familia: FamiliaDeCarteira | null
  mensagemVazia: string
  ocupada: boolean
  aoEscolher: (carteira: CarteiraDescoberta) => void
  aoCancelar: () => void
}) {
  const [estado, setEstado] = useState<EstadoDaDescoberta>({ tipo: 'carregando' })

  useEffect(() => {
    let vale = true
    import('./conexaoDeCarteira')
      .then((modulo) => modulo.descobrirCarteiras())
      .then(
        (encontradas) => {
          if (vale) {
            setEstado({
              tipo: 'ok',
              carteiras: encontradas.filter((item) => familia === null || item.familia === familia),
            })
          }
        },
        () => {
          if (vale) setEstado({ tipo: 'erro' })
        },
      )
    return () => {
      vale = false
    }
  }, [familia])

  return (
    <div className="escolha-carteira">
      {estado.tipo === 'carregando' && <Carregando texto="Procurando carteiras no navegador..." linhas={1} />}
      {estado.tipo === 'erro' && (
        <p role="alert" className="erro">
          Não foi possível procurar as carteiras no navegador. Digite o endereço, funciona igual.
        </p>
      )}
      {estado.tipo === 'ok' && estado.carteiras.length === 0 && <p role="status">{mensagemVazia}</p>}
      {estado.tipo === 'ok' && estado.carteiras.length > 0 && (
        <ul aria-label="Carteiras encontradas no navegador">
          {estado.carteiras.map((item) => (
            <li key={item.chave}>
              <button type="button" className="pequeno" disabled={ocupada} onClick={() => aoEscolher(item)}>
                {item.nome} · {ROTULO_DA_FAMILIA[item.familia]}
              </button>
            </li>
          ))}
        </ul>
      )}
      <button type="button" className="pequeno discreto" onClick={aoCancelar}>
        Cancelar
      </button>
    </div>
  )
}

function NovaCarteira({
  contas,
  aoCriar,
  aoSessaoExpirar,
}: {
  contas: Conta[]
  aoCriar: () => void
  aoSessaoExpirar: () => void
}) {
  const [contaId, setContaId] = useState(contas[0]?.id ?? '')
  const [rede, setRede] = useState<RedeDaCarteira>('ethereum')
  const [endereco, setEndereco] = useState('')
  const [rotulo, setRotulo] = useState('')
  const [enviando, setEnviando] = useState(false)
  const [mensagem, setMensagem] = useState<string | null>(null)
  const [escolhendo, setEscolhendo] = useState(false)
  const [conectando, setConectando] = useState(false)
  const [avisoDaCarteira, setAvisoDaCarteira] = useState<string | null>(null)
  // O state só atualiza no próximo render; o ref barra o segundo submit síncrono.
  const emVoo = useRef(false)

  // Só preenche o formulário: quem salva é o envio, que continua sendo do usuário.
  async function conectar(carteira: CarteiraDescoberta): Promise<void> {
    setConectando(true)
    setAvisoDaCarteira(null)
    try {
      const modulo = await import('./conexaoDeCarteira')
      const enderecoLido = await modulo.conectarCarteira(carteira)
      setEndereco(enderecoLido)
      setRede((atual) => redeAposConectar(carteira.familia, atual))
      setEscolhendo(false)
    } catch (erro) {
      setAvisoDaCarteira(
        ehRecusaDoUsuario(erro)
          ? 'Você recusou o pedido na carteira. Nada foi preenchido.'
          : 'Não foi possível ler a carteira. Digite o endereço, funciona igual.',
      )
    } finally {
      setConectando(false)
    }
  }

  async function enviar(evento: FormEvent<HTMLFormElement>): Promise<void> {
    evento.preventDefault()
    if (emVoo.current) return
    const enderecoLimpo = endereco.trim()
    if (enderecoLimpo === '' || enderecoLimpo.length > TAMANHO_MAXIMO_DO_ENDERECO) {
      setMensagem('Informe o endereço público da carteira.')
      return
    }
    emVoo.current = true
    setEnviando(true)
    setMensagem(null)
    try {
      await postCarteira({
        contaId,
        rede,
        endereco: enderecoLimpo,
        rotulo: rotulo.trim() === '' ? null : rotulo.trim(),
      })
      aoCriar()
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      setMensagem(mensagemDeEscrita(erro, 'Não foi possível cadastrar a carteira. Tente de novo.'))
    } finally {
      emVoo.current = false
      setEnviando(false)
    }
  }

  return (
    <form onSubmit={(evento) => void enviar(evento)} aria-label="Nova carteira">
      <p className="aviso-cripto">
        O Ballast só observa o endereço público. Ele nunca pede seed, chave privada nem assinatura de transação.
      </p>
      <div className="campo">
        <label htmlFor="carteira-conta">Conta cripto</label>
        <select id="carteira-conta" value={contaId} required onChange={(evento) => setContaId(evento.target.value)}>
          {contas.map((conta) => (
            <option key={conta.id} value={conta.id}>
              {conta.nome}
            </option>
          ))}
        </select>
      </div>
      <div className="campo">
        <label htmlFor="carteira-rede">Rede</label>
        <select
          id="carteira-rede"
          value={rede}
          onChange={(evento) => {
            const escolhida = REDES_DA_CARTEIRA.find((opcao) => opcao === evento.target.value)
            if (escolhida !== undefined) setRede(escolhida)
          }}
        >
          {REDES_DA_CARTEIRA.map((opcao) => (
            <option key={opcao} value={opcao}>
              {ROTULO_DA_REDE[opcao]}
            </option>
          ))}
        </select>
      </div>
      <div className="campo">
        <label htmlFor="carteira-endereco">Endereço</label>
        <input
          id="carteira-endereco"
          type="text"
          autoComplete="off"
          autoCapitalize="off"
          spellCheck={false}
          maxLength={TAMANHO_MAXIMO_DO_ENDERECO}
          required
          value={endereco}
          onChange={(evento) => setEndereco(evento.target.value)}
        />
        <small>Digite ou cole o endereço. Não precisa de extensão nem de conexão com a carteira.</small>
        {!escolhendo && (
          <button
            type="button"
            className="pequeno"
            onClick={() => {
              setAvisoDaCarteira(null)
              setEscolhendo(true)
            }}
          >
            Conectar carteira
          </button>
        )}
        {escolhendo && (
          <EscolhaDeCarteira
            familia={null}
            mensagemVazia="Nenhuma carteira encontrada no navegador. Digite o endereço abaixo, funciona igual."
            ocupada={conectando}
            aoEscolher={(carteira) => void conectar(carteira)}
            aoCancelar={() => setEscolhendo(false)}
          />
        )}
        <small>A conexão só lê o endereço público. O app nunca pede seed, chave privada nem assinatura de transação.</small>
        {avisoDaCarteira !== null && (
          <p role="status" className="aviso-cripto">
            {avisoDaCarteira}
          </p>
        )}
      </div>
      <div className="campo">
        <label htmlFor="carteira-rotulo">Rótulo (opcional)</label>
        <input
          id="carteira-rotulo"
          type="text"
          autoComplete="off"
          maxLength={60}
          value={rotulo}
          onChange={(evento) => setRotulo(evento.target.value)}
        />
      </div>
      {mensagem !== null && (
        <p role="alert" className="erro">
          {mensagem}
        </p>
      )}
      <button type="submit" className="primario" disabled={enviando}>
        {enviando ? 'Salvando...' : 'Observar carteira'}
      </button>
    </form>
  )
}

function CustoPendente({
  movimento,
  simbolo,
  aoInformar,
  aoSessaoExpirar,
}: {
  movimento: MovimentoCripto
  simbolo: string
  aoInformar: () => void
  aoSessaoExpirar: () => void
}) {
  const [valor, setValor] = useState('')
  const [enviando, setEnviando] = useState(false)
  const [mensagem, setMensagem] = useState<string | null>(null)
  const emVoo = useRef(false)

  const descricao = `${movimento.tipo === 'entrada' ? 'entrada' : 'saída'} de ${formatarDecimal(movimento.quantidade)} ${simbolo} em ${formatarDataCompleta(movimento.data)}`

  async function enviar(evento: FormEvent<HTMLFormElement>): Promise<void> {
    evento.preventDefault()
    if (emVoo.current) return
    const custoUsdCentavos = lerValorPositivo(valor)
    if (custoUsdCentavos === null) {
      setMensagem('Valor inválido. Use o formato 1.234,56, maior que zero.')
      return
    }
    emVoo.current = true
    setEnviando(true)
    setMensagem(null)
    try {
      await putCustoMovimento(movimento.id, custoUsdCentavos)
      aoInformar()
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      if (erro instanceof InvestimentoNaoEncontradoError) {
        setMensagem('Esse movimento não existe mais.')
        return
      }
      setMensagem(mensagemDeEscrita(erro, 'Não foi possível salvar o custo. Tente de novo.'))
    } finally {
      emVoo.current = false
      setEnviando(false)
    }
  }

  return (
    <form onSubmit={(evento) => void enviar(evento)} aria-label={`Custo da ${descricao}`}>
      <label htmlFor={`custo-${movimento.id}`}>Custo em dólar da {descricao}</label>
      <input
        id={`custo-${movimento.id}`}
        type="text"
        inputMode="decimal"
        autoComplete="off"
        placeholder="0,00"
        value={valor}
        onChange={(evento) => setValor(evento.target.value)}
      />
      <small>O custo só pode ser informado uma vez.</small>
      {mensagem !== null && (
        <p role="alert" className="erro">
          {mensagem}
        </p>
      )}
      <button type="submit" className="pequeno" disabled={enviando}>
        {enviando ? 'Salvando...' : 'Salvar custo'}
      </button>
    </form>
  )
}

function CartaoDaCarteira({
  carteira,
  nomeDaConta,
  ativos,
  movimentos,
  aoAtualizar,
  aoMudar,
  aoSessaoExpirar,
}: {
  carteira: Carteira
  nomeDaConta: string
  ativos: AtivoCripto[]
  movimentos: MovimentoCripto[]
  aoAtualizar: (carteira: Carteira) => void
  aoMudar: () => void
  aoSessaoExpirar: () => void
}) {
  const [completo, setCompleto] = useState(false)
  const [confirmando, setConfirmando] = useState(false)
  const [provando, setProvando] = useState(false)
  const [ocupado, setOcupado] = useState(false)
  const [mensagem, setMensagem] = useState<string | null>(null)
  const emVoo = useRef(false)

  async function executar(acao: () => Promise<void>, padrao: string): Promise<void> {
    if (emVoo.current) return
    emVoo.current = true
    setOcupado(true)
    setMensagem(null)
    try {
      await acao()
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      setConfirmando(false)
      if (erro instanceof InvestimentoNaoEncontradoError) {
        setMensagem('Essa carteira não existe mais.')
        aoMudar()
        return
      }
      setMensagem(mensagemDeFalha(erro, padrao))
    } finally {
      emVoo.current = false
      setOcupado(false)
    }
  }

  const titulo = carteira.rotulo ?? `Carteira ${ROTULO_DA_REDE[carteira.rede]}`
  const pendentes = movimentos.filter(
    (movimento) =>
      movimento.origem === 'sincronizada' &&
      movimento.custoUsdCentavos === null &&
      movimento.contaId === carteira.contaId,
  )

  return (
    <li>
      <div className="item-corpo">
        <strong className="item-titulo">{titulo}</strong>
        <span className="item-meta">
          {ROTULO_DA_REDE[carteira.rede]} · {nomeDaConta}
        </span>
        <small className="contrato" title={carteira.endereco}>
          {completo ? carteira.endereco : abreviarEndereco(carteira.endereco)}
        </small>
        <div className="item-botoes">
          <button
            type="button"
            className="pequeno discreto"
            aria-pressed={completo}
            aria-label={`${completo ? 'Ocultar' : 'Mostrar'} endereço completo de ${titulo}`}
            onClick={() => setCompleto((atual) => !atual)}
          >
            {completo ? 'Ocultar endereço' : 'Endereço completo'}
          </button>
          {carteira.verificadaEm === null && (
            <button
              type="button"
              className="pequeno discreto"
              aria-expanded={provando}
              aria-label={`Provar posse de ${titulo}`}
              onClick={() => setProvando((atual) => !atual)}
            >
              Provar posse
            </button>
          )}
        </div>
        {carteira.verificadaEm !== null && (
          <div className="item-selos">
            <Selo tom="paga">Posse verificada</Selo>
          </div>
        )}
        {provando && carteira.verificadaEm === null && (
          <div role="group" aria-label={`Prova de posse de ${titulo}`}>
            <p className="aviso-cripto">
              Opcional. Você assina um texto na sua carteira para mostrar que o endereço é seu. Assinar a mensagem é
              grátis e não é transação: nada sai da carteira. O Ballast nunca pede seed nem chave privada.
            </p>
            <EscolhaDeCarteira
              familia={familiaDaRede(carteira.rede)}
              mensagemVazia="Nenhuma carteira compatível encontrada no navegador. A prova de posse é opcional."
              ocupada={ocupado}
              aoEscolher={(escolhida) =>
                void executar(async () => {
                  const modulo = await import('./conexaoDeCarteira')
                  const desafio = await postDesafioPosse(carteira.id)
                  const assinatura = await modulo.assinarMensagem(escolhida, carteira.endereco, desafio.mensagem)
                  aoAtualizar(await postVerificarPosse(carteira.id, { nonce: desafio.nonce, assinatura }))
                  setProvando(false)
                }, 'Não foi possível provar a posse. Tente de novo.')
              }
              aoCancelar={() => setProvando(false)}
            />
          </div>
        )}
        <small>
          {carteira.sincronizadaEm === null
            ? 'Ainda não lida'
            : `Última leitura em ${descreverInstante(carteira.sincronizadaEm)}`}
        </small>
        {carteira.leituraFalhou && (
          <p className="aviso-cripto">
            {carteira.sincronizadaEm === null
              ? 'Não consegui atualizar agora. Ainda não há leitura anterior.'
              : `Não consegui atualizar agora, mostrando o saldo de ${descreverInstante(carteira.sincronizadaEm)}.`}
          </p>
        )}
        {carteira.saldos.length === 0 && carteira.sincronizadaEm !== null && <small>Nenhum saldo nesta carteira.</small>}
        {carteira.saldos.length > 0 && (
          <ul aria-label={`Saldos de ${titulo}`}>
            {carteira.saldos.map((saldo) => {
              const ativo = ativos.find((candidato) => candidato.id === saldo.ativoId)
              const doSaldo = pendentes.filter((movimento) => movimento.ativoId === saldo.ativoId)
              return (
                <li key={saldo.ativoId}>
                  <strong>
                    {formatarDecimal(saldo.quantidade)} {saldo.simbolo}
                  </strong>{' '}
                  <span className="item-meta">{ROTULO_DA_REDE[carteira.rede]}</span>
                  {!saldo.confiavel && (
                    <>
                      <div className="item-selos">
                        <Selo tom="neutro">Não confiável</Selo>
                      </div>
                      <small>
                        Este ativo veio da leitura da rede e fica fora do patrimônio até você promover.
                      </small>
                      {ativo !== undefined && (
                        <button
                          type="button"
                          className="pequeno"
                          disabled={ocupado}
                          aria-label={`Promover ${saldo.simbolo} para confiável`}
                          onClick={() =>
                            void executar(async () => {
                              await putAtivoCripto(ativo.id, {
                                simbolo: ativo.simbolo,
                                decimais: ativo.decimais,
                                nome: ativo.nome,
                                confiavel: true,
                              })
                              aoMudar()
                            }, 'Não foi possível promover o ativo. Tente de novo.')
                          }
                        >
                          Promover
                        </button>
                      )}
                    </>
                  )}
                  {saldo.custoPendente && (
                    <>
                      <p className="aviso-cripto">
                        Falta o custo em dólar de {saldo.simbolo}. Sem ele, o custo médio e o ganho ficam pendentes.
                      </p>
                      {doSaldo.map((movimento) => (
                        <CustoPendente
                          key={movimento.id}
                          movimento={movimento}
                          simbolo={saldo.simbolo}
                          aoInformar={aoMudar}
                          aoSessaoExpirar={aoSessaoExpirar}
                        />
                      ))}
                    </>
                  )}
                </li>
              )
            })}
          </ul>
        )}
        {mensagem !== null && (
          <p role="alert" className="erro">
            {mensagem}
          </p>
        )}
      </div>
      <div className="item-lado">
        {confirmando ? (
          <>
            <small>Remove também as entradas e saídas sincronizadas dela. Os movimentos digitados ficam.</small>
            <div className="item-botoes">
              <button
                type="button"
                className="pequeno perigo"
                disabled={ocupado}
                onClick={() =>
                  void executar(async () => {
                    await deleteCarteira(carteira.id)
                    aoMudar()
                  }, 'Não foi possível remover a carteira. Tente de novo.')
                }
              >
                {ocupado ? 'Removendo...' : `Confirmar remoção de ${titulo}`}
              </button>
              <button type="button" className="pequeno discreto" onClick={() => setConfirmando(false)}>
                Manter
              </button>
            </div>
          </>
        ) : (
          <div className="item-botoes">
            <button
              type="button"
              className="pequeno"
              disabled={ocupado}
              aria-label={`Sincronizar ${titulo}`}
              onClick={() =>
                void executar(async () => {
                  aoAtualizar(await postSincronizarCarteira(carteira.id))
                  aoMudar()
                }, 'Não foi possível sincronizar. Tente de novo.')
              }
            >
              {ocupado ? 'Lendo...' : 'Sincronizar'}
            </button>
            <button
              type="button"
              className="pequeno"
              disabled={ocupado}
              aria-label={`Remover ${titulo}`}
              onClick={() => {
                setMensagem(null)
                setConfirmando(true)
              }}
            >
              Remover
            </button>
          </div>
        )}
      </div>
    </li>
  )
}

// Cadastro e lista das carteiras observadas (ADR-071). O endereço só aparece no corpo das chamadas:
// nunca em URL, console ou storage.
export default function Carteiras({
  contas,
  ativos,
  movimentos,
  aoMudar,
  aoSessaoExpirar,
}: {
  contas: Conta[]
  ativos: AtivoCripto[]
  movimentos: MovimentoCripto[]
  aoMudar: () => void
  aoSessaoExpirar: () => void
}) {
  const [versao, setVersao] = useState(0)
  const [carteiras, setCarteiras] = useState<Estado<Carteira[]>>({ tipo: 'carregando' })
  const [abrindo, setAbrindo] = useState(false)

  useEffect(() => {
    let vale = true
    carregar(fetchCarteiras, 'as carteiras', aoSessaoExpirar, setCarteiras, () => vale)
    return () => {
      vale = false
    }
  }, [versao, aoSessaoExpirar])

  const contasAtivas = contas.filter((conta) => !conta.arquivada)
  const nomesDeConta = new Map(contas.map((conta) => [conta.id, conta.nome]))

  function depoisDeMudar(): void {
    setAbrindo(false)
    setVersao((n) => n + 1)
    aoMudar()
  }

  function guardarCarteira(atualizada: Carteira): void {
    setCarteiras((atual) =>
      atual.tipo === 'ok'
        ? { tipo: 'ok', dados: atual.dados.map((item) => (item.id === atualizada.id ? atualizada : item)) }
        : atual,
    )
  }

  return (
    <>
      <header className="pagina-topo">
        <h3 className="titulo-cartao">Carteiras observadas</h3>
        {contasAtivas.length > 0 && (
          <button type="button" className="discreto" onClick={() => setAbrindo(true)}>
            Observar carteira
          </button>
        )}
      </header>
      <p className="aviso-cripto">
        O Ballast só observa o endereço público. Ele nunca pede seed, chave privada nem assinatura de transação.
      </p>
      {carteiras.tipo === 'carregando' && <Carregando texto="Carregando carteiras..." linhas={2} />}
      {carteiras.tipo === 'erro' && (
        <p role="alert" className="erro">
          {carteiras.mensagem}
        </p>
      )}
      {carteiras.tipo === 'ok' && carteiras.dados.length === 0 && (
        <EstadoVazio
          titulo="Nenhuma carteira observada."
          dica={
            contasAtivas.length === 0
              ? 'Crie uma conta do tipo Cripto na aba Contas para observar um endereço.'
              : 'Digite ou cole um endereço público para acompanhar o saldo.'
          }
        />
      )}
      {carteiras.tipo === 'ok' && carteiras.dados.length > 0 && (
        <ul className="lancamentos lista-cartao" aria-label="Carteiras">
          {carteiras.dados.map((carteira) => (
            <CartaoDaCarteira
              key={carteira.id}
              carteira={carteira}
              nomeDaConta={nomesDeConta.get(carteira.contaId) ?? 'Conta'}
              ativos={ativos}
              movimentos={movimentos}
              aoAtualizar={guardarCarteira}
              aoMudar={depoisDeMudar}
              aoSessaoExpirar={aoSessaoExpirar}
            />
          ))}
        </ul>
      )}
      {abrindo && contasAtivas.length > 0 && (
        <Painel titulo="Observar carteira" aoFechar={() => setAbrindo(false)}>
          <NovaCarteira contas={contasAtivas} aoCriar={depoisDeMudar} aoSessaoExpirar={aoSessaoExpirar} />
        </Painel>
      )}
    </>
  )
}
