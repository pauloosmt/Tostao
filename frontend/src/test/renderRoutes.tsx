import { render } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { MemoryRouter, Routes, type InitialEntry } from 'react-router'
import { AuthProvider } from '../auth/AuthContext'
import { LocationProbe } from './LocationProbe'

/** Renderiza as rotas dentro do roteador de memória e da sessão, como o aplicativo, e devolve também um `user`. */
export function renderRoutes(routes: ReactNode, initialEntries: InitialEntry[]) {
  return {
    user: userEvent.setup(),
    ...render(
      <MemoryRouter initialEntries={initialEntries}>
        <AuthProvider>
          <Routes>{routes}</Routes>
          <LocationProbe />
        </AuthProvider>
      </MemoryRouter>,
    ),
  }
}
