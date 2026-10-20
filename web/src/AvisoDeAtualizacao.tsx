import { useRegisterSW } from 'virtual:pwa-register/react'

// Sem aviso de "pronto para offline": o app não funciona offline com dado (ADR-030).
function AvisoDeAtualizacao() {
  const {
    needRefresh: [precisaAtualizar],
    updateServiceWorker,
  } = useRegisterSW()

  if (!precisaAtualizar) return null

  return (
    <div role="status" className="aviso-de-atualizacao">
      <span>Nova versão disponível.</span>
      <button type="button" onClick={() => void updateServiceWorker(true)}>
        Atualizar
      </button>
    </div>
  )
}

export default AvisoDeAtualizacao
