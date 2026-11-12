import { useState } from 'react'
import type { CategoryKind } from '../api/types'
import { Alert, Button, CATEGORY_COLORS, CategoryCoin, Field, IconPicker, OptionTile, SwatchPicker } from '../design-system/src'
import { describeFailure, type Failure } from '../forms/apiErrors'
import { useSubmit } from '../forms/useSubmit'
import { firstInvalid, type FieldMessages } from '../validation/account'
import {
  CATEGORY_API_FIELDS,
  CATEGORY_FIELD_ORDER,
  KIND_OPTIONS,
  resolveIcon,
  validateCategoryForm,
  type CategoryField,
  type CategoryFormMode,
  type CategoryFormValues,
} from '../validation/categoryForm'
import { ErrorNotice } from '../ui/ErrorNotice'
import { SheetDialog } from '../ui/SheetDialog'
import { categoryConflict } from './conflicts'

const FIELD_ID: Record<CategoryField, string> = { name: 'category-name', kind: 'category-kind' }
const focusField = (field: CategoryField) => document.getElementById(FIELD_ID[field])?.focus()

/** Uma categoria principal ativa, para escolher como mãe. */
export interface ParentOption {
  id: string
  name: string
  kind: CategoryKind
  color: string | null
}

export interface CategoryFormProps {
  /** `create`: uma categoria nova (a pessoa escolhe o tipo, a cor e, se quiser, a mãe). `edit`: o tipo e a mãe não mudam. */
  mode: 'create' | 'edit'
  title: string
  initial: CategoryFormValues
  /** As principais ativas que podem ser a mãe (só na criação). */
  parents?: ParentOption[]
  /** A mãe já escolhida: ao criar pela mãe, ou ao editar uma subcategoria. */
  initialParent?: ParentOption | null
  /** Com ela, a mãe não se troca (criar pela mãe e editar). */
  lockParent?: boolean
  submitLabel: string
  /** Envia os valores já validados e a mãe escolhida (ou `null`); falha com o erro da API, que o formulário mostra. Em caso de sucesso, quem chama fecha a folha. */
  onSubmit: (values: CategoryFormValues, parent: ParentOption | null) => Promise<void>
  onClose: () => void
}

/** A folha da categoria e da subcategoria: a moeda ao vivo, o nome, o tipo, a mãe, a cor e o ícone. */
export function CategoryForm({ mode, title, initial, parents = [], initialParent = null, lockParent = false, submitLabel, onSubmit, onClose }: CategoryFormProps) {
  const [values, setValues] = useState<CategoryFormValues>(() => ({ ...initial, color: initial.color ?? (mode === 'create' ? CATEGORY_COLORS[0] : null) }))
  const [parent, setParent] = useState<ParentOption | null>(initialParent)
  const [errors, setErrors] = useState<FieldMessages<CategoryField>>({})
  const [failure, setFailure] = useState<Failure<CategoryField> | null>(null)
  const { submitting, run } = useSubmit()

  const set = <K extends keyof CategoryFormValues>(field: K, value: CategoryFormValues[K]) => setValues((current) => ({ ...current, [field]: value }))
  const formMode: CategoryFormMode = mode === 'edit' ? 'edit' : parent ? 'sub' : 'principal'
  const kind = parent ? parent.kind : values.kind
  const coinColor = parent ? (parent.color ?? undefined) : (values.color ?? undefined)
  const icon = resolveIcon(values.icon, values.name)
  const shown = values.name.trim() || 'Nome da categoria'
  const candidates = parents.filter((option) => option.kind === kind)

  async function submit() {
    const found = validateCategoryForm({ ...values, kind }, formMode)
    setErrors(found)
    setFailure(null)
    const first = firstInvalid(found, CATEGORY_FIELD_ORDER)
    if (first) {
      focusField(first)
      return
    }
    await run(async () => {
      try {
        await onSubmit({ ...values, kind }, parent)
      } catch (error) {
        const described = describeFailure<CategoryField>(error, { fieldMap: CATEGORY_API_FIELDS, conflict: categoryConflict })
        setErrors(described.fields)
        setFailure(described)
        const firstFromApi = firstInvalid(described.fields, CATEGORY_FIELD_ORDER)
        if (firstFromApi) focusField(firstFromApi)
      }
    })
  }

  return (
    <SheetDialog
      title={title}
      onClose={onClose}
      onSubmit={() => void submit()}
      initialFocusId={FIELD_ID.name}
      footer={
        <Button type="submit" size="lg" full disabled={submitting}>
          {submitting ? 'Salvando…' : submitLabel}
        </Button>
      }
    >
      {failure?.general ? (
        failure.retry ? <ErrorNotice onRetry={() => void submit()}>{failure.general}</ErrorNotice> : <Alert tone="danger">{failure.general}</Alert>
      ) : null}

      <div className="category-preview">
        <CategoryCoin name={shown} color={coinColor} icon={icon} variant={parent ? 'outline' : 'solid'} size={64} />
        <div>
          <p className="title">{shown}</p>
          <p className="text-body-sm muted">{parent ? `Subcategoria de ${parent.name}` : 'Categoria'}</p>
        </div>
      </div>

      <Field
        id={FIELD_ID.name}
        label="Nome"
        autoComplete="off"
        hint="O ícone acompanha o nome até você escolher um."
        value={values.name}
        onChange={(event) => set('name', event.target.value)}
        error={errors.name}
      />

      {mode === 'create' && !parent ? (
        <div className="tx-sheet__section">
          <span className="text-label">Tipo</span>
          <div className="tile-row" role="radiogroup" aria-label="Tipo da categoria">
            {KIND_OPTIONS.map((option) => (
              <OptionTile key={option.value} selected={values.kind === option.value} onClick={() => set('kind', option.value)}>
                {option.label}
              </OptionTile>
            ))}
          </div>
          {errors.kind ? <p className="tx-field__msg tx-field__msg--error text-body-sm" role="alert">{errors.kind}</p> : null}
        </div>
      ) : (
        <p className="text-body-sm muted">{kind === 'INCOME' ? 'Receita' : 'Despesa'}. {parent ? 'Herdado da categoria principal.' : 'O tipo não pode ser mudado depois de criada.'}</p>
      )}

      {mode === 'create' && !lockParent ? (
        <div className="tx-sheet__section">
          <span className="text-label">Fica dentro de</span>
          <div className="tile-row tile-row--chips" role="radiogroup" aria-label="Categoria mãe">
            <OptionTile compact selected={parent === null} onClick={() => setParent(null)}>
              Nenhuma
            </OptionTile>
            {candidates.map((option) => (
              <OptionTile key={option.id} compact selected={parent?.id === option.id} onClick={() => setParent(option)}>
                {option.name}
              </OptionTile>
            ))}
          </div>
        </div>
      ) : null}

      <div className="tx-sheet__section">
        <span className="text-label">Cor</span>
        {parent ? (
          <p className="text-body-sm muted">Usa a cor de {parent.name}, a categoria mãe.</p>
        ) : (
          <SwatchPicker value={values.color ?? undefined} onChange={(color) => set('color', color)} />
        )}
      </div>

      <div className="tx-sheet__section">
        <span className="text-label">Ícone</span>
        <IconPicker name={values.name} color={coinColor} value={icon} onChange={(picked) => set('icon', picked)} />
      </div>
    </SheetDialog>
  )
}
