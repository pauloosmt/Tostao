import { describe, expect, it } from 'vitest'
import { canOfferWindowsApp } from './desktopApp'

const chromeWindows = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36'

describe('oferta do app para Windows', () => {
  it('só no navegador de computador com Windows', () => {
    expect(canOfferWindowsApp(chromeWindows, false)).toBe(true)
    expect(canOfferWindowsApp('Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/605.1.15 Safari/605.1.15', false)).toBe(false)
    expect(canOfferWindowsApp('Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/126.0 Safari/537.36', false)).toBe(false)
  })

  it('nunca no celular, no próprio app desktop nem no PWA instalado', () => {
    expect(canOfferWindowsApp('Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36', false)).toBe(false)
    expect(canOfferWindowsApp('Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148', false)).toBe(false)
    expect(canOfferWindowsApp(`${chromeWindows} Electron/45.3.2`, false)).toBe(false)
    expect(canOfferWindowsApp(chromeWindows, true)).toBe(false)
  })
})
