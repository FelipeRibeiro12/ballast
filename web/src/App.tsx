import { useCallback, useEffect, useRef, useState } from 'react'
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
  postLogout,
} from './api'
import type { Conta, Usuario } from './api'
import Contas from './Contas'
import Lancamentos from './Lancamentos'

type EstadoSessao =
  | { tipo: 'carregando' }
  | { tipo: 'erro' }
  | { tipo: 'anonimo' }
  | { tipo: 'logado'; usuario: Usuario }

type EstadoHealthz =
  | { tipo: 'carregando' }
  | { tipo: 'ok'; mensagem: string }
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
        <button type="button" onClick={aoCriarConta}>
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
      <button type="button" onClick={aoVoltar}>
        Já tenho conta
      </button>
    </form>
  )
}

function Logado({ usuario, aoSair }: { usuario: Usuario; aoSair: () => void }) {
  const [saindo, setSaindo] = useState(false)
  const [falhou, setFalhou] = useState(false)

  async function sair(): Promise<void> {
    setSaindo(true)
    setFalhou(false)
    try {
      await postLogout()
      aoSair()
    } catch {
      // Sem confirmação do servidor a sessão pode seguir viva: continua logado e avisa.
      setFalhou(true)
      setSaindo(false)
    }
  }

  return (
    <section>
      <p>
        Você está logado como <strong>{usuario.nome ?? usuario.email}</strong>
        {usuario.nome !== null && <> ({usuario.email})</>}.
      </p>
      {falhou && (
        <p role="alert" className="erro">
          Não foi possível sair. Tente de novo.
        </p>
      )}
      <button type="button" disabled={saindo} onClick={() => void sair()}>
        Sair
      </button>
    </section>
  )
}

function Rodape() {
  const [estado, setEstado] = useState<EstadoHealthz>({ tipo: 'carregando' })

  useEffect(() => {
    // Evita setState depois de desmontar (e no segundo disparo do StrictMode).
    let ativo = true
    fetchHealthz().then(
      (resposta) => {
        if (ativo) setEstado({ tipo: 'ok', mensagem: resposta.mensagem })
      },
      () => {
        if (ativo) setEstado({ tipo: 'erro' })
      },
    )
    return () => {
      ativo = false
    }
  }, [])

  return (
    <footer>
      {estado.tipo === 'carregando' && <p>Verificando a API...</p>}
      {estado.tipo === 'ok' && <p>{estado.mensagem}</p>}
      {estado.tipo === 'erro' && (
        <p role="alert">Não foi possível falar com o servidor.</p>
      )}
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

  function tentarDeNovo(): void {
    setSessao({ tipo: 'carregando' })
    setTentativa((n) => n + 1)
  }

  return (
    <main>
      <h1>Ballast</h1>
      {sessao.tipo === 'carregando' && <p>Carregando...</p>}
      {sessao.tipo === 'erro' && (
        <>
          <p role="alert" className="erro">
            Não foi possível carregar sua sessão.
          </p>
          <button type="button" onClick={tentarDeNovo}>
            Tentar de novo
          </button>
        </>
      )}
      {sessao.tipo === 'anonimo' && telaAnonima === 'login' && (
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
      )}
      {sessao.tipo === 'anonimo' && telaAnonima === 'cadastro' && registroAberto() && (
        <Cadastro
          aoEntrar={(usuario) => setSessao({ tipo: 'logado', usuario })}
          aoContaCriadaSemLogin={() => {
            setAviso('Conta criada. Entre com seu e-mail e senha.')
            setTelaAnonima('login')
          }}
          aoVoltar={() => setTelaAnonima('login')}
        />
      )}
      {sessao.tipo === 'logado' && (
        <>
          <Logado
            usuario={sessao.usuario}
            aoSair={() => {
              // Quem entrou pelo cadastro não deve voltar a ver o formulário de cadastro.
              setTelaAnonima('login')
              setAviso(null)
              setSessao({ tipo: 'anonimo' })
            }}
          />
          <Contas
            aoSessaoExpirar={aoSessaoExpirar}
            versaoDeSaldo={versaoDeSaldo}
            aoCarregarAtivas={setContasAtivas}
          />
          <Lancamentos
            aoSessaoExpirar={aoSessaoExpirar}
            contas={contasAtivas}
            aoMudarSaldo={() => setVersaoDeSaldo((n) => n + 1)}
          />
        </>
      )}
      <Rodape />
    </main>
  )
}

export default App
