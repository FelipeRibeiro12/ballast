import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import {
  CredenciaisInvalidasError,
  LimiteTentativasError,
  fetchHealthz,
  fetchUsuarioAtual,
  postLogin,
  postLogout,
} from './api'
import type { Usuario } from './api'

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

function Login({ aoEntrar }: { aoEntrar: (usuario: Usuario) => void }) {
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
      {mensagem !== null && (
        <p id="erro-login" role="alert" className="erro">
          {mensagem}
        </p>
      )}
      <button type="submit" disabled={enviando}>
        {enviando ? 'Entrando...' : 'Entrar'}
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
      {sessao.tipo === 'anonimo' && (
        <Login aoEntrar={(usuario) => setSessao({ tipo: 'logado', usuario })} />
      )}
      {sessao.tipo === 'logado' && (
        <Logado usuario={sessao.usuario} aoSair={() => setSessao({ tipo: 'anonimo' })} />
      )}
      <Rodape />
    </main>
  )
}

export default App
