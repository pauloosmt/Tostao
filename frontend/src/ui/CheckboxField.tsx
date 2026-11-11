import type { InputHTMLAttributes } from 'react'

export interface CheckboxFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'className' | 'type'> {
  label: string
}

/** Uma caixa de marcação nativa com o rótulo clicável, do tamanho de toque do aplicativo. */
export function CheckboxField({ label, ...rest }: CheckboxFieldProps) {
  return (
    <label className="checkbox-field text-body">
      <input type="checkbox" {...rest} />
      <span>{label}</span>
    </label>
  )
}
