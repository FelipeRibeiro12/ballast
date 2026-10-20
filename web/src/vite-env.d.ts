/// <reference types="vite/client" />
/// <reference types="vite-plugin-pwa/react" />

interface ImportMetaEnv {
  readonly VITE_API_URL: string | undefined
  // Só a string exata "true" liga a tela de cadastro (ADR-022).
  readonly VITE_REGISTRO_ABERTO: string | undefined
}
