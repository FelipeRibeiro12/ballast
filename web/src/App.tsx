import { useEffect, useState } from 'react'
import { fetchHealthz } from './api'

type Estado =
  | { tipo: 'carregando' }
  | { tipo: 'ok'; mensagem: string }
  | { tipo: 'erro' }

function App() {
  const [estado, setEstado] = useState<Estado>({ tipo: 'carregando' })

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
    <main>
      <h1>Ballast</h1>
      {estado.tipo === 'carregando' && <p>Carregando...</p>}
      {estado.tipo === 'ok' && <p>{estado.mensagem}</p>}
      {estado.tipo === 'erro' && (
        <p role="alert">Não foi possível falar com o servidor.</p>
      )}
    </main>
  )
}

export default App
