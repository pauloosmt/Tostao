// Uma API de mentira COM MEMÓRIA para lançamentos, que se comporta como o contrato (specs 004 e 009): lista paginada e filtrada, criar, editar,
// efetivar e excluir, com a transferência de duas pontas. Serve aos testes de tela; os testes dela mesma (fakeTransactions.test.ts) provam que
// ela segue o contrato. Não valida a existência de contas e categorias (quem precisa dessas recusas registra a resposta no teste).
import type { CategoryTree, CreateTransaction, Transaction, UpdateTransaction } from '../api/types'
import { noContent, ok, problem, wsRoute, type Call, type FakeApi, type Reply } from './fakeApi'
import { makeTransaction } from './fixtures'

export interface FakeTransactions {
  /** Todas as pontas, na ordem em que foram criadas (a tela só vê o que a API devolve). */
  store: Transaction[]
}

interface Options {
  workspaceId: string
  /** Para o filtro por categoria principal incluir as subcategorias. */
  categories?: CategoryTree[]
}

interface FieldError {
  field: string
  message: string
}

const MAX_CENTS = 1_000_000_000_000
const invalid = (errors: FieldError[]): Reply => problem(400, 'Requisição inválida', { detail: 'Um ou mais campos são inválidos', errors })
const notFound = (): Reply => problem(404, 'Não encontrado', { detail: 'Lançamento não encontrado' })

function isDate(text: unknown): boolean {
  if (typeof text !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(text)) return false
  const [y, m, d] = text.split('-').map(Number)
  const date = new Date(2000, 0, 1)
  date.setFullYear(y, m - 1, d)
  return date.getFullYear() === y && date.getMonth() === m - 1 && date.getDate() === d
}

// O que a validação lê do corpo (de criar ou de editar); o que a requisição trouxer de fora do contrato é conferido campo a campo.
interface Body {
  accountId?: string
  destinationAccountId?: string
  amountCents?: number
  occurredOn?: string
  description?: string
  notes?: string
  categoryId?: string
  status?: string
}

function validate(body: Body, type: string | undefined): FieldError[] {
  const errors: FieldError[] = []
  if (body.accountId === undefined || body.accountId === '') errors.push({ field: 'accountId', message: 'é obrigatório' })
  const amount = body.amountCents
  if (!Number.isInteger(amount) || (amount as number) < 1 || (amount as number) > MAX_CENTS) errors.push({ field: 'amountCents', message: 'deve ser de 1 a 1.000.000.000.000 centavos' })
  if (!isDate(body.occurredOn)) errors.push({ field: 'occurredOn', message: 'data inválida' })
  else {
    const year = Number((body.occurredOn as string).slice(0, 4))
    if (year < 2000 || year > 2100) errors.push({ field: 'occurredOn', message: 'o ano deve ser de 2000 a 2100' })
  }
  const description = (body.description ?? '').trim()
  if (description === '') errors.push({ field: 'description', message: 'é obrigatório' })
  else if (description.length > 200) errors.push({ field: 'description', message: 'deve ter no máximo 200 caracteres' })
  if ((body.notes ?? '').length > 1000) errors.push({ field: 'notes', message: 'deve ter no máximo 1.000 caracteres' })
  if (type === 'TRANSFER') {
    if (!body.destinationAccountId) errors.push({ field: 'destinationAccountId', message: 'é obrigatório na transferência' })
    else if (body.destinationAccountId === body.accountId) errors.push({ field: 'destinationAccountId', message: 'deve ser diferente da origem' })
    if (body.categoryId) errors.push({ field: 'categoryId', message: 'não vale para transferência' })
  } else {
    if (!body.categoryId) errors.push({ field: 'categoryId', message: 'é obrigatório' })
    if (body.destinationAccountId) errors.push({ field: 'destinationAccountId', message: 'só vale para transferência' })
  }
  if (body.status !== undefined && body.status !== 'PENDING' && body.status !== 'CLEARED') errors.push({ field: 'status', message: 'deve ser CLEARED ou PENDING' })
  return errors
}

/** Liga as rotas de lançamentos do workspace ao `fakeApi` dado, com a memória `initial`. */
export function installTransactions(api: FakeApi, initial: Transaction[], { workspaceId, categories = [] }: Options): FakeTransactions {
  const store: Transaction[] = initial.map((tx) => ({ ...tx }))
  let sequence = 0
  const nextId = () => `fake-tx-${++sequence}`
  const prefix = `/api/v1/workspaces/${workspaceId}/transactions`
  const find = (id: string) => store.find((tx) => tx.id === id)
  const groupOf = (tx: Transaction) => (tx.transferGroupId === null ? [tx] : store.filter((other) => other.transferGroupId === tx.transferGroupId))

  const categoryIds = (id: string): Set<string> => {
    const ids = new Set([id])
    const parent = categories.find((category) => category.id === id)
    for (const sub of parent?.subcategories ?? []) ids.add(sub.id)
    return ids
  }

  function list(call: Call): Reply {
    const query = new URLSearchParams(call.path.split('?')[1] ?? '')
    const errors: FieldError[] = []
    const page = Number(query.get('page') ?? 1)
    const size = Number(query.get('size') ?? 20)
    if (!Number.isInteger(page) || page < 1) errors.push({ field: 'page', message: 'deve ser 1 ou mais' })
    if (!Number.isInteger(size) || size < 1 || size > 100) errors.push({ field: 'size', message: 'deve ser de 1 a 100' })
    const from = query.get('from')
    const to = query.get('to')
    if (from !== null && !isDate(from)) errors.push({ field: 'from', message: 'data inválida' })
    if (to !== null && !isDate(to)) errors.push({ field: 'to', message: 'data inválida' })
    if (from !== null && to !== null && isDate(from) && isDate(to) && to < from) errors.push({ field: 'from', message: 'não pode ser anterior à data inicial' })
    const q = query.get('q')
    if (q !== null && q.length > 100) errors.push({ field: 'q', message: 'deve ter no máximo 100 caracteres' })
    const type = query.get('type')
    if (type !== null && !['INCOME', 'EXPENSE', 'TRANSFER'].includes(type)) errors.push({ field: 'type', message: 'tipo inválido' })
    const status = query.get('status')
    if (status !== null && !['CLEARED', 'PENDING'].includes(status)) errors.push({ field: 'status', message: 'estado inválido' })
    if (errors.length > 0) return invalid(errors)

    const accountId = query.get('accountId')
    const categoryId = query.get('categoryId')
    const wanted = categoryId === null ? null : categoryIds(categoryId)
    const needle = q?.toLowerCase() ?? null // `%` e `_` valem literalmente: é uma busca por trecho, não um padrão
    const matches = store
      .filter((tx) => (from === null || tx.occurredOn >= from) && (to === null || tx.occurredOn <= to))
      .filter((tx) => accountId === null || tx.accountId === accountId)
      .filter((tx) => wanted === null || (tx.categoryId !== null && wanted.has(tx.categoryId)))
      .filter((tx) => type === null || tx.type === type)
      .filter((tx) => status === null || tx.status === status)
      .filter((tx) => needle === null || tx.description.toLowerCase().includes(needle))
      .sort((a, b) => b.occurredOn.localeCompare(a.occurredOn) || b.createdAt.localeCompare(a.createdAt) || b.id.localeCompare(a.id))
    const totalItems = matches.length
    return ok({
      items: matches.slice((page - 1) * size, page * size),
      page,
      size,
      totalItems,
      totalPages: Math.ceil(totalItems / size),
    })
  }

  function create(call: Call): Reply {
    const body = call.body as CreateTransaction
    const errors = validate(body, body.type)
    if (!['INCOME', 'EXPENSE', 'TRANSFER'].includes(body.type)) errors.push({ field: 'type', message: 'tipo inválido' })
    if (errors.length > 0) return invalid(errors)

    const now = new Date(2026, 0, 1, 0, 0, ++sequence).toISOString()
    const common = {
      occurredOn: body.occurredOn,
      description: body.description.trim(),
      notes: body.notes === undefined || body.notes.trim() === '' ? null : body.notes.trim(),
      status: body.status === 'PENDING' ? ('PENDING' as const) : ('CLEARED' as const),
      createdAt: now,
      updatedAt: now,
    }
    if (body.type === 'TRANSFER') {
      const group = `fake-group-${++sequence}`
      const out = makeTransaction({ id: nextId(), type: 'TRANSFER', accountId: body.accountId, counterpartAccountId: body.destinationAccountId ?? null, amountCents: -body.amountCents, categoryId: null, transferGroupId: group, ...common })
      const into = makeTransaction({ id: nextId(), type: 'TRANSFER', accountId: body.destinationAccountId as string, counterpartAccountId: body.accountId, amountCents: body.amountCents, categoryId: null, transferGroupId: group, ...common })
      store.push(out, into)
      return ok(out, 201)
    }
    const created = makeTransaction({
      id: nextId(),
      type: body.type,
      accountId: body.accountId,
      amountCents: body.type === 'INCOME' ? body.amountCents : -body.amountCents,
      categoryId: body.categoryId ?? null,
      ...common,
    })
    store.push(created)
    return ok(created, 201)
  }

  function update(call: Call, id: string): Reply {
    const current = find(id)
    if (!current) return notFound()
    const body = call.body as UpdateTransaction & { type?: string }
    const errors = validate(body, current.type)
    if (body.type !== undefined && body.type !== current.type) errors.push({ field: 'type', message: 'o tipo não pode ser mudado' })
    if (errors.length > 0) return invalid(errors)

    const changed = {
      occurredOn: body.occurredOn,
      description: body.description.trim(),
      notes: body.notes === undefined || body.notes.trim() === '' ? null : body.notes.trim(),
      updatedAt: new Date(2026, 6, 1, 0, 0, ++sequence).toISOString(),
    }
    const status = body.status ?? current.status
    if (current.type === 'TRANSFER') {
      const ends = groupOf(current)
      const out = ends.find((end) => end.amountCents < 0) as Transaction
      const into = ends.find((end) => end.amountCents > 0) as Transaction
      Object.assign(out, changed, { status, accountId: body.accountId, counterpartAccountId: body.destinationAccountId ?? null, amountCents: -body.amountCents })
      Object.assign(into, changed, { status, accountId: body.destinationAccountId as string, counterpartAccountId: body.accountId, amountCents: body.amountCents })
      return ok(current)
    }
    Object.assign(current, changed, {
      status,
      accountId: body.accountId,
      categoryId: body.categoryId ?? null,
      amountCents: current.type === 'INCOME' ? body.amountCents : -body.amountCents,
    })
    return ok(current)
  }

  api.on(wsRoute('GET', workspaceId, '/transactions'), list)
  api.on(wsRoute('POST', workspaceId, '/transactions'), create)
  const idPattern = (suffix = '') => new RegExp(`^${prefix}/([^/]+)${suffix}$`)
  api.onMatch('GET', idPattern(), (_call, match) => {
    const tx = find(match[1])
    return tx ? ok(tx) : notFound()
  })
  api.onMatch('PUT', idPattern(), (call, match) => update(call, match[1]))
  api.onMatch('POST', idPattern('/clear'), (_call, match) => {
    const tx = find(match[1])
    if (!tx) return notFound()
    for (const end of groupOf(tx)) end.status = 'CLEARED'
    return ok(tx)
  })
  api.onMatch('DELETE', idPattern(), (_call, match) => {
    const tx = find(match[1])
    if (!tx) return notFound()
    const gone = new Set(groupOf(tx).map((end) => end.id))
    for (let i = store.length - 1; i >= 0; i--) if (gone.has(store[i].id)) store.splice(i, 1)
    return noContent()
  })

  return { store }
}
