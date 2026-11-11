import { useState, type FormEvent } from 'react'
import { Alert, Button, Field } from '../design-system/src'
import { describeFailure, type Failure } from '../forms/apiErrors'
import { useSubmit } from '../forms/useSubmit'
import {
  ACCOUNT_API_FIELDS,
  ACCOUNT_FIELD_ORDER,
  validateAccountForm,
  type AccountField,
  type AccountFormValues,
} from '../validation/accountForm'
import { firstInvalid, type FieldMessages } from '../validation/account'
import { ButtonLink } from '../ui/ButtonLink'
import { ErrorNotice } from '../ui/ErrorNotice'
import { MoneyField } from '../ui/MoneyField'
import { SelectField } from '../ui/SelectField'
import { ACCOUNT_TYPE_OPTIONS, isCreditCard } from './accountTypes'

const FIELD_ID: Record<AccountField, string> = {
  name: 'account-name',
  type: 'account-type',
  balance: 'account-balance',
  limit: 'account-limit',
  closingDay: 'account-closing-day',
  dueDay: 'account-due-day',
}
const focusField = (field: AccountField) => document.getElementById(FIELD_ID[field])?.focus()

export interface AccountFormProps {
  mode: 'create' | 'edit'
  initial: AccountFormValues
  submitLabel: string
  /** Envia os valores já validados; falha com o erro da API (ou de rede), que o formulário mostra. Em caso de sucesso, quem chama navega. */
  onSubmit: (values: AccountFormValues) => Promise<void>
  /** Para onde "Cancelar" volta. */
  cancelTo: string
}

export function AccountForm({ mode, initial, submitLabel, onSubmit, cancelTo }: AccountFormProps) {
  const [values, setValues] = useState(initial)
  const [errors, setErrors] = useState<FieldMessages<AccountField>>({})
  const [failure, setFailure] = useState<Failure<AccountField> | null>(null)
  const { submitting, run } = useSubmit()

  const set = <K extends keyof AccountFormValues>(field: K, value: AccountFormValues[K]) => setValues((current) => ({ ...current, [field]: value }))

  // Trocar para um tipo que não é cartão descarta o que foi digitado nos campos do cartão: nada disso é enviado nem volta se o cartão voltar.
  function changeType(type: AccountFormValues['type']) {
    setValues((current) => ({ ...current, type, ...(isCreditCard(type) ? {} : { limit: '', closingDay: '', dueDay: '' }) }))
  }

  async function submit() {
    const found = validateAccountForm(values, mode)
    setErrors(found)
    setFailure(null)
    const first = firstInvalid(found, ACCOUNT_FIELD_ORDER)
    if (first) {
      focusField(first)
      return
    }
    await run(async () => {
      try {
        await onSubmit(values)
      } catch (error) {
        const described = describeFailure<AccountField>(error, {
          fieldMap: ACCOUNT_API_FIELDS,
          conflictField: 'name',
          conflictMessage: 'Já existe uma conta ativa com este nome.',
        })
        setErrors(described.fields)
        setFailure(described)
        const firstFromApi = firstInvalid(described.fields, ACCOUNT_FIELD_ORDER)
        if (firstFromApi) focusField(firstFromApi)
      }
    })
  }

  function onFormSubmit(event: FormEvent) {
    event.preventDefault()
    void submit()
  }

  const card = isCreditCard(values.type)

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
        onChange={(event) => set('name', event.target.value)}
        error={errors.name}
      />

      <SelectField
        id={FIELD_ID.type}
        label="Tipo"
        options={ACCOUNT_TYPE_OPTIONS}
        placeholder={mode === 'create' ? 'Escolha o tipo' : undefined}
        value={values.type}
        onChange={(event) => changeType(event.target.value as AccountFormValues['type'])}
        disabled={mode === 'edit'}
        hint={mode === 'edit' ? 'O tipo não pode ser mudado depois de criada.' : undefined}
        error={errors.type}
      />

      <MoneyField
        id={FIELD_ID.balance}
        label="Saldo inicial"
        hint="Pode ser negativo, por exemplo -200,00."
        value={values.balance}
        onChange={(text) => set('balance', text)}
        error={errors.balance}
      />

      {card ? (
        <>
          <MoneyField
            id={FIELD_ID.limit}
            label="Limite (opcional)"
            value={values.limit}
            onChange={(text) => set('limit', text)}
            error={errors.limit}
          />
          <div className="form-row">
            <Field
              id={FIELD_ID.closingDay}
              label="Dia de fechamento (opcional)"
              inputMode="numeric"
              autoComplete="off"
              numeric
              value={values.closingDay}
              onChange={(event) => set('closingDay', event.target.value)}
              error={errors.closingDay}
            />
            <Field
              id={FIELD_ID.dueDay}
              label="Dia de vencimento (opcional)"
              inputMode="numeric"
              autoComplete="off"
              numeric
              value={values.dueDay}
              onChange={(event) => set('dueDay', event.target.value)}
              error={errors.dueDay}
            />
          </div>
        </>
      ) : null}

      <div className="form-actions">
        <ButtonLink to={cancelTo}>Cancelar</ButtonLink>
        <Button type="submit" disabled={submitting}>
          {submitting ? 'Salvando…' : submitLabel}
        </Button>
      </div>
    </form>
  )
}
