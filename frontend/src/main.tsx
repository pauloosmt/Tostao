import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import './design-system/src'
import './styles/app.css'
import { App } from './App'
import { AppProviders } from './AppProviders'
import { applyStoredTheme } from './theme/theme'

// O tema guardado vale antes da primeira pintura (e antes do React), para não piscar o tema errado.
applyStoredTheme()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <AppProviders>
        <App />
      </AppProviders>
    </BrowserRouter>
  </StrictMode>,
)
