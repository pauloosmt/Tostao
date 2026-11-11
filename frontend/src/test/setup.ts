import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, beforeEach, vi } from 'vitest'
import { takeUnhandledCalls } from './fakeApi'
import { installMatchMedia, resetViewport } from './media'

// O jsdom não implementa o <dialog> modal: o mínimo para os testes (abrir, fechar e o evento close).
if (typeof HTMLDialogElement !== 'undefined' && !HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    if (!this.hasAttribute('open')) return
    this.removeAttribute('open')
    this.dispatchEvent(new Event('close'))
  }
}

beforeEach(() => {
  localStorage.clear()
  document.documentElement.removeAttribute('data-theme')
  document.title = ''
  resetViewport()
  installMatchMedia()
})

afterEach(() => {
  const unhandled = takeUnhandledCalls()
  if (unhandled.length > 0) {
    throw new Error(`chamada(s) à API sem rota no fakeApi: ${unhandled.join(', ')}`)
  }
  cleanup()
  vi.unstubAllGlobals()
  vi.useRealTimers()
})
