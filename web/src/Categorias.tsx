import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { SessaoExpiradaError, deleteCategoria, fetchCategorias, postCategoria, putCategoria } from './api'
import type { Categoria } from './api'
import { IconeMais } from './icones'
import { carregar, mensagemDeEscrita } from './leitura'
import type { Estado } from './leitura'
import MenuDeAcoes from './MenuDeAcoes'
import Painel from './Painel'
import AtalhosDePlanejamento from './Planejamento'
import { Carregando, EstadoVazio, Selo } from './ui'

// O teto de 20 sem contar as duas protegidas é do servidor (ADR-064); aqui só avisa.
const TETO = 20

type Abertura = { tipo: 'nova' } | { tipo: 'renomear'; categoria: Categoria }

function Formulario({
  inicial,
  aoSalvar,
  aoSessaoExpirar,
}: {
  inicial: Categoria | null
  aoSalvar: () => void
  aoSessaoExpirar: () => void
}) {
  const [nome, setNome] = useState(inicial?.nome ?? '')
  const [tipo, setTipo] = useState<Categoria['tipo']>('despesa')
  const [enviando, setEnviando] = useState(false)
  const [mensagem, setMensagem] = useState<string | null>(null)
  const emVoo = useRef(false)

  async function enviar(evento: FormEvent<HTMLFormElement>): Promise<void> {
    evento.preventDefault()
    if (emVoo.current) return
    emVoo.current = true
    setEnviando(true)
    setMensagem(null)
    try {
      if (inicial === null) await postCategoria(nome.trim(), tipo)
      else await putCategoria(inicial.id, nome.trim())
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
    <form onSubmit={(evento) => void enviar(evento)} aria-label="Categoria">
      <div className="campo">
        <label htmlFor="categoria-nome">Nome</label>
        <input
          id="categoria-nome"
          type="text"
          maxLength={100}
          required
          autoComplete="off"
          value={nome}
          onChange={(evento) => setNome(evento.target.value)}
        />
      </div>
      {inicial === null && (
        <div className="campo">
          <label htmlFor="categoria-tipo">Tipo</label>
          <select
            id="categoria-tipo"
            value={tipo}
            onChange={(evento) => setTipo(evento.target.value === 'receita' ? 'receita' : 'despesa')}
          >
            <option value="despesa">Despesa</option>
            <option value="receita">Receita</option>
          </select>
        </div>
      )}
      {mensagem !== null && (
        <p role="alert" className="erro">
          {mensagem}
        </p>
      )}
      <button type="submit" className="primario" disabled={enviando}>
        Salvar
      </button>
    </form>
  )
}

// Criar, renomear e apagar (ADR-064). Apagar manda os lançamentos para Outros.
export default function Categorias({
  aoSessaoExpirar,
  aoMudar,
}: {
  aoSessaoExpirar: () => void
  aoMudar: () => void
}) {
  const [versao, setVersao] = useState(0)
  const [estado, setEstado] = useState<Estado<Categoria[]>>({ tipo: 'carregando' })
  const [abertura, setAbertura] = useState<Abertura | null>(null)
  const [confirmando, setConfirmando] = useState<string | null>(null)
  const [falha, setFalha] = useState<string | null>(null)

  useEffect(() => {
    let vale = true
    carregar(fetchCategorias, 'as categorias', aoSessaoExpirar, setEstado, () => vale)
    return () => {
      vale = false
    }
  }, [versao, aoSessaoExpirar])

  async function apagar(categoria: Categoria): Promise<void> {
    setFalha(null)
    try {
      await deleteCategoria(categoria.id)
      setConfirmando(null)
      setVersao((n) => n + 1)
      aoMudar()
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      setConfirmando(null)
      setFalha(mensagemDeEscrita(erro, 'Não foi possível apagar. Tente de novo.'))
    }
  }

  const categorias = estado.tipo === 'ok' ? estado.dados : []
  const usadas = categorias.filter((categoria) => !categoria.protegida).length

  return (
    <section aria-labelledby="titulo-categorias">
      <header className="pagina-topo">
        <h2 id="titulo-categorias">Categorias</h2>
        <button
          type="button"
          className="primario botao-novo"
          disabled={estado.tipo !== 'ok' || usadas >= TETO}
          onClick={() => setAbertura({ tipo: 'nova' })}
        >
          <IconeMais />
          Nova categoria
        </button>
      </header>
      <AtalhosDePlanejamento atual="categorias" />
      {estado.tipo === 'ok' && (
        <small>
          {usadas} de {TETO} categorias. Outros e Outras receitas não entram na conta.
        </small>
      )}
      {estado.tipo === 'carregando' && <Carregando texto="Carregando categorias..." />}
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
      {estado.tipo === 'ok' && categorias.length === 0 && <EstadoVazio titulo="Nenhuma categoria." dica="" />}
      {estado.tipo === 'ok' && categorias.length > 0 && (
        <ul className="lancamentos lista-cartao" aria-label="Categorias">
          {categorias.map((categoria) => (
            <li key={categoria.id}>
              <div className="item-corpo">
                <strong className="item-titulo">{categoria.nome}</strong>
                <div className="item-selos">
                  <Selo tom={categoria.tipo === 'receita' ? 'paga' : 'neutro'}>
                    {categoria.tipo === 'receita' ? 'Receita' : 'Despesa'}
                  </Selo>
                  {categoria.protegida && <Selo tom="neutro">Padrão</Selo>}
                </div>
              </div>
              {!categoria.protegida && (
                <div className="item-lado">
                  {confirmando === categoria.id ? (
                    <div className="item-botoes">
                      <button
                        type="button"
                        className="pequeno perigo"
                        aria-label={`Confirmar apagar ${categoria.nome}`}
                        onClick={() => void apagar(categoria)}
                      >
                        Apagar
                      </button>
                      <button type="button" className="pequeno" onClick={() => setConfirmando(null)}>
                        Manter
                      </button>
                    </div>
                  ) : (
                    <MenuDeAcoes rotulo={`Mais ações de ${categoria.nome}`}>
                      <button
                        type="button"
                        aria-label={`Renomear ${categoria.nome}`}
                        onClick={() => setAbertura({ tipo: 'renomear', categoria })}
                      >
                        Renomear
                      </button>
                      <button
                        type="button"
                        className="perigo"
                        aria-label={`Apagar ${categoria.nome}`}
                        onClick={() => {
                          setFalha(null)
                          setConfirmando(categoria.id)
                        }}
                      >
                        Apagar (lançamentos vão para Outros)
                      </button>
                    </MenuDeAcoes>
                  )}
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
      {abertura !== null && (
        <Painel
          titulo={abertura.tipo === 'nova' ? 'Nova categoria' : `Renomear ${abertura.categoria.nome}`}
          aoFechar={() => setAbertura(null)}
        >
          <Formulario
            inicial={abertura.tipo === 'renomear' ? abertura.categoria : null}
            aoSalvar={() => {
              setAbertura(null)
              setVersao((n) => n + 1)
              aoMudar()
            }}
            aoSessaoExpirar={aoSessaoExpirar}
          />
        </Painel>
      )}
    </section>
  )
}
