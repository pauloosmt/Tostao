import { useEffect, useId, useLayoutEffect, useRef, useState, type KeyboardEvent } from 'react'
import { Icon, IconButton } from '../design-system/src'
import {
  WEEKDAY_INITIALS,
  WEEKDAY_NAMES,
  addDays,
  addMonths,
  brToIso,
  fromIso,
  isoToBr,
  longLabel,
  maskBr,
  monthGrid,
  monthLabel,
  todayIso,
} from '../dates/calendar'

export interface DateFieldProps {
  id?: string
  label: string
  /** A data como a API a usa (`AAAA-MM-DD`), ou vazio. */
  value: string
  onChange: (iso: string) => void
  /** O erro da tela (envio ou API); tem prioridade sobre o do próprio campo. */
  error?: string
  /** Dica sob o campo (some quando há erro). */
  hint?: string
}

/** Largura do calendário (20 rem): se não couber à direita do campo, ele abre alinhado à direita. */
const POPOVER_WIDTH = 320

/**
 * Campo de data com o calendário do sistema (no lugar do `<input type="date">` do navegador). Dá para digitar (`13112026` vira `13/11/2026`;
 * letras são ignoradas) ou abrir o calendário: navegar pelos meses, escolher o dia, "Hoje" e "Limpar", e pelo teclado (setas, PageUp/PageDown, Esc).
 * `value` e `onChange` usam `AAAA-MM-DD`; uma digitação incompleta ou impossível (31/02) entrega vazio.
 */
export function DateField({ id, label, value, onChange, error, hint }: DateFieldProps) {
  const auto = useId()
  const inputId = id ?? auto
  const messageId = `${inputId}-msg`
  const root = useRef<HTMLDivElement>(null)
  const popover = useRef<HTMLDivElement>(null)
  const toggle = useRef<HTMLButtonElement>(null)

  const [draft, setDraft] = useState(isoToBr(value))
  const [left, setLeft] = useState(false)
  const [open, setOpen] = useState(false)
  const [focusIso, setFocusIso] = useState(value)
  const [alignRight, setAlignRight] = useState(false)

  // O texto digitado vale enquanto ainda corresponde ao `value`; se a tela trocar o valor (editar outro lançamento, "Hoje"), mostra o novo.
  const text = (brToIso(draft) ?? '') === value ? draft : isoToBr(value)
  const ownError = left && text !== '' && brToIso(text) === null ? 'Informe uma data válida, como 13/11/2026.' : undefined
  const message = error ?? ownError
  const note = message ?? hint

  const view = fromIso(focusIso) ?? fromIso(todayIso())!

  function openCalendar() {
    setFocusIso(fromIso(value) ? value : todayIso())
    setOpen(true)
  }

  function close(returnFocus: boolean) {
    setOpen(false)
    if (returnFocus) toggle.current?.focus()
  }

  function pick(iso: string) {
    setDraft(isoToBr(iso))
    onChange(iso)
    close(true)
  }

  // Abrir leva o foco ao dia; mover o dia (teclado ou mês) leva junto.
  useEffect(() => {
    if (!open) return
    popover.current?.querySelector<HTMLElement>(`[data-iso="${focusIso}"]`)?.focus()
  }, [open, focusIso])

  // Se o calendário não cabe à direita do campo (campos lado a lado, tela estreita), abre alinhado à direita.
  useLayoutEffect(() => {
    if (!open || !root.current) return
    const rect = root.current.getBoundingClientRect()
    const room = Math.min(POPOVER_WIDTH, window.innerWidth - 32)
    setAlignRight(rect.left + room > window.innerWidth - 16 && rect.right - room >= 16)
  }, [open])

  // Tocar fora fecha, sem tirar o foco de onde a pessoa tocou.
  useEffect(() => {
    if (!open) return
    const onPointer = (event: PointerEvent) => {
      if (event.target instanceof Node && !root.current?.contains(event.target)) setOpen(false)
    }
    document.addEventListener('pointerdown', onPointer)
    return () => document.removeEventListener('pointerdown', onPointer)
  }, [open])

  function onDayKeyDown(event: KeyboardEvent<HTMLElement>) {
    const moves: Record<string, (iso: string) => string> = {
      ArrowLeft: (iso) => addDays(iso, -1),
      ArrowRight: (iso) => addDays(iso, 1),
      ArrowUp: (iso) => addDays(iso, -7),
      ArrowDown: (iso) => addDays(iso, 7),
      PageUp: (iso) => addMonths(iso, -1),
      PageDown: (iso) => addMonths(iso, 1),
    }
    const move = moves[event.key]
    if (!move) return
    event.preventDefault()
    setFocusIso(move(focusIso))
  }

  function onRootKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key !== 'Escape' || !open) return
    // Esc fecha só o calendário (não a folha em que o campo está).
    event.preventDefault()
    event.stopPropagation()
    close(true)
  }

  const weeks = monthGrid(view.year, view.month)
  const today = todayIso()

  return (
    <div className={`tx-field tx-datefield${message ? ' tx-field--invalid' : ''}`} ref={root} onKeyDown={onRootKeyDown}>
      <label className="tx-field__label text-label" htmlFor={inputId}>
        {label}
      </label>
      <div className="tx-datefield__anchor">
        <div className="tx-field__control">
          <input
            id={inputId}
            className="tx-field__input text-body tx-num"
            type="text"
            inputMode="numeric"
            autoComplete="off"
            placeholder="dd/mm/aaaa"
            maxLength={10}
            value={text}
            aria-invalid={message ? true : undefined}
            aria-describedby={note ? messageId : undefined}
            onChange={(event) => {
              const raw = event.target.value
              // Uma data completa em AAAA-MM-DD (colar, preencher por programa) vale como está; o resto segue a máscara.
              const masked = fromIso(raw) ? isoToBr(raw) : maskBr(raw)
              setDraft(masked)
              onChange(brToIso(masked) ?? '')
            }}
            onBlur={() => setLeft(true)}
          />
          <button
            ref={toggle}
            type="button"
            className="tx-datefield__toggle"
            aria-label={`Abrir calendário de ${label}`}
            aria-expanded={open}
            onClick={() => (open ? close(false) : openCalendar())}
          >
            <Icon name="calendar" size={20} />
          </button>
        </div>
        {open ? (
          <div
            ref={popover}
            role="dialog"
            aria-label={`Calendário de ${label}`}
            className={`tx-cal${alignRight ? ' tx-cal--right' : ''}`}
          >
            <div className="tx-cal__head">
              <span className="tx-cal__title text-label" aria-live="polite">
                {monthLabel(view.year, view.month)}
              </span>
              <IconButton icon="chevronLeft" label="Mês anterior" onClick={() => setFocusIso(addMonths(focusIso, -1))} />
              <IconButton icon="chevronRight" label="Próximo mês" onClick={() => setFocusIso(addMonths(focusIso, 1))} />
            </div>
            <table className="tx-cal__grid">
              <thead>
                <tr>
                  {WEEKDAY_INITIALS.map((initial, index) => (
                    <th key={index} scope="col" abbr={WEEKDAY_NAMES[index]} className="tx-cal__weekday text-label">
                      {initial}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {weeks.map((week) => (
                  <tr key={week[0].iso}>
                    {week.map((day) => {
                      const selected = day.iso === value
                      return (
                        <td key={day.iso}>
                          <button
                            type="button"
                            data-iso={day.iso}
                            className={`tx-cal__day tx-num${day.inMonth ? '' : ' tx-cal__day--out'}${selected ? ' tx-cal__day--selected' : ''}`}
                            tabIndex={day.iso === focusIso ? 0 : -1}
                            aria-label={longLabel(day.iso)}
                            aria-pressed={selected}
                            aria-current={day.iso === today ? 'date' : undefined}
                            onClick={() => pick(day.iso)}
                            onKeyDown={onDayKeyDown}
                          >
                            {day.day}
                          </button>
                        </td>
                      )
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="tx-cal__foot">
              <button
                type="button"
                className="tx-cal__link text-label"
                onClick={() => {
                  setDraft('')
                  onChange('')
                  close(true)
                }}
              >
                Limpar
              </button>
              <button type="button" className="tx-cal__link text-label" onClick={() => pick(today)}>
                Hoje
              </button>
            </div>
          </div>
        ) : null}
      </div>
      {note ? (
        <p id={messageId} className={`tx-field__msg text-body-sm${message ? ' tx-field__msg--error' : ''}`}>
          {message ? <Icon name="alert" size={16} /> : null}
          {note}
        </p>
      ) : null}
    </div>
  )
}
