import { Link, type LinkProps } from 'react-router'

export interface ButtonLinkProps extends Omit<LinkProps, 'className'> {
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger'
  size?: 'sm' | 'md' | 'lg'
  full?: boolean
}

/** Um link do roteador com a cara de botão do design system (para quem só navega: "Criar conta", "Editar", "Voltar"). */
export function ButtonLink({ variant = 'secondary', size = 'md', full, children, ...link }: ButtonLinkProps) {
  const className = ['tx-btn', 'text-label', `tx-btn--${variant}`, `tx-btn--${size}`, full ? 'tx-btn--full' : '', 'button-link'].filter(Boolean).join(' ')
  return (
    <Link className={className} {...link}>
      {children}
    </Link>
  )
}
