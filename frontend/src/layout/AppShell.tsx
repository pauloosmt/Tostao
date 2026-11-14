import { Suspense, useEffect, useRef } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router'
import { useAuth } from '../auth/useAuth'
import { Nav, type NavItem } from '../design-system/src'
import { usePermissions } from '../permissions/usePermissions'
import { LoadingNote } from '../ui/LoadingNote'
import { useWorkspace } from '../workspace/useWorkspace'
import { useMediaQuery } from './useMediaQuery'

const HOME: NavItem = { id: 'inicio', label: 'Início', icon: 'home', href: '/' }
const TRANSACTIONS: NavItem = { id: 'lancamentos', label: 'Lançamentos', icon: 'list', href: '/lancamentos' }
const ACCOUNTS: NavItem = { id: 'contas', label: 'Contas', icon: 'bank', href: '/contas' }
const BUDGETS: NavItem = { id: 'orcamentos', label: 'Orçamentos', icon: 'chart', href: '/orcamentos' }
const RECURRENCES: NavItem = { id: 'recorrencias', label: 'Recorrências', icon: 'swap', href: '/recorrencias' }
const CATEGORIES: NavItem = { id: 'categorias', label: 'Categorias', icon: 'tag', href: '/categorias' }
const PROFILE: NavItem = { id: 'perfil', label: 'Perfil', icon: 'user', href: '/perfil' }

// No menu lateral o Perfil é o rodapé (o usuário); na barra de baixo cabem quatro itens e o botão de lançar no meio, e as categorias
// as contas, as recorrências e as categorias ficam em atalhos dentro de Orçamentos.
const SIDE_ITEMS: NavItem[] = [HOME, TRANSACTIONS, ACCOUNTS, BUDGETS, RECURRENCES, CATEGORIES]
const BOTTOM_ITEMS: NavItem[] = [HOME, TRANSACTIONS, BUDGETS, PROFILE]
const PATH_OF: Record<string, string> = { inicio: '/', lancamentos: '/lancamentos', contas: '/contas', orcamentos: '/orcamentos', recorrencias: '/recorrencias', categorias: '/categorias', perfil: '/perfil' }

/**
 * A estrutura de quem está dentro: a navegação e, ao lado dela, a tela. No celular (abaixo de 960 px) a navegação é a barra de baixo;
 * a partir de 960 px, o menu lateral do desktop. Na troca de tamanho da janela, ela troca junto.
 */
export function AppShell() {
  const wide = useMediaQuery('(min-width: 960px)')
  const { pathname } = useLocation()
  const navigate = useNavigate()
  const { state } = useAuth()
  const { current } = useWorkspace()
  const { canWrite } = usePermissions()

  // Ao trocar de tela, o foco vai para o conteúdo novo: quem usa teclado ou leitor de tela nunca fica com o foco perdido no menu.
  const main = useRef<HTMLElement>(null)
  const lastPath = useRef(pathname)
  useEffect(() => {
    if (lastPath.current === pathname) return
    lastPath.current = pathname
    main.current?.focus()
  }, [pathname])

  // A tecla N abre o lançamento (a dica está no botão do menu lateral): só para quem pode escrever, fora de campos de texto e sem folha aberta.
  useEffect(() => {
    if (!canWrite) return
    const onKey = (event: KeyboardEvent) => {
      if (event.key.toLowerCase() !== 'n' || event.ctrlKey || event.metaKey || event.altKey || event.defaultPrevented) return
      const target = event.target instanceof HTMLElement ? event.target : null
      if (target && (target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName))) return
      if (document.querySelector('dialog[open]')) return
      event.preventDefault()
      void navigate('/lancamentos/novo')
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [canWrite, navigate])

  const inSection = (section: string) => pathname === section || pathname.startsWith(`${section}/`)
  const active = inSection('/perfil') || inSection('/membros') ? 'perfil' : inSection('/lancamentos') ? 'lancamentos' : inSection('/contas') ? 'contas' : inSection('/orcamentos') ? 'orcamentos' : inSection('/recorrencias') ? 'recorrencias' : inSection('/categorias') ? 'categorias' : 'inicio'
  const go = (to: string) => void navigate(to)
  const user = state.status === 'authenticated' ? { name: state.me.name, sub: current?.name, href: '/perfil' } : undefined
  const nav = (
    <Nav
      items={wide ? SIDE_ITEMS : BOTTOM_ITEMS}
      active={active}
      layout={wide ? 'side' : 'bottom'}
      onSelect={(id) => go(PATH_OF[id] ?? '/')}
      launchLabel={canWrite ? 'Novo lançamento' : undefined}
      onLaunch={() => go('/lancamentos/novo')}
      user={user}
      onUser={() => go('/perfil')}
    />
  )

  return (
    <div className={`app-shell app-shell--${wide ? 'side' : 'bottom'}`}>
      {wide ? nav : null}
      <main ref={main} tabIndex={-1} className="app-shell__main">
        <Suspense fallback={<LoadingNote>Carregando…</LoadingNote>}>
          <Outlet />
        </Suspense>
      </main>
      {wide ? null : nav}
    </div>
  )
}
