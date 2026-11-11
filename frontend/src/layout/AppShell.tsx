import { Outlet, useLocation, useNavigate } from 'react-router'
import { Nav, type NavItem } from '../design-system/src'
import { useMediaQuery } from './useMediaQuery'

const ITEMS: NavItem[] = [
  { id: 'inicio', label: 'Início', icon: 'home', href: '/' },
  { id: 'perfil', label: 'Perfil', icon: 'wallet', href: '/perfil' },
]
const PATH_OF: Record<string, string> = { inicio: '/', perfil: '/perfil' }

/**
 * A estrutura de quem está dentro: a navegação e, ao lado dela, a tela. No celular (abaixo de 960 px) a navegação é a barra de baixo;
 * a partir de 960 px, o menu lateral do desktop. Na troca de tamanho da janela, ela troca junto.
 */
export function AppShell() {
  const wide = useMediaQuery('(min-width: 960px)')
  const { pathname } = useLocation()
  const navigate = useNavigate()

  const active = pathname === '/perfil' || pathname.startsWith('/perfil/') ? 'perfil' : 'inicio'
  const nav = (
    <Nav items={ITEMS} active={active} layout={wide ? 'side' : 'bottom'} onSelect={(id) => void navigate(PATH_OF[id] ?? '/')} />
  )

  return (
    <div className={`app-shell app-shell--${wide ? 'side' : 'bottom'}`}>
      {wide ? nav : null}
      <main className="app-shell__main">
        <Outlet />
      </main>
      {wide ? null : nav}
    </div>
  )
}
