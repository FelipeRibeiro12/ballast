import { useRef, useState } from 'react'
import { SessaoExpiradaError, putCofrinhoEmInvestimentos } from './api'
import type { Conta, Patrimonio } from './api'
import { formatarCentavos } from './dinheiro'
import { IconeEngrenagem } from './icones'
import { mensagemDeEscrita } from './leitura'
import Painel from './Painel'

export type CofrinhoNaData = Patrimonio['cofrinhos'][number]

// Um interruptor por cofrinho, sem "marcar todos": o usuário escolhe quais contam (ADR-076).
function Escolha({
  cofrinhos,
  aoMudar,
  aoSessaoExpirar,
}: {
  cofrinhos: Conta[]
  aoMudar: () => void
  aoSessaoExpirar: () => void
}) {
  // A resposta do servidor vale mais que a lista do pai, que só atualiza depois do refetch.
  const [marcas, setMarcas] = useState<Record<string, boolean>>({})
  const [ocupado, setOcupado] = useState<string | null>(null)
  const [falha, setFalha] = useState<string | null>(null)
  const emVoo = useRef(false)

  async function alternar(conta: Conta, valor: boolean): Promise<void> {
    if (emVoo.current) return
    emVoo.current = true
    setOcupado(conta.id)
    setFalha(null)
    try {
      const atualizada = await putCofrinhoEmInvestimentos(conta.id, valor)
      setMarcas((atual) => ({ ...atual, [conta.id]: atualizada.emInvestimentos }))
      aoMudar()
    } catch (erro) {
      if (erro instanceof SessaoExpiradaError) {
        aoSessaoExpirar()
        return
      }
      setFalha(mensagemDeEscrita(erro, 'Não foi possível salvar a escolha. Tente de novo.'))
    } finally {
      emVoo.current = false
      setOcupado(null)
    }
  }

  if (cofrinhos.length === 0) {
    return <p>Nenhum cofrinho ativo. Crie uma conta do tipo Cofrinho na aba Contas.</p>
  }
  return (
    <>
      <p className="aviso-cripto">
        Os cofrinhos marcados entram no valor de Renda fixa e no total investido. O saldo e o Posso gastar não mudam.
      </p>
      {falha !== null && (
        <p role="alert" className="erro">
          {falha}
        </p>
      )}
      <ul className="categorias-lista" aria-label="Cofrinhos">
        {cofrinhos.map((conta) => (
          <li key={conta.id}>
            <label>
              <input
                type="checkbox"
                checked={marcas[conta.id] ?? conta.emInvestimentos}
                disabled={ocupado !== null}
                onChange={(evento) => void alternar(conta, evento.target.checked)}
              />
              {conta.nome}
            </label>
          </li>
        ))}
      </ul>
    </>
  )
}

// Engrenagem do card Renda fixa e a lista dos cofrinhos que entram nele, com o saldo na data.
export function EngrenagemDosCofrinhos({
  cofrinhos,
  aoMudar,
  aoSessaoExpirar,
}: {
  cofrinhos: Conta[]
  aoMudar: () => void
  aoSessaoExpirar: () => void
}) {
  const [aberto, setAberto] = useState(false)
  return (
    <>
      <button
        type="button"
        className="icone-botao"
        aria-label="Escolher cofrinhos da renda fixa"
        aria-haspopup="dialog"
        onClick={() => setAberto(true)}
      >
        <IconeEngrenagem />
      </button>
      {aberto && (
        <Painel titulo="Cofrinhos na renda fixa" aoFechar={() => setAberto(false)}>
          <Escolha cofrinhos={cofrinhos} aoMudar={aoMudar} aoSessaoExpirar={aoSessaoExpirar} />
        </Painel>
      )}
    </>
  )
}

export function ListaDosCofrinhos({ cofrinhos }: { cofrinhos: CofrinhoNaData[] }) {
  if (cofrinhos.length === 0) return null
  return (
    <ul className="lancamentos lista-cartao" aria-label="Cofrinhos na renda fixa">
      {cofrinhos.map((cofrinho) => (
        <li key={cofrinho.contaId}>
          <span className="item-icone">Cofrinho</span>
          <div className="item-corpo">
            <strong className="item-titulo">{cofrinho.nome}</strong>
          </div>
          <div className="item-lado">
            <span className="item-valor">{formatarCentavos(cofrinho.saldoCentavos)}</span>
          </div>
        </li>
      ))}
    </ul>
  )
}
