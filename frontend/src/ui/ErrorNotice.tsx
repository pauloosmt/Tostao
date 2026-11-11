import type { ReactNode } from 'react'
import { Alert, Button } from '../design-system/src'

export interface ErrorNoticeProps {
  title?: string
  children: ReactNode
  /** Com ela, o aviso oferece "Tentar de novo". */
  onRetry?: () => void
  retryLabel?: string
}

/** Um erro anunciado na hora (o Alert de perigo é `role="alert"`), com o caminho para tentar outra vez. */
export function ErrorNotice({ title, children, onRetry, retryLabel = 'Tentar de novo' }: ErrorNoticeProps) {
  return (
    <div className="stack">
      <Alert tone="danger" title={title}>
        {children}
      </Alert>
      {onRetry ? (
        <Button variant="secondary" onClick={onRetry}>
          {retryLabel}
        </Button>
      ) : null}
    </div>
  )
}
