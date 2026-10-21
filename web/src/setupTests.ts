import '@testing-library/jest-dom/vitest'
// O jsdom não implementa rolagem; a navegação por hash chama scrollTo ao trocar de seção.
window.scrollTo = () => {}
