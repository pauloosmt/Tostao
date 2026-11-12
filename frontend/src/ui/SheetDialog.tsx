import { useEffect, useRef, type ReactNode } from 'react'
import { Sheet } from '../design-system/src'
import { useMediaQuery } from '../layout/useMediaQuery'

export interface SheetDialogProps {
  title: string
  /** Fechar: o botão "Fechar", a tecla Esc e o toque no fundo escurecido. */
  onClose: () => void
  /** O botão principal, de largura total. Com `onSubmit`, um botão `type="submit"` aqui envia o formulário. */
  footer?: ReactNode
  /** Com ele, o conteúdo todo é um formulário (Enter nos campos envia). */
  onSubmit?: () => void
  /** O `id` do elemento que recebe o foco ao abrir; sem ele, o primeiro foco vai ao botão "Fechar". */
  initialFocusId?: string
  children: ReactNode
}

/**
 * A folha do design system dentro de um `<dialog>` modal: prende o foco, fecha com Esc, escurece o fundo e devolve o foco a quem a abriu.
 * Lateral no desktop; abaixo de 720 px, de baixo. A folha existe enquanto este componente existe: o pai o monta para abrir e o desmonta para fechar.
 */
export function SheetDialog({ title, onClose, footer, onSubmit, initialFocusId, children }: SheetDialogProps) {
  const dialog = useRef<HTMLDialogElement>(null)
  const closing = useRef(false)
  const bottom = useMediaQuery('(max-width: 719px)')

  useEffect(() => {
    const element = dialog.current
    if (!element) return
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null
    closing.current = false
    if (!element.open) element.showModal()
    if (initialFocusId) document.getElementById(initialFocusId)?.focus()
    return () => {
      // Desmontar não é um pedido de fechar: o pai já sabe.
      closing.current = true
      if (element.open) element.close()
      opener?.focus?.()
    }
  }, [initialFocusId])

  const sheet = (
    <Sheet title={title} onClose={onClose} footer={footer} className={bottom ? 'tx-sheet--bottom' : undefined}>
      {children}
    </Sheet>
  )

  return (
    <dialog
      ref={dialog}
      className={`launch-dialog${bottom ? ' launch-dialog--bottom' : ''}`}
      aria-label={title}
      // Esc (o navegador fecha o <dialog> sozinho): é um pedido de fechar. O `close` de um diálogo de dentro (a confirmação de excluir)
      // sobe pelo React até aqui e não pode fechar a folha.
      onClose={(event) => {
        if (event.target === event.currentTarget && !closing.current) onClose()
      }}
      // O toque no fundo escurecido (o próprio <dialog>, fora da folha) também fecha.
      onClick={(event) => {
        if (event.target === event.currentTarget) onClose()
      }}
    >
      {onSubmit ? (
        <form
          className="launch-form"
          noValidate
          onSubmit={(event) => {
            event.preventDefault()
            onSubmit()
          }}
        >
          {sheet}
        </form>
      ) : (
        sheet
      )}
    </dialog>
  )
}
