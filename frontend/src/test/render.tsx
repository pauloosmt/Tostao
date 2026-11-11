import { render, type RenderResult } from '@testing-library/react'
import userEvent, { type UserEvent } from '@testing-library/user-event'
import type { ReactElement } from 'react'

/** Renderiza e devolve também um `user` (userEvent) pronto: os testes interagem como a pessoa, pelo teclado e pelo toque. */
export function renderWithUser(ui: ReactElement): RenderResult & { user: UserEvent } {
  return { user: userEvent.setup(), ...render(ui) }
}
