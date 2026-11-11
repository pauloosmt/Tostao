import { render } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, type InitialEntry } from 'react-router'
import { App } from '../App'
import { AppProviders } from '../AppProviders'
import { LocationProbe } from './LocationProbe'

/** O aplicativo inteiro (tema, sessão e rotas) num roteador de memória, aberto no endereço dado, com um `user` pronto. */
export function renderApp(initialEntries: InitialEntry[] = ['/']) {
  return {
    user: userEvent.setup(),
    ...render(
      <MemoryRouter initialEntries={initialEntries}>
        <AppProviders>
          <App />
          <LocationProbe />
        </AppProviders>
      </MemoryRouter>,
    ),
  }
}
