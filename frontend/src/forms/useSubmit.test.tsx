import { act, render, screen } from '@testing-library/react'
import { useEffect } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { useSubmit } from './useSubmit'

const latest: { value: ReturnType<typeof useSubmit> | null } = { value: null }
// o teste chama as ações como a tela chamaria; o valor mais recente vem de um efeito (não se reatribui nada na renderização)
const submit = {
  get run() {
    return latest.value!.run
  },
}
function Probe() {
  const current = useSubmit()
  useEffect(() => {
    latest.value = current
  })
  return <p data-testid="submitting">{String(current.submitting)}</p>
}
const submitting = () => screen.getByTestId('submitting').textContent

function deferred() {
  let resolve!: () => void
  let reject!: (error: unknown) => void
  const promise = new Promise<void>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

describe('useSubmit', () => {
  it('executa a ação e mostra o andamento enquanto ela roda', async () => {
    render(<Probe />)
    const pending = deferred()
    const action = vi.fn(() => pending.promise)
    expect(submitting()).toBe('false')

    let finished!: Promise<void>
    act(() => {
      finished = submit.run(action)
    })
    expect(submitting()).toBe('true')
    await act(async () => {
      pending.resolve()
      await finished
    })

    expect(submitting()).toBe('false')
    expect(action).toHaveBeenCalledTimes(1)
  })

  it('duas chamadas seguidas executam uma só (o segundo clique não envia de novo)', async () => {
    render(<Probe />)
    const pending = deferred()
    const action = vi.fn(() => pending.promise)

    let first!: Promise<void>
    let second!: Promise<void>
    act(() => {
      first = submit.run(action)
      second = submit.run(action)
    })
    await act(async () => {
      pending.resolve()
      await Promise.all([first, second])
    })

    expect(action).toHaveBeenCalledTimes(1)
  })

  it('depois que termina, uma nova ação pode rodar', async () => {
    render(<Probe />)
    const action = vi.fn(() => Promise.resolve())

    await act(() => submit.run(action))
    await act(() => submit.run(action))

    expect(action).toHaveBeenCalledTimes(2)
  })

  it('se a ação falha, o guarda é liberado e o erro segue para quem chamou', async () => {
    render(<Probe />)
    const failing = vi.fn(() => Promise.reject(new Error('sem rede')))
    const next = vi.fn(() => Promise.resolve())

    const error = await act(() => submit.run(failing).catch((e: unknown) => e))
    expect(error).toBeInstanceOf(Error)
    expect((error as Error).message).toBe('sem rede')
    expect(submitting()).toBe('false')

    await act(() => submit.run(next))
    expect(next).toHaveBeenCalledTimes(1)
  })

  it('a função `run` é a mesma entre renderizações (serve de dependência de efeitos e de eventos)', () => {
    const view = render(<Probe />)
    const first = submit.run

    view.rerender(<Probe />)

    expect(submit.run).toBe(first)
  })
})
