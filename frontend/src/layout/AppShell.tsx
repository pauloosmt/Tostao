import { useEffect, useRef } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router'
import { useAuth } from '../auth/useAuth'
import { Nav, type NavItem } from '../design-system/src'
import { usePermissions } from '../permissions/usePermissions'
import { useWorkspace } from '../workspace/useWorkspace'
import { useMediaQuery } from './useMediaQuery'

const HOME: NavItem = { id: 'inicio', label: 'Início', icon: 'home', href: '/' }
const TRANSACTIONS: NavItem = { id: 'lancamentos', label: 'Lançamentos', icon: 'list', href: '/lancamentos' }
const ACCOUNTS: NavItem = { id: 'contas', label: 'Contas', icon: 'bank', href: '/contas' }
const CATEGORIES: NavItem = { id: 'categorias', label: 'Categorias', icon: 'tag', href: '/categorias' }
const PROFILE: NavItem = { id: 'perfil', label: 'Perfil', icon: 'user', href: '/perfil' }

// No menu lateral o Perfil é o rodapé (o usuário); na barra de baixo cabem quatro itens e o botão de lançar no meio, e as categorias
// ficam num atalho dentro de Contas.
const SIDE_ITEMS: NavItem[] = [HOME, TRANSACTIONS, ACCOUNTS, CATEGORIES]
const BOTTOM_ITEMS: NavItem[] = [HOME, TRANSACTIONS, ACCOUNTS, PROFILE]
const PATH_OF: Record<string, string> = { inicio: '/', lancamentos: '/lancamentos', contas: '/contas', categorias: '/categorias', perfil: '/perfil' }

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

  const inSection = (section: string) => pathname === section || pathname.startsWith(`${section}/`)
  const active = inSection('/perfil') ? 'perfil' : inSection('/lancamentos') ? 'lancamentos' : inSection('/contas') ? 'contas' : inSection('/categorias') ? 'categorias' : 'inicio'
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
        <Outlet />
      </main>
      {wide ? null : nav}
    </div>
  )
}
