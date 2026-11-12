import { Navigate, useLocation, useNavigate, useOutletContext, useParams } from 'react-router'
import { ApiError } from '../api/errors'
import { createCategory, getCategory, updateCategory } from '../api/categories'
import type { Category, CategoryKind, CategoryTree } from '../api/types'
import { CATEGORY_COLORS } from '../design-system/src'
import { useResource } from '../data/useResource'
import { FAILURE_MESSAGES } from '../forms/apiErrors'
import { usePermissions } from '../permissions/usePermissions'
import { ErrorNotice } from '../ui/ErrorNotice'
import { LoadingNote } from '../ui/LoadingNote'
import { SheetDialog } from '../ui/SheetDialog'
import { buildCreateCategory, buildUpdateCategory, categoryToForm, emptyCategoryForm } from '../validation/categoryForm'
import { CategoryForm, type ParentOption } from './CategoryForm'

const BACK = '/categorias'

/** O que a lista entrega às folhas de criar e editar categoria, que são suas rotas filhas. */
export interface CategorySheetContext {
  workspaceId: string
  /** As categorias que a lista mostra (as principais com as subcategorias). */
  categories: CategoryTree[]
  /** Avisa que uma categoria mudou: a lista, que continua atrás da folha, se atualiza. */
  changed: () => void
}

const useSheetContext = () => useOutletContext<CategorySheetContext>()

const toParent = (category: Category): ParentOption => ({ id: category.id, name: category.name, kind: category.kind, color: category.color })
const readOnly = (what: string) => ({ notice: `Este workspace é só para consulta: você não pode ${what} categorias.` })

/** `/categorias/nova`: cria uma categoria, ou, escolhendo "Fica dentro de", uma subcategoria. O tipo já vem do que estava aberto na lista. */
export function NewCategorySheet() {
  const { workspaceId, categories, changed } = useSheetContext()
  const { canWrite } = usePermissions()
  const navigate = useNavigate()
  const location = useLocation()
  const opened = (location.state as { kind?: CategoryKind } | null)?.kind

  if (!canWrite) return <Navigate to={BACK} replace state={readOnly('criar')} />

  const parents = categories.filter((category) => !category.archived).map(toParent)
  return (
    <CategoryForm
      mode="create"
      title="Nova categoria"
      initial={emptyCategoryForm(opened === 'INCOME' ? 'INCOME' : 'EXPENSE', CATEGORY_COLORS[0])}
      parents={parents}
      submitLabel="Criar categoria"
      onClose={() => void navigate(BACK, { replace: true, state: { kind: opened } })}
      onSubmit={async (values, parent) => {
        await createCategory(workspaceId, buildCreateCategory(values, parent?.id))
        changed()
        void navigate(BACK, { replace: true, state: { notice: parent ? 'Subcategoria criada.' : 'Categoria criada.', kind: values.kind } })
      }}
    />
  )
}

/** `/categorias/:id/subcategorias/nova`: cria uma subcategoria da principal `:id` (que precisa existir, ser principal e estar ativa). */
export function NewSubcategorySheet() {
  const { canWrite } = usePermissions()
  // Quem só lê nem consulta a principal: volta à lista com o aviso.
  return canWrite ? <NewSubcategory /> : <Navigate to={BACK} replace state={readOnly('criar')} />
}

function NewSubcategory() {
  const { workspaceId, changed } = useSheetContext()
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const resource = useResource(`category:${workspaceId}:${id}`, () => getCategory(workspaceId, id))
  const close = (kind?: CategoryKind) => void navigate(BACK, { replace: true, state: { kind } })

  if (resource.status === 'loading') {
    return (
      <SheetDialog title="Nova subcategoria" onClose={() => close()}>
        <LoadingNote>Carregando categoria…</LoadingNote>
      </SheetDialog>
    )
  }
  if (resource.status === 'error') {
    if (resource.error instanceof ApiError && resource.error.status === 404) return <Navigate to={BACK} replace state={{ notice: FAILURE_MESSAGES.notFound }} />
    return (
      <SheetDialog title="Nova subcategoria" onClose={() => close()}>
        <ErrorNotice onRetry={resource.reload}>Não foi possível carregar a categoria. Tente de novo.</ErrorNotice>
      </SheetDialog>
    )
  }

  const parent = resource.data
  if (parent.parentId !== null) return <Navigate to={BACK} replace state={{ notice: FAILURE_MESSAGES.notFound }} />
  if (parent.archived) {
    return (
      <SheetDialog title="Nova subcategoria" onClose={() => close(parent.kind)}>
        <p className="text-body">Restaure a categoria principal antes de criar subcategorias nela.</p>
      </SheetDialog>
    )
  }

  return (
    <CategoryForm
      key={parent.id}
      mode="create"
      title="Nova subcategoria"
      initial={emptyCategoryForm(parent.kind)}
      initialParent={toParent(parent)}
      lockParent
      submitLabel="Criar subcategoria"
      onClose={() => close(parent.kind)}
      onSubmit={async (values) => {
        await createCategory(workspaceId, buildCreateCategory(values, parent.id))
        changed()
        void navigate(BACK, { replace: true, state: { notice: 'Subcategoria criada.', kind: parent.kind } })
      }}
    />
  )
}

/** `/categorias/:id`: edita nome, cor e ícone (o tipo e a mãe não mudam). Arquivada pede restaurar antes. */
export function EditCategorySheet() {
  const { canWrite } = usePermissions()
  // Quem só lê nem consulta a categoria: volta à lista com o aviso.
  return canWrite ? <EditCategory /> : <Navigate to={BACK} replace state={readOnly('editar')} />
}

function EditCategory() {
  const { workspaceId, changed } = useSheetContext()
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const resource = useResource(`category:${workspaceId}:${id}`, () => getCategory(workspaceId, id))
  // A subcategoria usa a cor da mãe: busca a mãe (sem ela, a chave não consulta nada).
  const motherId = resource.status === 'ready' ? resource.data.parentId : null
  const mother = useResource(`category-mother:${workspaceId}:${motherId ?? 'none'}`, () => (motherId === null ? Promise.resolve(null) : getCategory(workspaceId, motherId)))
  const close = (kind?: CategoryKind) => void navigate(BACK, { replace: true, state: { kind } })

  if (resource.status === 'error') {
    if (resource.error instanceof ApiError && resource.error.status === 404) return <Navigate to={BACK} replace state={{ notice: FAILURE_MESSAGES.notFound }} />
    return (
      <SheetDialog title="Editar categoria" onClose={() => close()}>
        <ErrorNotice onRetry={resource.reload}>Não foi possível carregar a categoria. Tente de novo.</ErrorNotice>
      </SheetDialog>
    )
  }
  if (resource.status === 'loading' || mother.status === 'loading') {
    return (
      <SheetDialog title="Editar categoria" onClose={() => close()}>
        <LoadingNote>Carregando categoria…</LoadingNote>
      </SheetDialog>
    )
  }

  const category = resource.data
  const isSub = category.parentId !== null
  if (category.archived) {
    return (
      <SheetDialog title={category.name} onClose={() => close(category.kind)}>
        <p className="text-body">Restaure {isSub ? 'a subcategoria' : 'a categoria'} antes de editá-la. Pela lista, em "Mostrar arquivadas", escolha "Restaurar".</p>
      </SheetDialog>
    )
  }

  return (
    <CategoryForm
      key={category.id}
      mode="edit"
      title={isSub ? 'Editar subcategoria' : 'Editar categoria'}
      initial={categoryToForm(category)}
      initialParent={mother.status === 'ready' && mother.data ? toParent(mother.data) : null}
      lockParent
      submitLabel="Salvar"
      onClose={() => close(category.kind)}
      onSubmit={async (values) => {
        try {
          await updateCategory(workspaceId, category.id, buildUpdateCategory(values, isSub))
        } catch (error) {
          // A categoria deixou de existir (outro aparelho): volta à lista, que se atualiza.
          if (error instanceof ApiError && error.status === 404) {
            changed()
            void navigate(BACK, { replace: true, state: { notice: FAILURE_MESSAGES.notFound, kind: category.kind } })
            return
          }
          throw error
        }
        changed()
        void navigate(BACK, { replace: true, state: { notice: isSub ? 'Subcategoria salva.' : 'Categoria salva.', kind: category.kind } })
      }}
    />
  )
}
