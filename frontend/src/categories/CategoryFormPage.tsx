import { useLocation, useNavigate, useParams } from 'react-router'
import { ApiError } from '../api/errors'
import { createCategory, getCategory, updateCategory } from '../api/categories'
import type { CategoryKind } from '../api/types'
import { Card } from '../design-system/src'
import { useResource } from '../data/useResource'
import { WriteGate } from '../permissions/WriteGate'
import { ButtonLink } from '../ui/ButtonLink'
import { ErrorNotice } from '../ui/ErrorNotice'
import { LoadingNote } from '../ui/LoadingNote'
import { MissingItem } from '../ui/MissingItem'
import { FAILURE_MESSAGES } from '../forms/apiErrors'
import { buildCreateCategory, buildUpdateCategory, categoryToForm, emptyCategoryForm } from '../validation/categoryForm'
import { useWorkspace } from '../workspace/useWorkspace'
import { CategoryForm } from './CategoryForm'

const BACK = '/categorias'

/** `/categorias/nova`: cria uma categoria principal. O tipo já vem escolhido pelo que estava aberto na lista (e pode ser trocado). */
export function NewCategoryPage() {
  const { current } = useWorkspace()
  const navigate = useNavigate()
  const location = useLocation()
  const opened = (location.state as { kind?: CategoryKind } | null)?.kind

  return (
    <WriteGate backTo={BACK} backLabel="Voltar às categorias">
      <h1 className="heading-1">Criar categoria</h1>
      <Card>
        {current ? (
          <CategoryForm
            mode="principal"
            initial={emptyCategoryForm(opened === 'INCOME' || opened === 'EXPENSE' ? opened : 'EXPENSE')}
            submitLabel="Criar categoria"
            cancelTo={BACK}
            onSubmit={async (values) => {
              await createCategory(current.id, buildCreateCategory(values))
              void navigate(BACK, { state: { notice: 'Categoria criada.', kind: values.kind } })
            }}
          />
        ) : null}
      </Card>
    </WriteGate>
  )
}

/** `/categorias/:id/subcategorias/nova`: cria uma subcategoria da principal `:id` (que precisa existir, ser principal e estar ativa). */
export function NewSubcategoryPage() {
  const { current } = useWorkspace()
  const { id = '' } = useParams()

  return (
    <WriteGate backTo={BACK} backLabel="Voltar às categorias">
      {current ? <NewSubcategory workspaceId={current.id} parentId={id} /> : null}
    </WriteGate>
  )
}

function NewSubcategory({ workspaceId, parentId }: { workspaceId: string; parentId: string }) {
  const navigate = useNavigate()
  const resource = useResource(`category:${workspaceId}:${parentId}`, () => getCategory(workspaceId, parentId))

  if (resource.status === 'loading') return <LoadingNote>Carregando categoria…</LoadingNote>

  if (resource.status === 'error') {
    if (resource.error instanceof ApiError && resource.error.status === 404) {
      return <MissingItem title="Categoria não encontrada" backTo={BACK} backLabel="Voltar às categorias" />
    }
    return <ErrorNotice onRetry={resource.reload}>Não foi possível carregar a categoria. Tente de novo.</ErrorNotice>
  }

  const parent = resource.data
  if (parent.parentId !== null) {
    return <MissingItem title="Categoria não encontrada" backTo={BACK} backLabel="Voltar às categorias" />
  }
  if (parent.archived) {
    return (
      <>
        <h1 className="heading-1">{parent.name}</h1>
        <Card title="Categoria arquivada">
          <div className="stack">
            <p className="text-body">Restaure a categoria principal antes de criar subcategorias nela.</p>
            <div>
              <ButtonLink to={BACK} state={{ kind: parent.kind }}>
                Voltar às categorias
              </ButtonLink>
            </div>
          </div>
        </Card>
      </>
    )
  }

  return (
    <>
      <h1 className="heading-1">Nova subcategoria</h1>
      <p className="text-body muted">Em {parent.name}</p>
      <Card>
        <CategoryForm
          key={parent.id}
          mode="sub"
          initial={emptyCategoryForm(parent.kind)}
          submitLabel="Criar subcategoria"
          cancelTo={BACK}
          onSubmit={async (values) => {
            await createCategory(workspaceId, buildCreateCategory(values, parent.id))
            void navigate(BACK, { state: { notice: 'Subcategoria criada.', kind: parent.kind } })
          }}
        />
      </Card>
    </>
  )
}

/** `/categorias/:id`: edita nome e cor (o tipo e a principal não mudam, e o ícone que já existe é preservado). Arquivada pede restaurar antes. */
export function EditCategoryPage() {
  const { current } = useWorkspace()
  const { id = '' } = useParams()

  return (
    <WriteGate backTo={BACK} backLabel="Voltar às categorias">
      {current ? <EditCategory workspaceId={current.id} categoryId={id} /> : null}
    </WriteGate>
  )
}

function EditCategory({ workspaceId, categoryId }: { workspaceId: string; categoryId: string }) {
  const navigate = useNavigate()
  const resource = useResource(`category:${workspaceId}:${categoryId}`, () => getCategory(workspaceId, categoryId))

  if (resource.status === 'loading') return <LoadingNote>Carregando categoria…</LoadingNote>

  if (resource.status === 'error') {
    if (resource.error instanceof ApiError && resource.error.status === 404) {
      return <MissingItem title="Categoria não encontrada" backTo={BACK} backLabel="Voltar às categorias" />
    }
    return <ErrorNotice onRetry={resource.reload}>Não foi possível carregar a categoria. Tente de novo.</ErrorNotice>
  }

  const category = resource.data
  const isSub = category.parentId !== null
  if (category.archived) {
    return (
      <>
        <h1 className="heading-1">{category.name}</h1>
        <Card title={isSub ? 'Subcategoria arquivada' : 'Categoria arquivada'}>
          <div className="stack">
            <p className="text-body">Restaure {isSub ? 'a subcategoria' : 'a categoria'} antes de editá-la. Pela lista, em "Mostrar arquivadas", escolha "Restaurar".</p>
            <div>
              <ButtonLink to={BACK} state={{ kind: category.kind }}>
                Voltar às categorias
              </ButtonLink>
            </div>
          </div>
        </Card>
      </>
    )
  }

  return (
    <>
      <h1 className="heading-1">{isSub ? 'Editar subcategoria' : 'Editar categoria'}</h1>
      <Card>
        <CategoryForm
          key={category.id}
          mode="edit"
          initial={categoryToForm(category)}
          submitLabel="Salvar"
          cancelTo={BACK}
          onSubmit={async (values) => {
            try {
              // O ícone que a categoria já tem é reenviado: a API grava o que vier, e omiti-lo o apagaria.
              await updateCategory(workspaceId, category.id, buildUpdateCategory(values, category.icon))
            } catch (error) {
              // A categoria deixou de existir (outro aparelho): volta à lista, que se atualiza.
              if (error instanceof ApiError && error.status === 404) {
                void navigate(BACK, { state: { notice: FAILURE_MESSAGES.notFound, kind: category.kind } })
                return
              }
              throw error
            }
            void navigate(BACK, { state: { notice: isSub ? 'Subcategoria salva.' : 'Categoria salva.', kind: category.kind } })
          }}
        />
      </Card>
    </>
  )
}
