import { useState } from 'react'
import { Button, Field, type FieldProps } from '../design-system/src'

export interface PasswordFieldProps extends Omit<FieldProps, 'type' | 'autoComplete'> {
  /** Obrigatório: é ele que faz o gerenciador de senhas oferecer (ou não) uma senha nova. */
  autoComplete: 'current-password' | 'new-password'
}

/** Campo de senha com "Mostrar senha". O botão tem sempre o mesmo nome e informa o estado por `aria-pressed`. */
export function PasswordField({ autoComplete, ...field }: PasswordFieldProps) {
  const [visible, setVisible] = useState(false)
  return (
    <div className="password-field">
      <Field {...field} type={visible ? 'text' : 'password'} autoComplete={autoComplete} autoCapitalize="off" spellCheck={false} />
      <Button variant="ghost" size="sm" aria-pressed={visible} onClick={() => setVisible((current) => !current)}>
        Mostrar senha
      </Button>
    </div>
  )
}
