import { useState, type ChangeEvent } from 'react'
import { useNavigate } from 'react-router'
import { confirmImport, previewStatement } from '../api/imports'
import type { Account, ImportLinePreview, ImportPreview } from '../api/types'
import { formatDate } from '../dates/dates'
import { Alert, Badge, Button, Card, PageHeader, TransactionRow } from '../design-system/src'
import { describeFailure } from '../forms/apiErrors'
import { useSubmit } from '../forms/useSubmit'
import { ButtonLink } from '../ui/ButtonLink'
import { ErrorNotice } from '../ui/ErrorNotice'
import type { ReferenceData } from '../transactions/reference'
import { validateImportFile } from '../validation/importFile'
import { isSelectable } from './selectable'

const FILE_ID = 'import-file'

export interface ImportFlowProps {
  workspaceId: string
  account: Account
  reference: ReferenceData
}

/** Os dois passos: o arquivo (e a prévia, que não grava nada) e a escolha das linhas com a confirmação. */
export function ImportFlow({ workspaceId, account, reference }: ImportFlowProps) {
  const [preview, setPreview] = useState<ImportPreview | null>(null)
  return (
    <>
      <PageHeader title="Importar extrato" context={account.name} />
      {preview === null ? (
        <FileStep workspaceId={workspaceId} accountId={account.id} onPreview={setPreview} />
      ) : (
        <PreviewStep workspaceId={workspaceId} accountId={account.id} preview={preview} reference={reference} onBack={() => setPreview(null)} />
      )}
    </>
  )
}

interface FileStepProps {
  workspaceId: string
  accountId: string
  onPreview: (preview: ImportPreview) => void
}

function FileStep({ workspaceId, accountId, onPreview }: FileStepProps) {
  const [file, setFile] = useState<File | null>(null)
  const [error, setError] = useState<string | undefined>()
  const [problem, setProblem] = useState<{ text: string; retry: boolean } | null>(null)
  const { submitting, run } = useSubmit()

  async function submit() {
    setProblem(null)
    const found = validateImportFile(file)
    setError(found)
    if (found || file === null) {
      document.getElementById(FILE_ID)?.focus()
      return
    }
    await run(async () => {
      try {
        onPreview(await previewStatement(workspaceId, accountId, file))
      } catch (failure) {
        const described = describeFailure<'file'>(failure, { fieldMap: { file: 'file' } })
        if (described.fields.file) {
          setError(described.fields.file)
          document.getElementById(FILE_ID)?.focus()
        } else {
          setProblem({ text: described.general ?? 'Não foi possível ler o extrato.', retry: described.retry })
        }
      }
    })
  }

  return (
    <Card>
      <form
        className="stack"
        noValidate
        onSubmit={(event) => {
          event.preventDefault()
          void submit()
        }}
      >
        {problem ? problem.retry ? <ErrorNotice onRetry={() => void submit()}>{problem.text}</ErrorNotice> : <Alert tone="danger">{problem.text}</Alert> : null}
        <p className="text-body">
          Envie o extrato da conta: um arquivo <strong>OFX</strong>, um <strong>CSV</strong> com as colunas data, descrição e valor, ou um <strong>PDF</strong>, de até 2 MB. Você revê tudo antes de importar.
        </p>
        <div className={`tx-field${error ? ' tx-field--invalid' : ''}`}>
          <label className="tx-field__label text-label" htmlFor={FILE_ID}>
            Arquivo do extrato
          </label>
          <input
            id={FILE_ID}
            className="file-input text-body"
            type="file"
            accept=".ofx,.qfx,.csv,.pdf,.txt"
            aria-invalid={error ? true : undefined}
            aria-describedby={error ? `${FILE_ID}-msg` : undefined}
            onChange={(event: ChangeEvent<HTMLInputElement>) => {
              setFile(event.target.files?.[0] ?? null)
              setError(undefined)
            }}
          />
          {error ? (
            <p id={`${FILE_ID}-msg`} className="tx-field__msg tx-field__msg--error text-body-sm">
              {error}
            </p>
          ) : null}
        </div>
        <div className="form-actions">
          <ButtonLink to="/contas">Cancelar</ButtonLink>
          <Button type="submit" disabled={submitting}>
            {submitting ? 'Lendo…' : 'Ler extrato'}
          </Button>
        </div>
      </form>
    </Card>
  )
}

interface PreviewStepProps {
  workspaceId: string
  accountId: string
  preview: ImportPreview
  reference: ReferenceData
  onBack: () => void
}

function PreviewStep({ workspaceId, accountId, preview, reference, onBack }: PreviewStepProps) {
  const navigate = useNavigate()
  const { submitting, run } = useSubmit()
  const [problem, setProblem] = useState<{ text: string; retry: boolean } | null>(null)

  // A categoria sugerida só vale se ainda serve ao tipo da linha (ativa e de despesa ou receita, conforme o sinal).
  const kindOf = (line: ImportLinePreview) => ((line.amountCents ?? 0) > 0 ? 'INCOME' : 'EXPENSE')
  const initialCategory = (line: ImportLinePreview) => (line.suggestedCategoryId && reference.categoryFits(line.suggestedCategoryId, kindOf(line)) ? line.suggestedCategoryId : '')
  const [selected, setSelected] = useState<Set<number>>(() => new Set(preview.lines.flatMap((line, index) => (isSelectable(line) ? [index] : []))))
  const [categories, setCategories] = useState<Record<number, string>>(() => Object.fromEntries(preview.lines.map((line, index) => [index, initialCategory(line)])))

  const toggle = (index: number) =>
    setSelected((current) => {
      const next = new Set(current)
      if (next.has(index)) next.delete(index)
      else next.add(index)
      return next
    })

  async function submit() {
    setProblem(null)
    const lines = preview.lines.flatMap((line, index) =>
      selected.has(index) && isSelectable(line)
        ? [{ key: line.key as string, occurredOn: line.occurredOn as string, description: line.description, amountCents: line.amountCents as number, ...(categories[index] ? { categoryId: categories[index] } : {}) }]
        : [],
    )
    await run(async () => {
      try {
        const { created, skipped } = await confirmImport(workspaceId, accountId, lines)
        const imported = `${created.length} ${created.length === 1 ? 'lançamento importado' : 'lançamentos importados'}.`
        const ignored = skipped > 0 ? ` ${skipped} ${skipped === 1 ? 'já existia e foi ignorada' : 'já existiam e foram ignoradas'}.` : ''
        void navigate('/lancamentos', { state: { notice: imported + ignored } })
      } catch (failure) {
        const described = describeFailure(failure)
        setProblem({ text: described.general ?? 'Não foi possível importar.', retry: described.retry })
      }
    })
  }

  return (
    <div className="stack">
      {problem ? problem.retry ? <ErrorNotice onRetry={() => void submit()}>{problem.text}</ErrorNotice> : <Alert tone="danger">{problem.text}</Alert> : null}

      <Card>
        <div className="stack stack--tight">
          <p className="title">
            {preview.total} {preview.total === 1 ? 'linha' : 'linhas'} <Badge tone="neutral">{preview.format}</Badge>
          </p>
          <p className="text-body-sm muted">
            {preview.valid} {preview.valid === 1 ? 'nova' : 'novas'} · {preview.existing} já {preview.existing === 1 ? 'existente' : 'existentes'} · {preview.invalid} com problema
          </p>
          <div className="profile-actions">
            <Button size="sm" variant="secondary" onClick={() => setSelected(new Set(preview.lines.flatMap((line, index) => (isSelectable(line) ? [index] : []))))}>
              Marcar todas as novas
            </Button>
            <Button size="sm" variant="secondary" onClick={() => setSelected(new Set())}>
              Desmarcar todas
            </Button>
          </div>
        </div>
      </Card>

      <ul className="import-list" aria-label="Linhas do extrato">
        {preview.lines.map((line, index) => {
          const ok = isSelectable(line)
          const cents = line.amountCents ?? 0
          const options = reference.categoryOptions(kindOf(line))
          return (
            <li key={line.line} className={`import-row${ok ? '' : ' is-off'}`}>
              <input
                type="checkbox"
                className="import-row__check"
                aria-label={`Importar ${line.description}`}
                checked={selected.has(index) && ok}
                disabled={!ok}
                onChange={() => toggle(index)}
              />
              <div className="import-row__main">
                <TransactionRow
                  description={line.description || 'Sem descrição'}
                  cents={Math.abs(cents)}
                  kind={cents > 0 ? 'income' : 'expense'}
                  date={line.occurredOn ? formatDate(line.occurredOn) : 'Sem data'}
                  tags={[...(line.existing ? ['Já existe'] : []), ...(line.possibleDuplicate ? ['Possível duplicata'] : [])]}
                />
                {line.problems.length > 0 ? <p className="text-body-sm import-row__problem">{line.problems.join(' ')}</p> : null}
              </div>
              {ok ? (
                <select
                  className="tx-field__input text-body-sm import-row__category"
                  aria-label={`Categoria de ${line.description}`}
                  value={categories[index] ?? ''}
                  onChange={(event) => setCategories((current) => ({ ...current, [index]: event.target.value }))}
                >
                  <option value="">Sem categoria</option>
                  {options.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
              ) : null}
            </li>
          )
        })}
      </ul>

      <div className="form-actions">
        <Button variant="secondary" onClick={onBack} disabled={submitting}>
          Escolher outro arquivo
        </Button>
        <Button disabled={submitting || selected.size === 0} onClick={() => void submit()}>
          {submitting ? 'Importando…' : `Importar ${selected.size} ${selected.size === 1 ? 'lançamento' : 'lançamentos'}`}
        </Button>
      </div>
    </div>
  )
}
