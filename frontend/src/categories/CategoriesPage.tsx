import { useEffect, useState } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { archiveCategory, listCategories, restoreCategory } from '../api/categories'
import type { Category, CategoryKind, CategoryTree } from '../api/types'
import { Alert, Badge, Button, Card, Segmented } from '../design-system/src'
import { useResource } from '../data/useResource'
import { describeFailure } from '../forms/apiErrors'
import { useSubmit } from '../forms/useSubmit'
import { usePermissions } from '../permissions/usePermissions'
import { ButtonLink } from '../ui/ButtonLink'
import { CheckboxField } from '../ui/CheckboxField'
import { ConfirmDialog } from '../ui/ConfirmDialog'
import { ErrorNotice } from '../ui/ErrorNotice'
import { LoadingNote } from '../ui/LoadingNote'
import { KIND_OPTIONS } from '../validation/categoryForm'
import { useWorkspace } from '../workspace/useWorkspace'
import { categoryConflict } from './conflicts'
import { paletteColor } from './palette'

const SEGMENTS = KIND_OPTIONS.map((option) => ({ value: option.value, label: option.plural }))
const kindWord = (kind: CategoryKind) => (kind === 'EXPENSE' ? 'despesa' : 'receita')

/** O que a confirmação de arquivar diz: uma principal arrasta as subcategorias ativas; uma subcategoria sai sozinha. */
function archiveText(category: CategoryTree | Category, activeSubs: number): string {
  if (category.parentId !== null) return 'A subcategoria sai da lista. Você pode restaurá-la quando quiser.'
  const subs = activeSubs === 1 ? 'A subcategoria ativa dela também será arquivada.' : `As ${activeSubs} subcategorias ativas dela também serão arquivadas.`
  return `A categoria sai da lista. ${activeSubs > 0 ? `${subs} ` : ''}Você pode restaurá-la quando quiser.`
}

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

interface ArchiveTarget {
  category: Category
  activeSubs: number
}

function CategoriesOf({ workspaceId }: { workspaceId: string }) {
  const { canWrite } = usePermissions()
  const location = useLocation()
  const navigate = useNavigate()
  const { submitting, run } = useSubmit()
  const [showArchived, setShowArchived] = useState(false)
  const [archiving, setArchiving] = useState<ArchiveTarget | null>(null)
  const [problem, setProblem] = useState<string | null>(null)

  // O que a tela anterior pediu: o aviso do que acabou de acontecer e o tipo em que a categoria foi criada ou salva.
  const [arrival] = useState(() => (location.state as { notice?: string; kind?: CategoryKind } | null) ?? null)
  const [notice, setNotice] = useState<string | null>(arrival?.notice ?? null)
  const [kind, setKind] = useState<CategoryKind>(arrival?.kind === 'INCOME' ? 'INCOME' : 'EXPENSE')
  useEffect(() => {
    if (arrival !== null) void navigate(location.pathname, { replace: true, state: null })
  }, [arrival, navigate, location.pathname])

  // A chave leva o workspace e a opção: trocar qualquer um volta a "carregando" e descarta a resposta que ficou para trás.
  const resource = useResource(`categories:${workspaceId}:${showArchived}`, () => listCategories(workspaceId, showArchived))
  const { reload } = resource

  /** Uma ação sobre uma categoria (arquivar, restaurar): o sucesso recarrega a lista; a falha deixa tudo como estava e explica. */
  async function act(action: () => Promise<unknown>, done: string) {
    await run(async () => {
      setNotice(null)
      setProblem(null)
      try {
        await action()
        setNotice(done)
        reload()
      } catch (error) {
        const failure = describeFailure(error, { conflict: (detail) => ({ message: categoryConflict(detail).message }) })
        setProblem(failure.general ?? null)
        // A categoria deixou de existir em outro aparelho: a lista se atualiza para refletir isso.
        if (failure.kind === 'notFound') reload()
      } finally {
        setArchiving(null)
      }
    })
  }

  const restore = (category: Category, archivedSubs: number) =>
    void act(
      () => restoreCategory(workspaceId, category.id),
      category.parentId === null && archivedSubs > 0
        ? 'Categoria restaurada. As subcategorias dela continuam arquivadas; restaure cada uma à parte.'
        : category.parentId === null
          ? 'Categoria restaurada.'
          : 'Subcategoria restaurada.',
    )

  const visible: CategoryTree[] = resource.status === 'ready' ? resource.data.filter((category) => category.kind === kind) : []
  const plural = SEGMENTS.find((segment) => segment.value === kind)?.label ?? ''

  const archiveButton = (category: Category, activeSubs: number) => (
    <Button size="sm" variant="ghost" disabled={submitting} aria-label={`Arquivar ${category.name}`} onClick={() => setArchiving({ category, activeSubs })}>
      Arquivar
    </Button>
  )
  const restoreButton = (category: Category, archivedSubs: number) => (
    <Button size="sm" variant="ghost" disabled={submitting} aria-label={`Restaurar ${category.name}`} onClick={() => restore(category, archivedSubs)}>
      Restaurar
    </Button>
  )

  return (
    <>
      {notice ? <Alert tone="info">{notice}</Alert> : null}
      {problem ? <Alert tone="danger">{problem}</Alert> : null}

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
            {visible.map((category) => {
              const activeSubs = category.subcategories.filter((sub) => !sub.archived).length
              const archivedSubs = category.subcategories.length - activeSubs
              return (
                <li key={category.id} className="category-tree__item">
                  <Card>
                    <div className="stack stack--tight">
                      <div className="category-row">
                      <CategoryName category={category} />
                      {canWrite ? (
                        <div className="item-actions">
                          {category.archived ? (
                            restoreButton(category, archivedSubs)
                          ) : (
                            <>
                              <ButtonLink variant="ghost" size="sm" to={`/categorias/${category.id}/subcategorias/nova`} aria-label={`Adicionar subcategoria em ${category.name}`}>
                                Adicionar subcategoria
                              </ButtonLink>
                              <ButtonLink variant="ghost" size="sm" to={`/categorias/${category.id}`} aria-label={`Editar ${category.name}`}>
                                Editar
                              </ButtonLink>
                              {archiveButton(category, activeSubs)}
                            </>
                          )}
                        </div>
                      ) : null}
                      </div>
                      {category.subcategories.length > 0 ? (
                        <ul className="category-tree__subs" aria-label={`Subcategorias de ${category.name}`}>
                          {category.subcategories.map((sub) => (
                            <li key={sub.id} className="category-row">
                              <CategoryName category={sub} />
                              {canWrite ? (
                                <div className="item-actions">
                                  {sub.archived ? (
                                    restoreButton(sub, 0)
                                  ) : (
                                    <>
                                      <ButtonLink variant="ghost" size="sm" to={`/categorias/${sub.id}`} aria-label={`Editar ${sub.name}`}>
                                        Editar
                                      </ButtonLink>
                                      {archiveButton(sub, 0)}
                                    </>
                                  )}
                                </div>
                              ) : null}
                            </li>
                          ))}
                        </ul>
                      ) : null}
                    </div>
                  </Card>
                </li>
              )
            })}
          </ul>
        )
      ) : null}

      <ConfirmDialog
        open={archiving !== null}
        tone="primary"
        title={`Arquivar "${archiving?.category.name ?? ''}"?`}
        confirmLabel="Arquivar"
        busy={submitting}
        onConfirm={() => {
          if (archiving) void act(() => archiveCategory(workspaceId, archiving.category.id), archiving.category.parentId === null ? 'Categoria arquivada.' : 'Subcategoria arquivada.')
        }}
        onCancel={() => setArchiving(null)}
      >
        {archiving ? archiveText(archiving.category, archiving.activeSubs) : ''}
      </ConfirmDialog>
    </>
  )
}
