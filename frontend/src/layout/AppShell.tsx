import { useEffect, useRef } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router'
import { Nav, type NavItem } from '../design-system/src'
import { useMediaQuery } from './useMediaQuery'

const ITEMS: NavItem[] = [
  { id: 'inicio', label: 'Início', icon: 'home', href: '/' },
  { id: 'contas', label: 'Contas', icon: 'bank', href: '/contas' },
  { id: 'perfil', label: 'Perfil', icon: 'wallet', href: '/perfil' },
]
const PATH_OF: Record<string, string> = { inicio: '/', contas: '/contas', perfil: '/perfil' }

/**
 * A estrutura de quem está dentro: a navegação e, ao lado dela, a tela. No celular (abaixo de 960 px) a navegação é a barra de baixo;
 * a partir de 960 px, o menu lateral do desktop. Na troca de tamanho da janela, ela troca junto.
 */
export function AppShell() {
  const wide = useMediaQuery('(min-width: 960px)')
  const { pathname } = useLocation()
  const navigate = useNavigate()

  // Ao trocar de tela, o foco vai para o conteúdo novo: quem usa teclado ou leitor de tela nunca fica com o foco perdido no menu.
  const main = useRef<HTMLElement>(null)
  const lastPath = useRef(pathname)
  useEffect(() => {
    if (lastPath.current === pathname) return
    lastPath.current = pathname
    main.current?.focus()
  }, [pathname])

  const inSection = (section: string) => pathname === section || pathname.startsWith(`${section}/`)
  const active = inSection('/perfil') ? 'perfil' : inSection('/contas') ? 'contas' : 'inicio'
  const nav = (
    <Nav items={ITEMS} active={active} layout={wide ? 'side' : 'bottom'} onSelect={(id) => void navigate(PATH_OF[id] ?? '/')} />
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
