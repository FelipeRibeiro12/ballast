import { render, screen } from '@testing-library/react'
import App from './App'

describe('App', () => {
  it('mostra o título Ballast', () => {
    render(<App />)
    expect(screen.getByRole('heading', { name: /ballast/i })).toBeVisible()
  })
})
