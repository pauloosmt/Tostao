import { Link } from 'react-router'
import { Logo, PageHeader } from '../design-system/src'

/** Qualquer endereço que o aplicativo não tem: a página própria, com o caminho de volta (nunca uma tela em branco). */
export function NotFoundPage() {
  return (
    <main className="screen-center">
      <Logo variant="lockup" size={48} />
      <PageHeader title="Página não encontrada" />
      <p className="text-body muted">O endereço que você abriu não existe.</p>
      <Link className="link" to="/">
        Voltar ao início
      </Link>
    </main>
  )
}
