import { useMediaQuery } from '../layout/useMediaQuery'
import { canOfferWindowsApp, WINDOWS_DOWNLOAD_PATH } from '../platform/desktopApp'

/** "Baixar o app para Windows": só aparece no navegador de um computador com Windows (ver `canOfferWindowsApp`). */
export function DesktopAppLink() {
  const installed = useMediaQuery('(display-mode: standalone)')
  if (!canOfferWindowsApp(navigator.userAgent, installed)) return null
  return (
    <p className="text-body-sm muted auth-page__footer">
      Prefere usar no computador?{' '}
      <a className="link" href={WINDOWS_DOWNLOAD_PATH}>
        Baixar o app para Windows
      </a>
    </p>
  )
}
