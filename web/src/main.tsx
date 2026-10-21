import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import { aplicarTema, lerTema } from './tema'

// Antes do primeiro desenho: quem escolheu o escuro não vê o claro piscar.
aplicarTema(lerTema())

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
