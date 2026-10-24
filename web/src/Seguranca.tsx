import { useEffect, useMemo, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { encode } from 'uqr'
import {
  LimiteTentativasError,
  MfaRecusadoError,
  ServicoIndisponivelError,
  SessaoExpiradaError,
  fetchMfa,
  postMfaAtivar,
  postMfaCodigosDeRecuperacao,
  postMfaConfigurar,
  postMfaDesativar,
} from './api'
import type { MfaConfiguracao, MfaStatus } from './api'
import { carregar } from './leitura'
import type { Estado } from './leitura'
import { Carregando } from './ui'

// As mensagens fixas do servidor que a tela traduz; o resto cai no texto genérico.
const MENSAGEM_DO_SERVIDOR: Record<string, string> = {
  'senha incorreta': 'Senha incorreta.',
  'código inválido': 'Código inválido.',
  'mfa já ativo': 'A verificação em duas etapas já está ligada.',
  'mfa não está ativo': 'A verificação em duas etapas já está desligada.',
  'configure o mfa antes': 'Comece de novo pelo botão Ligar.',
}

function mensagemDeMfa(erro: unknown, padrao: string): string {
  if (erro instanceof MfaRecusadoError && erro.mensagemServidor !== null) {
    return MENSAGEM_DO_SERVIDOR[erro.mensagemServidor] ?? padrao
  }
  if (erro instanceof LimiteTentativasError) {
    if (erro.segundos === null) return 'Muitas tentativas. Tente de novo em instantes.'
    const unidade = erro.segundos === 1 ? 'segundo' : 'segundos'
    return `Muitas tentativas. Tente de novo em ${erro.segundos} ${unidade}.`
  }
  if (erro instanceof ServicoIndisponivelError) {
    return 'A verificação em duas etapas não está disponível neste servidor.'
  }
  return padrao
}

// Desenhado em React a partir da matriz do uqr, sem innerHTML. Fundo branco e módulos pretos em
// qualquer tema: leitor de QR invertido falha em muito celular.
function QrCode({ texto }: { texto: string }) {
  const { caminho, lado } = useMemo(() => {
    const qr = encode(texto, { ecc: 'M', border: 2 })
    const partes: string[] = []
    qr.data.forEach((linha, y) => {
      linha.forEach((preto, x) => {
        if (preto) partes.push(`M${x} ${y}h1v1h-1z`)
      })
    })
    return { caminho: partes.join(''), lado: qr.size }
  }, [texto])
  return (
    <svg
      className="qr"
      role="img"
      aria-label="QR code para o app autenticador"
      viewBox={`0 0 ${lado} ${lado}`}
      shapeRendering="crispEdges"
    >
      <rect width={lado} height={lado} fill="#fff" />
      <path d={caminho} fill="#000" />
    </svg>
  )
}

// Em grupos de quatro, como os autenticadores mostram, para digitar sem se perder.
function emGrupos(segredo: string): string {
  return segredo.replace(/(.{4})(?=.)/g, '$1 ')
}

function useEnvio(aoSessaoExpirar: () => void) {
  const [enviando, setEnviando] = useState(false)
  const [mensagem, setMensagem] = useState<string | null>(null)
  // O state só atualiza no próximo render; o ref barra o segundo submit síncrono.
  const emVoo = useRef(false)

  async function enviar(acao: () => Promise<void>, padrao: string, aoFalhar: () => void): Promise<void> {
    if (emVoo.current) return
    emVoo.current = true
    setEnviando(true)
    setMensagem(null)
    try {
      await acao()
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      setMensagem(mensagemDeMfa(erro, padrao))
      aoFalhar()
    } finally {
      emVoo.current = false
      setEnviando(false)
    }
  }

  return { enviando, mensagem, enviar }
}

type Passo =
  | { tipo: 'senha' }
  | { tipo: 'qr'; configuracao: MfaConfiguracao }
  | { tipo: 'codigos'; codigos: string[] }

function Ligar({
  aoTerminar,
  aoCancelar,
  aoSessaoExpirar,
}: {
  aoTerminar: () => void
  aoCancelar: () => void
  aoSessaoExpirar: () => void
}) {
  const [passo, setPasso] = useState<Passo>({ tipo: 'senha' })
  const [senha, setSenha] = useState('')
  const [codigo, setCodigo] = useState('')
  const { enviando, mensagem, enviar } = useEnvio(aoSessaoExpirar)
  const campoSenha = useRef<HTMLInputElement>(null)
  const campoCodigo = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (passo.tipo === 'senha') campoSenha.current?.focus()
    if (passo.tipo === 'qr') campoCodigo.current?.focus()
  }, [passo.tipo])

  function configurar(evento: FormEvent<HTMLFormElement>): void {
    evento.preventDefault()
    void enviar(
      async () => {
        const configuracao = await postMfaConfigurar(senha)
        setSenha('')
        setPasso({ tipo: 'qr', configuracao })
      },
      'Não foi possível começar. Tente de novo.',
      () => {
        setSenha('')
        campoSenha.current?.focus()
      },
    )
  }

  function ativar(evento: FormEvent<HTMLFormElement>): void {
    evento.preventDefault()
    void enviar(
      async () => {
        setPasso({ tipo: 'codigos', codigos: await postMfaAtivar(codigo) })
        setCodigo('')
      },
      'Não foi possível ativar. Tente de novo.',
      () => {
        setCodigo('')
        campoCodigo.current?.focus()
      },
    )
  }

  if (passo.tipo === 'senha') {
    return (
      <form className="cartao mfa-cartao" onSubmit={configurar} aria-label="Confirmar senha">
        <p>Confirme sua senha para começar.</p>
        <div className="campo">
          <label htmlFor="mfa-senha">Senha</label>
          <input
            id="mfa-senha"
            ref={campoSenha}
            type="password"
            autoComplete="current-password"
            maxLength={128}
            required
            value={senha}
            onChange={(evento) => setSenha(evento.target.value)}
          />
        </div>
        {mensagem !== null && (
          <p role="alert" className="erro">
            {mensagem}
          </p>
        )}
        <div className="mfa-acoes">
          <button type="submit" className="primario" disabled={enviando}>
            Continuar
          </button>
          <button type="button" className="discreto" onClick={aoCancelar}>
            Cancelar
          </button>
        </div>
      </form>
    )
  }

  if (passo.tipo === 'qr') {
    return (
      <form className="cartao mfa-cartao" onSubmit={ativar} aria-label="Ativar verificação">
        <p>Leia o QR code no app autenticador (Google Authenticator, Aegis, 1Password...).</p>
        <QrCode texto={passo.configuracao.uri} />
        <p className="mfa-segredo">
          Ou digite o segredo: <code>{emGrupos(passo.configuracao.segredo)}</code>
        </p>
        <div className="campo">
          <label htmlFor="mfa-codigo">Código do app</label>
          <input
            id="mfa-codigo"
            ref={campoCodigo}
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={32}
            required
            value={codigo}
            onChange={(evento) => setCodigo(evento.target.value)}
          />
        </div>
        {mensagem !== null && (
          <p role="alert" className="erro">
            {mensagem}
          </p>
        )}
        <div className="mfa-acoes">
          <button type="submit" className="primario" disabled={enviando}>
            Ativar
          </button>
          <button type="button" className="discreto" onClick={aoCancelar}>
            Cancelar
          </button>
        </div>
      </form>
    )
  }

  return (
    <div className="cartao mfa-cartao">
      <p role="status">Verificação ligada.</p>
      <p>
        <strong>Guarde estes códigos. Eles não aparecem de novo.</strong> Cada um entra uma vez no lugar do
        código do app, se você perder o celular.
      </p>
      <ul className="mfa-codigos" aria-label="Códigos de recuperação">
        {passo.codigos.map((recuperacao) => (
          <li key={recuperacao}>
            <code>{recuperacao}</code>
          </li>
        ))}
      </ul>
      <button type="button" className="primario" onClick={aoTerminar}>
        Já guardei
      </button>
    </div>
  )
}

function GerarCodigos({
  aoTerminar,
  aoCancelar,
  aoSessaoExpirar,
}: {
  aoTerminar: () => void
  aoCancelar: () => void
  aoSessaoExpirar: () => void
}) {
  const [codigo, setCodigo] = useState('')
  const [novos, setNovos] = useState<string[] | null>(null)
  const { enviando, mensagem, enviar } = useEnvio(aoSessaoExpirar)
  const campo = useRef<HTMLInputElement>(null)

  useEffect(() => {
    campo.current?.focus()
  }, [])

  function gerar(evento: FormEvent<HTMLFormElement>): void {
    evento.preventDefault()
    void enviar(
      async () => {
        setNovos(await postMfaCodigosDeRecuperacao(codigo))
        setCodigo('')
      },
      'Não foi possível gerar os códigos. Tente de novo.',
      () => {
        setCodigo('')
        campo.current?.focus()
      },
    )
  }

  if (novos !== null) {
    return (
      <div className="cartao mfa-cartao">
        <p role="status">Códigos novos gerados. Os códigos antigos deixaram de valer.</p>
        <p>
          <strong>Guarde estes códigos. Eles não aparecem de novo.</strong>
        </p>
        <ul className="mfa-codigos" aria-label="Códigos de recuperação">
          {novos.map((recuperacao) => (
            <li key={recuperacao}>
              <code>{recuperacao}</code>
            </li>
          ))}
        </ul>
        <button type="button" className="primario" onClick={aoTerminar}>
          Já guardei
        </button>
      </div>
    )
  }

  return (
    <form className="cartao mfa-cartao" onSubmit={gerar} aria-label="Gerar novos códigos de recuperação">
      <p>Os códigos atuais deixam de valer. Digite o código de 6 dígitos do app para continuar.</p>
      <div className="campo">
        <label htmlFor="mfa-gerar-codigo">Código do app</label>
        <input
          id="mfa-gerar-codigo"
          ref={campo}
          inputMode="numeric"
          autoComplete="one-time-code"
          maxLength={32}
          required
          value={codigo}
          onChange={(evento) => setCodigo(evento.target.value)}
        />
      </div>
      {mensagem !== null && (
        <p role="alert" className="erro">
          {mensagem}
        </p>
      )}
      <div className="mfa-acoes">
        <button type="submit" className="primario" disabled={enviando}>
          Gerar
        </button>
        <button type="button" className="discreto" onClick={aoCancelar}>
          Cancelar
        </button>
      </div>
    </form>
  )
}

function Desligar({
  aoTerminar,
  aoCancelar,
  aoSessaoExpirar,
}: {
  aoTerminar: () => void
  aoCancelar: () => void
  aoSessaoExpirar: () => void
}) {
  const [senha, setSenha] = useState('')
  const [codigo, setCodigo] = useState('')
  const { enviando, mensagem, enviar } = useEnvio(aoSessaoExpirar)

  function desligar(evento: FormEvent<HTMLFormElement>): void {
    evento.preventDefault()
    void enviar(
      async () => {
        await postMfaDesativar(senha, codigo)
        aoTerminar()
      },
      'Não foi possível desligar. Tente de novo.',
      () => {
        setSenha('')
        setCodigo('')
      },
    )
  }

  return (
    <form className="cartao mfa-cartao" onSubmit={desligar} aria-label="Desligar verificação">
      <p>Para desligar, confirme a senha e um código do app ou de recuperação.</p>
      <div className="campo">
        <label htmlFor="mfa-desligar-senha">Senha</label>
        <input
          id="mfa-desligar-senha"
          type="password"
          autoComplete="current-password"
          maxLength={128}
          required
          value={senha}
          onChange={(evento) => setSenha(evento.target.value)}
        />
      </div>
      <div className="campo">
        <label htmlFor="mfa-desligar-codigo">Código</label>
        <input
          id="mfa-desligar-codigo"
          autoComplete="one-time-code"
          autoCapitalize="none"
          spellCheck={false}
          maxLength={32}
          required
          value={codigo}
          onChange={(evento) => setCodigo(evento.target.value)}
        />
      </div>
      {mensagem !== null && (
        <p role="alert" className="erro">
          {mensagem}
        </p>
      )}
      <div className="mfa-acoes">
        <button type="submit" className="perigo" disabled={enviando}>
          Desligar
        </button>
        <button type="button" className="discreto" onClick={aoCancelar}>
          Cancelar
        </button>
      </div>
    </form>
  )
}

// ADR-050. Fora da barra de navegação: abre pelo ícone de escudo do topo.
export default function Seguranca({ aoSessaoExpirar }: { aoSessaoExpirar: () => void }) {
  const [estado, setEstado] = useState<Estado<MfaStatus>>({ tipo: 'carregando' })
  const [versao, setVersao] = useState(0)
  const [aberto, setAberto] = useState<'ligar' | 'desligar' | 'gerar' | null>(null)

  useEffect(() => {
    let vale = true
    carregar(fetchMfa, 'a verificação em duas etapas', aoSessaoExpirar, setEstado, () => vale)
    return () => {
      vale = false
    }
  }, [versao, aoSessaoExpirar])

  function terminar(): void {
    setAberto(null)
    setVersao((n) => n + 1)
  }

  return (
    <section aria-labelledby="titulo-seguranca">
      <header className="pagina-topo">
        <h2 id="titulo-seguranca">Segurança</h2>
      </header>
      {estado.tipo === 'carregando' && <Carregando texto="Carregando segurança..." />}
      {estado.tipo === 'erro' && (
        <p role="alert" className="erro">
          {estado.mensagem}
        </p>
      )}
      {estado.tipo === 'ok' && aberto === null && (
        <div className="cartao mfa-cartao">
          <h3>Verificação em duas etapas</h3>
          {estado.dados.ativo ? (
            <>
              <p>Verificação em duas etapas ligada.</p>
              <p>
                {estado.dados.codigosRestantes === 1
                  ? '1 código de recuperação restante.'
                  : `${estado.dados.codigosRestantes} códigos de recuperação restantes.`}
              </p>
              <div className="mfa-acoes">
                <button type="button" onClick={() => setAberto('gerar')}>
                  Gerar novos códigos
                </button>
                <button type="button" onClick={() => setAberto('desligar')}>
                  Desligar
                </button>
              </div>
            </>
          ) : (
            <>
              <p>Verificação em duas etapas desligada.</p>
              <p>Ligada, o login pede também um código do app autenticador do seu celular.</p>
              <button type="button" className="primario" onClick={() => setAberto('ligar')}>
                Ligar
              </button>
            </>
          )}
        </div>
      )}
      {aberto === 'ligar' && (
        <Ligar aoTerminar={terminar} aoCancelar={terminar} aoSessaoExpirar={aoSessaoExpirar} />
      )}
      {aberto === 'gerar' && (
        <GerarCodigos aoTerminar={terminar} aoCancelar={() => setAberto(null)} aoSessaoExpirar={aoSessaoExpirar} />
      )}
      {aberto === 'desligar' && (
        <Desligar aoTerminar={terminar} aoCancelar={() => setAberto(null)} aoSessaoExpirar={aoSessaoExpirar} />
      )}
    </section>
  )
}
