/** Endereço (do próprio site) que leva ao instalador; o `vercel.json` o redireciona para o arquivo publicado. */
export const WINDOWS_DOWNLOAD_PATH = '/baixar/windows'

/**
 * O instalador é só para Windows: oferece-se em navegador de computador com Windows e nunca no celular, dentro do próprio app
 * desktop (Electron) ou no PWA já instalado.
 */
export function canOfferWindowsApp(userAgent: string, installed: boolean): boolean {
  if (installed) return false
  if (/Electron\//.test(userAgent)) return false
  if (/Mobi|Android|iPhone|iPad|iPod/i.test(userAgent)) return false
  return /Windows NT/.test(userAgent)
}
