import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import {
  CredenciaisInvalidasError,
  EmailJaCadastradoError,
  EntradaInvalidaError,
  LimiteTentativasError,
  fetchHealthz,
  fetchUsuarioAtual,
  postLogin,
  postCadastro,
} from './api'
import type { Conta, Usuario } from './api'
import AvisoDeAtualizacao from './AvisoDeAtualizacao'
import Contas from './Contas'
import { Logo } from './icones'
import Inicio from './Inicio'
import Lancamentos from './Lancamentos'
import Recorrencias from './Recorrencias'
import { useRota } from './rota'
import Investimentos from './Investimentos'
import Metas from './Metas'
import Orcamento from './Orcamento'
import Relatorios from './Relatorios'
import Topo from './Topo'
import { Carregando } from './ui'

type EstadoSessao =
  | { tipo: 'carregando' }
  | { tipo: 'erro' }
  | { tipo: 'anonimo' }
  | { tipo: 'logado'; usuario: Usuario }

type EstadoHealthz =
  | { tipo: 'carregando' }
  | { tipo: 'ok' }
  | { tipo: 'erro' }

function mensagemDeLogin(erro: unknown): string {
  if (erro instanceof CredenciaisInvalidasError) return 'E-mail ou senha incorretos.'
  if (erro instanceof LimiteTentativasError) {
    if (erro.segundos === null) return 'Muitas tentativas. Tente de novo em instantes.'
    const unidade = erro.segundos === 1 ? 'segundo' : 'segundos'
    return `Muitas tentativas. Tente de novo em ${erro.segundos} ${unidade}.`
  }
  return 'Não foi possível entrar. Tente de novo.'
}

// Lida na renderização para o teste poder trocar a flag; só a string exata liga (ADR-022).
function registroAberto(): boolean {
  return import.meta.env.VITE_REGISTRO_ABERTO === 'true'
}

function mensagemDeCadastro(erro: unknown): string {
  if (erro instanceof EmailJaCadastradoError) return 'Este e-mail já tem conta.'
  if (erro instanceof EntradaInvalidaError && erro.mensagemServidor !== null) {
    return erro.mensagemServidor
  }
  return 'Não foi possível criar a conta. Tente de novo.'
}

function Login({
  aoEntrar,
  aoCriarConta,
  aviso,
}: {
  aoEntrar: (usuario: Usuario) => void
  aoCriarConta: (() => void) | null
  aviso: string | null
}) {
  const [email, setEmail] = useState('')
  const [senha, setSenha] = useState('')
  const [enviando, setEnviando] = useState(false)
  const [mensagem, setMensagem] = useState<string | null>(null)
  const campoSenha = useRef<HTMLInputElement>(null)
  // O state só atualiza no próximo render; o ref barra o segundo submit síncrono.
  const emVoo = useRef(false)

  async function enviar(evento: FormEvent<HTMLFormElement>): Promise<void> {
    evento.preventDefault()
    if (emVoo.current) return
    emVoo.current = true
    setEnviando(true)
    setMensagem(null)
    try {
      const usuario = await postLogin({ email, senha })
      aoEntrar(usuario)
    } catch (erro) {
      setMensagem(mensagemDeLogin(erro))
      setSenha('')
      campoSenha.current?.focus()
    } finally {
      emVoo.current = false
      setEnviando(false)
    }
  }

  return (
    <form onSubmit={(evento) => void enviar(evento)} aria-label="Entrar">
      <div className="campo">
        <label htmlFor="email">E-mail</label>
        <input
          id="email"
          type="email"
          autoComplete="username"
          maxLength={254}
          required
          value={email}
          onChange={(evento) => setEmail(evento.target.value)}
        />
      </div>
      <div className="campo">
        <label htmlFor="senha">Senha</label>
        <input
          id="senha"
          ref={campoSenha}
          type="password"
          autoComplete="current-password"
          maxLength={128}
          required
          value={senha}
          aria-describedby={mensagem === null ? undefined : 'erro-login'}
          onChange={(evento) => setSenha(evento.target.value)}
        />
      </div>
      {aviso !== null && <p role="status">{aviso}</p>}
      {mensagem !== null && (
        <p id="erro-login" role="alert" className="erro">
          {mensagem}
        </p>
      )}
      <button type="submit" disabled={enviando}>
        {enviando ? 'Entrando...' : 'Entrar'}
      </button>
      {aoCriarConta !== null && (
        <button type="button" className="discreto" onClick={aoCriarConta}>
          Criar conta
        </button>
      )}
    </form>
  )
}

function Cadastro({
  aoEntrar,
  aoContaCriadaSemLogin,
  aoVoltar,
}: {
  aoEntrar: (usuario: Usuario) => void
  aoContaCriadaSemLogin: () => void
  aoVoltar: () => void
}) {
  const [nome, setNome] = useState('')
  const [email, setEmail] = useState('')
  const [senha, setSenha] = useState('')
  const [confirmacao, setConfirmacao] = useState('')
  const [enviando, setEnviando] = useState(false)
  const [mensagem, setMensagem] = useState<string | null>(null)
  const campoSenha = useRef<HTMLInputElement>(null)
  // O state só atualiza no próximo render; o ref barra o segundo submit síncrono.
  const emVoo = useRef(false)

  function recusar(texto: string): void {
    setMensagem(texto)
    setSenha('')
    setConfirmacao('')
    campoSenha.current?.focus()
  }

  async function enviar(evento: FormEvent<HTMLFormElement>): Promise<void> {
    evento.preventDefault()
    if (emVoo.current) return
    if (senha !== confirmacao) {
      recusar('As senhas não conferem.')
      return
    }
    emVoo.current = true
    setEnviando(true)
    setMensagem(null)
    try {
      await postCadastro(nome === '' ? { email, senha } : { email, senha, nome })
    } catch (erro) {
      recusar(mensagemDeCadastro(erro))
      emVoo.current = false
      setEnviando(false)
      return
    }
    // A conta já existe: falha no login não é falha de cadastro, então não mostra erro de cadastro.
    try {
      aoEntrar(await postLogin({ email, senha }))
    } catch {
      aoContaCriadaSemLogin()
    }
  }

  return (
    <form onSubmit={(evento) => void enviar(evento)} aria-label="Criar conta">
      <div className="campo">
        <label htmlFor="cadastro-nome">Nome (opcional)</label>
        <input
          id="cadastro-nome"
          type="text"
          autoComplete="name"
          maxLength={100}
          value={nome}
          onChange={(evento) => setNome(evento.target.value)}
        />
      </div>
      <div className="campo">
        <label htmlFor="cadastro-email">E-mail</label>
        <input
          id="cadastro-email"
          type="email"
          autoComplete="username"
          maxLength={254}
          required
          value={email}
          onChange={(evento) => setEmail(evento.target.value)}
        />
      </div>
      <div className="campo">
        <label htmlFor="cadastro-senha">Senha</label>
        <input
          id="cadastro-senha"
          ref={campoSenha}
          type="password"
          autoComplete="new-password"
          minLength={12}
          maxLength={128}
          required
          value={senha}
          aria-describedby={mensagem === null ? undefined : 'erro-cadastro'}
          onChange={(evento) => setSenha(evento.target.value)}
        />
      </div>
      <div className="campo">
        <label htmlFor="cadastro-confirmacao">Confirmar senha</label>
        <input
          id="cadastro-confirmacao"
          type="password"
          autoComplete="new-password"
          minLength={12}
          maxLength={128}
          required
          value={confirmacao}
          onChange={(evento) => setConfirmacao(evento.target.value)}
        />
      </div>
      {mensagem !== null && (
        <p id="erro-cadastro" role="alert" className="erro">
          {mensagem}
        </p>
      )}
      <button type="submit" disabled={enviando}>
        {enviando ? 'Criando...' : 'Criar conta'}
      </button>
      <button type="button" className="discreto" onClick={aoVoltar}>
        Já tenho conta
      </button>
    </form>
  )
}

function Rodape() {
  const [estado, setEstado] = useState<EstadoHealthz>({ tipo: 'carregando' })

  useEffect(() => {
    // Evita setState depois de desmontar (e no segundo disparo do StrictMode).
    let ativo = true
    fetchHealthz().then(
      () => {
        if (ativo) setEstado({ tipo: 'ok' })
      },
      () => {
        if (ativo) setEstado({ tipo: 'erro' })
      },
    )
    return () => {
      ativo = false
    }
  }, [])

  // Com a API bem não há o que dizer: só a falha ocupa espaço na tela.
  if (estado.tipo !== 'erro') return null
  return (
    <footer className="rodape">
      <p role="alert">Não foi possível falar com o servidor.</p>
    </footer>
  )
}

function App() {
  const [sessao, setSessao] = useState<EstadoSessao>({ tipo: 'carregando' })
  const [tentativa, setTentativa] = useState(0)
  const [telaAnonima, setTelaAnonima] = useState<'login' | 'cadastro'>('login')
  const [aviso, setAviso] = useState<string | null>(null)
  // Contadores para uma tela mandar a outra buscar de novo, sem biblioteca de estado.
  const [versaoDeSaldo, setVersaoDeSaldo] = useState(0)
  // Contas ativas vindas da tela de Contas, para os selects de Lançamentos não buscarem de novo.
  const [contasAtivas, setContasAtivas] = useState<Conta[] | null>(null)
  const [versaoDaLista, setVersaoDaLista] = useState(0)
  const [versaoDeRecorrencias, setVersaoDeRecorrencias] = useState(0)
  const [recorrenciasAtivas, setRecorrenciasAtivas] = useState<ReadonlySet<string>>(new Set())
  const [parcelasPorRecorrencia, setParcelasPorRecorrencia] = useState<ReadonlyMap<string, number>>(new Map())
  // Onde o Início abre espaço para as vencidas que a tela de Lançamentos desenha.
  const [slotDeVencidas, setSlotDeVencidas] = useState<HTMLElement | null>(null)
  const [rota, irPara] = useRota()
  // Conta cripto e de investimento não recebem lançamento (ADR-035, ADR-037): ficam fora dos selects e
  // aparecem só na aba Investimentos.
  // Memo porque as telas buscam de novo quando a lista de contas muda de identidade.
  const contasEmReais = useMemo(
    () => contasAtivas?.filter((conta) => conta.tipo !== 'cripto' && conta.tipo !== 'investimento') ?? null,
    [contasAtivas],
  )
  const contasCripto = useMemo(() => contasAtivas?.filter((conta) => conta.tipo === 'cripto') ?? [], [contasAtivas])
  const contasDeInvestimento = useMemo(
    () => contasAtivas?.filter((conta) => conta.tipo === 'investimento') ?? [],
    [contasAtivas],
  )

  useEffect(() => {
    let ativo = true
    fetchUsuarioAtual().then(
      (usuario) => {
        if (!ativo) return
        setSessao(usuario === null ? { tipo: 'anonimo' } : { tipo: 'logado', usuario })
      },
      () => {
        if (ativo) setSessao({ tipo: 'erro' })
      },
    )
    return () => {
      ativo = false
    }
  }, [tentativa])

  // Estável para não refazer a busca de contas a cada render do App.
  const aoSessaoExpirar = useCallback((): void => {
    setTelaAnonima('login')
    setAviso('Sua sessão acabou. Entre de novo.')
    setSessao({ tipo: 'anonimo' })
  }, [])

  // O painel de edição vive em Lançamentos: quem edita uma vencida no Início vai até lá.
  const irParaLancamentos = useCallback((): void => irPara('lancamentos'), [irPara])

  function tentarDeNovo(): void {
    setSessao({ tipo: 'carregando' })
    setTentativa((n) => n + 1)
  }

  const marca = (
    <div className="auth-marca">
      <Logo tamanho={48} />
      <h1>Ballast</h1>
      <p>Controle financeiro pessoal</p>
    </div>
  )

  if (sessao.tipo !== 'logado') {
    return (
      <main className="auth">
        <div className="auth-cartao">
          <AvisoDeAtualizacao />
          {marca}
          {sessao.tipo === 'carregando' && <Carregando texto="Carregando..." linhas={2} />}
          {sessao.tipo === 'erro' && (
            <div className="cartao">
              <p role="alert" className="erro">
                Não foi possível carregar sua sessão.
              </p>
              <button type="button" onClick={tentarDeNovo}>
                Tentar de novo
              </button>
            </div>
          )}
          {sessao.tipo === 'anonimo' && telaAnonima === 'login' && (
            <div className="cartao">
              <Login
                aoEntrar={(usuario) => setSessao({ tipo: 'logado', usuario })}
                aoCriarConta={
                  registroAberto()
                    ? () => {
                        setAviso(null)
                        setTelaAnonima('cadastro')
                      }
                    : null
                }
                aviso={aviso}
              />
            </div>
          )}
          {sessao.tipo === 'anonimo' && telaAnonima === 'cadastro' && registroAberto() && (
            <div className="cartao">
              <Cadastro
                aoEntrar={(usuario) => setSessao({ tipo: 'logado', usuario })}
                aoContaCriadaSemLogin={() => {
                  setAviso('Conta criada. Entre com seu e-mail e senha.')
                  setTelaAnonima('login')
                }}
                aoVoltar={() => setTelaAnonima('login')}
              />
            </div>
          )}
          <Rodape />
        </div>
      </main>
    )
  }

  // Lançamentos, Contas e Recorrências ficam montadas e só escondidas: elas carregam o que as
  // outras usam (contas ativas, recorrências ativas). O Início é só leitura e remonta a cada visita.
  return (
    <div className="app">
      <AvisoDeAtualizacao />
      <Topo
        secao={rota}
        aoSair={() => {
          // Quem entrou pelo cadastro não deve voltar a ver o formulário de cadastro.
          setTelaAnonima('login')
          setAviso(null)
          setSessao({ tipo: 'anonimo' })
        }}
      />
      <main className="conteudo">
        {rota === 'inicio' && (
          <Inicio
            usuario={sessao.usuario}
            contas={contasEmReais}
            versaoDeSaldo={versaoDeSaldo}
            versaoDaLista={versaoDaLista}
            aoSessaoExpirar={aoSessaoExpirar}
            aoMontarVencidas={setSlotDeVencidas}
          />
        )}
        <div className="secao" hidden={rota !== 'lancamentos'}>
          <Lancamentos
            aoSessaoExpirar={aoSessaoExpirar}
            contas={contasEmReais}
            aoMudarSaldo={() => setVersaoDeSaldo((n) => n + 1)}
            recorrenciasAtivas={recorrenciasAtivas}
            parcelasPorRecorrencia={parcelasPorRecorrencia}
            versaoDaLista={versaoDaLista}
            aoMudarOcorrencia={() => setVersaoDeRecorrencias((n) => n + 1)}
            destinoDasVencidas={slotDeVencidas}
            aoEditarVencida={irParaLancamentos}
          />
        </div>
        <div className="secao" hidden={rota !== 'contas'}>
          <Contas
            aoSessaoExpirar={aoSessaoExpirar}
            versaoDeSaldo={versaoDeSaldo}
            aoCarregarAtivas={setContasAtivas}
            aoMudarSaldo={() => {
              setVersaoDeSaldo((n) => n + 1)
              // O pagamento da fatura cria duas pernas que a lista do mês precisa mostrar.
              setVersaoDaLista((n) => n + 1)
            }}
          />
        </div>
        <div className="secao" hidden={rota !== 'recorrencias'}>
          <Recorrencias
            aoSessaoExpirar={aoSessaoExpirar}
            contas={contasEmReais}
            versao={versaoDeRecorrencias}
            aoCarregarAtivas={setRecorrenciasAtivas}
            aoCarregarParcelas={setParcelasPorRecorrencia}
            aoMudarOcorrencias={() => {
              setVersaoDaLista((n) => n + 1)
              setVersaoDeSaldo((n) => n + 1)
            }}
          />
        </div>
        {rota === 'orcamento' && <Orcamento aoSessaoExpirar={aoSessaoExpirar} />}
        {rota === 'metas' && <Metas aoSessaoExpirar={aoSessaoExpirar} />}
        {rota === 'relatorios' && <Relatorios aoSessaoExpirar={aoSessaoExpirar} />}
        {rota === 'investimentos' && (
          <Investimentos
            contasDeInvestimento={contasDeInvestimento}
            contasCripto={contasCripto}
            aoSessaoExpirar={aoSessaoExpirar}
          />
        )}
        <Rodape />
      </main>
    </div>
  )
}

export default App
