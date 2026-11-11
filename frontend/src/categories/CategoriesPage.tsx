import { useEffect, useState } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { listCategories } from '../api/categories'
import type { Category, CategoryKind, CategoryTree } from '../api/types'
import { Alert, Badge, Card, Segmented } from '../design-system/src'
import { useResource } from '../data/useResource'
import { usePermissions } from '../permissions/usePermissions'
import { ButtonLink } from '../ui/ButtonLink'
import { CheckboxField } from '../ui/CheckboxField'
import { ErrorNotice } from '../ui/ErrorNotice'
import { LoadingNote } from '../ui/LoadingNote'
import { KIND_OPTIONS } from '../validation/categoryForm'
import { useWorkspace } from '../workspace/useWorkspace'
import { paletteColor } from './palette'

const SEGMENTS = KIND_OPTIONS.map((option) => ({ value: option.value, label: option.plural }))
const kindWord = (kind: CategoryKind) => (kind === 'EXPENSE' ? 'despesa' : 'receita')

export function CategoriesPage() {
  const { current } = useWorkspace()

  return (
    <>
      <div className="page-header">
        <h1 className="heading-1">Categorias</h1>
      </div>
      {current ? <CategoriesOf workspaceId={current.id} /> : <Card><p className="text-body">Você não participa de nenhum workspace.</p></Card>}
    </>
  )
}

/** O ponto de cor de uma categoria (nada quando ela não tem cor). O nome da cor vai para o leitor de tela quando é da paleta. */
export function ColorDot({ color }: { color: string | null }) {
  if (color === null) return null
  const name = paletteColor(color)?.name
  return (
    <>
      <span className="color-dot" style={{ background: color }} aria-hidden="true" />
      {name ? <span className="sr-only">Cor {name}. </span> : null}
    </>
  )
}

function CategoryName({ category }: { category: Category }) {
  return (
    <span className="category-name">
      <ColorDot color={category.color} />
      <span className="category-name__text">{category.name}</span>
      {category.archived ? <Badge tone="neutral">Arquivada</Badge> : null}
    </span>
  )
}

function CategoriesOf({ workspaceId }: { workspaceId: string }) {
  const { canWrite } = usePermissions()
  const location = useLocation()
  const navigate = useNavigate()
  const [showArchived, setShowArchived] = useState(false)

  // O que a tela anterior pediu: o aviso do que acabou de acontecer e o tipo em que a categoria nova foi criada.
  const [arrival] = useState(() => (location.state as { notice?: string; kind?: CategoryKind } | null) ?? null)
  const [kind, setKind] = useState<CategoryKind>(arrival?.kind === 'INCOME' ? 'INCOME' : 'EXPENSE')
  useEffect(() => {
    if (arrival !== null) void navigate(location.pathname, { replace: true, state: null })
  }, [arrival, navigate, location.pathname])

  // A chave leva o workspace e a opção: trocar qualquer um volta a "carregando" e descarta a resposta que ficou para trás.
  const resource = useResource(`categories:${workspaceId}:${showArchived}`, () => listCategories(workspaceId, showArchived))

  const visible: CategoryTree[] = resource.status === 'ready' ? resource.data.filter((category) => category.kind === kind) : []
  const plural = SEGMENTS.find((segment) => segment.value === kind)?.label ?? ''

  return (
    <>
      {arrival?.notice ? <Alert tone="info">{arrival.notice}</Alert> : null}

      <div className="list-toolbar">
        <Segmented label="Tipo de categoria" value={kind} options={SEGMENTS} onChange={setKind} />
        {canWrite ? (
          <ButtonLink variant="primary" to="/categorias/nova" state={{ kind }}>
            Criar categoria
          </ButtonLink>
        ) : null}
      </div>
      <CheckboxField label="Mostrar arquivadas" checked={showArchived} onChange={(event) => setShowArchived(event.target.checked)} />

      {resource.status === 'loading' ? <LoadingNote>Carregando categorias…</LoadingNote> : null}

      {resource.status === 'error' ? <ErrorNotice onRetry={resource.reload}>Não foi possível carregar as categorias. Tente de novo.</ErrorNotice> : null}

      {resource.status === 'ready' ? (
        visible.length === 0 ? (
          <Card>
            <div className="stack stack--tight">
              <p className="title">Nenhuma categoria de {kindWord(kind)} {showArchived ? '' : 'ainda'}</p>
              <p className="text-body muted">
                {canWrite ? `Crie a primeira categoria de ${kindWord(kind)} para organizar seus lançamentos.` : 'Quem pode editar este workspace cria as categorias.'}
              </p>
            </div>
          </Card>
        ) : (
          <ul className="category-tree" aria-label={plural}>
            {visible.map((category) => (
              <li key={category.id} className="category-tree__item">
                <Card>
                  <div className="stack stack--tight">
                    <CategoryName category={category} />
                    {category.subcategories.length > 0 ? (
                      <ul className="category-tree__subs" aria-label={`Subcategorias de ${category.name}`}>
                        {category.subcategories.map((sub) => (
                          <li key={sub.id}>
                            <CategoryName category={sub} />
                          </li>
                        ))}
                      </ul>
                    ) : null}
                  </div>
                </Card>
              </li>
            ))}
          </ul>
        )
      ) : null}
    </>
  )
}
