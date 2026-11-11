import { useEffect, useId, useRef, type ReactNode } from 'react'
import { Button } from '../design-system/src'

export interface ConfirmDialogProps {
  open: boolean
  title: string
  children: ReactNode
  confirmLabel: string
  cancelLabel?: string
  /** Enquanto a ação roda: os dois botões ficam indisponíveis e o diálogo não fecha por engano. */
  busy?: boolean
  /** `danger` (padrão) para o que não se desfaz; `primary` para o que tem volta (arquivar, por exemplo). */
  tone?: 'danger' | 'primary'
  onConfirm: () => void
  /** O botão "Cancelar" e a tecla Esc. */
  onCancel: () => void
}

/** Confirmação com o `<dialog>` nativo: prende o foco, fecha com Esc e devolve o foco a quem o abriu. */
export function ConfirmDialog({ open, title, children, confirmLabel, cancelLabel = 'Cancelar', busy = false, tone = 'danger', onConfirm, onCancel }: ConfirmDialogProps) {
  const dialog = useRef<HTMLDialogElement>(null)
  const titleId = useId()
  const bodyId = useId()

  useEffect(() => {
    const element = dialog.current
    if (!element) return
    if (open && !element.open) {
      element.showModal()
      // O caminho seguro recebe o foco: um Enter sem querer cancela em vez de confirmar.
      element.querySelector<HTMLElement>('[data-initial-focus]')?.focus()
    }
    if (!open && element.open) element.close()
  }, [open])

  return (
    <dialog
      ref={dialog}
      className="confirm-dialog"
      aria-labelledby={titleId}
      aria-describedby={bodyId}
      // Fechou sem o pai ter pedido (a tecla Esc): é um cancelamento. Se o pai fechou, `open` já é falso e nada se repete.
      onClose={() => {
        if (open) onCancel()
      }}
      // Esc durante a ação em andamento não fecha.
      onCancel={(event) => {
        if (busy) event.preventDefault()
      }}
    >
      <div className="stack">
        <h2 id={titleId} className="heading-2">
          {title}
        </h2>
        <div id={bodyId} className="text-body">
          {children}
        </div>
        <div className="confirm-dialog__actions">
          <Button variant="secondary" onClick={onCancel} disabled={busy} data-initial-focus>
            {cancelLabel}
          </Button>
          <Button variant={tone} onClick={onConfirm} disabled={busy}>
            {confirmLabel}
          </Button>
        </div>
      </div>
    </dialog>
  )
}
