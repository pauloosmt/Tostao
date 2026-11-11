import { useState, type FormEvent } from 'react'
import { Alert, Button, Field } from '../design-system/src'
import { describeFailure, type Failure } from '../forms/apiErrors'
import { useSubmit } from '../forms/useSubmit'
import { firstInvalid, type FieldMessages } from '../validation/account'
import {
  CATEGORY_API_FIELDS,
  CATEGORY_FIELD_ORDER,
  KIND_OPTIONS,
  validateCategoryForm,
  type CategoryField,
  type CategoryFormMode,
  type CategoryFormValues,
} from '../validation/categoryForm'
import { ButtonLink } from '../ui/ButtonLink'
import { ColorPicker } from '../ui/ColorPicker'
import { ErrorNotice } from '../ui/ErrorNotice'
import { SelectField } from '../ui/SelectField'
import { categoryConflict } from './conflicts'

const FIELD_ID: Record<CategoryField, string> = { name: 'category-name', kind: 'category-kind' }
const focusField = (field: CategoryField) => document.getElementById(FIELD_ID[field])?.focus()

const KIND_SELECT = KIND_OPTIONS.map((option) => ({ value: option.value, label: option.label }))

export interface CategoryFormProps {
  mode: CategoryFormMode
  initial: CategoryFormValues
  submitLabel: string
  /** Envia os valores já validados; falha com o erro da API (ou de rede), que o formulário mostra. Em caso de sucesso, quem chama navega. */
  onSubmit: (values: CategoryFormValues) => Promise<void>
  /** Para onde "Cancelar" volta (a lista, abrindo o tipo em que a pessoa estava). */
  cancelTo: string
}

export function CategoryForm({ mode, initial, submitLabel, onSubmit, cancelTo }: CategoryFormProps) {
  const [values, setValues] = useState(initial)
  const [errors, setErrors] = useState<FieldMessages<CategoryField>>({})
  const [failure, setFailure] = useState<Failure<CategoryField> | null>(null)
  const { submitting, run } = useSubmit()

  async function submit() {
    const found = validateCategoryForm(values, mode)
    setErrors(found)
    setFailure(null)
    const first = firstInvalid(found, CATEGORY_FIELD_ORDER)
    if (first) {
      focusField(first)
      return
    }
    await run(async () => {
      try {
        await onSubmit(values)
      } catch (error) {
        const described = describeFailure<CategoryField>(error, { fieldMap: CATEGORY_API_FIELDS, conflict: categoryConflict })
        setErrors(described.fields)
        setFailure(described)
        const firstFromApi = firstInvalid(described.fields, CATEGORY_FIELD_ORDER)
        if (firstFromApi) focusField(firstFromApi)
      }
    })
  }

  function onFormSubmit(event: FormEvent) {
    event.preventDefault()
    void submit()
  }

  const kindHint = mode === 'sub' ? 'Herdado da categoria principal.' : mode === 'edit' ? 'O tipo não pode ser mudado depois de criada.' : undefined

  return (
    <form className="stack" onSubmit={onFormSubmit} noValidate>
      {failure?.general ? (
        failure.retry ? <ErrorNotice onRetry={() => void submit()}>{failure.general}</ErrorNotice> : <Alert tone="danger">{failure.general}</Alert>
      ) : null}

      <Field
        id={FIELD_ID.name}
        label="Nome"
        autoComplete="off"
        value={values.name}
        onChange={(event) => setValues((current) => ({ ...current, name: event.target.value }))}
        error={errors.name}
      />

      <SelectField
        id={FIELD_ID.kind}
        label="Tipo"
        options={KIND_SELECT}
        placeholder={mode === 'principal' ? 'Escolha o tipo' : undefined}
        value={values.kind}
        onChange={(event) => setValues((current) => ({ ...current, kind: event.target.value as CategoryFormValues['kind'] }))}
        disabled={mode !== 'principal'}
        hint={kindHint}
        error={errors.kind}
      />

      <ColorPicker label="Cor" value={values.color} onChange={(color) => setValues((current) => ({ ...current, color }))} />

      <div className="form-actions">
        <ButtonLink to={cancelTo} state={{ kind: values.kind }}>
          Cancelar
        </ButtonLink>
        <Button type="submit" disabled={submitting}>
          {submitting ? 'Salvando…' : submitLabel}
        </Button>
      </div>
    </form>
  )
}
