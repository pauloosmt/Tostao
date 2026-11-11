import { Logo } from '../design-system/src'

/** Tela cheia de espera (a consulta inicial da sessão). O leitor de tela a anuncia como um estado, não como conteúdo. */
export function LoadingScreen({ label = 'Carregando…' }: { label?: string }) {
  return (
    <div className="screen-center" role="status">
      <Logo variant="lockup" size={48} />
      <p className="text-body muted">{label}</p>
    </div>
  )
}
