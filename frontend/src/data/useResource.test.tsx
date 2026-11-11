import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { useResource } from './useResource'

/** Uma consulta que o teste resolve ou rejeita quando quiser. */
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

function Probe({ keyName, load }: { keyName: string; load: () => Promise<string> }) {
  const resource = useResource(keyName, load)
  return (
    <div>
      <p data-testid="status">{resource.status}</p>
      {resource.status === 'ready' ? <p data-testid="data">{resource.data}</p> : null}
      {resource.status === 'error' ? <p data-testid="error">{String(resource.error)}</p> : null}
      <button type="button" onClick={resource.reload}>
        recarregar
      </button>
    </div>
  )
}
const status = () => screen.getByTestId('status').textContent

describe('useResource', () => {
  it('carrega e entrega o dado', async () => {
    const query = deferred<string>()
    render(<Probe keyName="a" load={() => query.promise} />)
    expect(status()).toBe('loading')

    await act(async () => query.resolve('dados de A'))

    expect(status()).toBe('ready')
    expect(screen.getByTestId('data')).toHaveTextContent('dados de A')
  })

  it('o erro vira o estado de erro, e "recarregar" volta a carregar e pode dar certo', async () => {
    const user = userEvent.setup()
    const first = deferred<string>()
    const second = deferred<string>()
    const load = vi.fn().mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    render(<Probe keyName="a" load={load} />)

    await act(async () => first.reject(new Error('sem rede')))
    expect(status()).toBe('error')
    expect(screen.getByTestId('error')).toHaveTextContent('sem rede')

    await user.click(screen.getByRole('button', { name: 'recarregar' }))
    expect(status()).toBe('loading')
    expect(screen.queryByTestId('error')).toBeNull()
    await act(async () => second.resolve('agora foi'))

    expect(status()).toBe('ready')
    expect(screen.getByTestId('data')).toHaveTextContent('agora foi')
    expect(load).toHaveBeenCalledTimes(2)
  })

  it('mudar a chave volta a "carregando" na hora, sem o dado da chave anterior', async () => {
    const a = deferred<string>()
    const b = deferred<string>()
    const view = render(<Probe keyName="a" load={() => a.promise} />)
    await act(async () => a.resolve('dados de A'))
    expect(screen.getByTestId('data')).toHaveTextContent('dados de A')

    view.rerender(<Probe keyName="b" load={() => b.promise} />)

    expect(status()).toBe('loading')
    expect(screen.queryByTestId('data')).toBeNull()
    await act(async () => b.resolve('dados de B'))
    expect(screen.getByTestId('data')).toHaveTextContent('dados de B')
  })

  it('a resposta atrasada de uma chave que ficou para trás é descartada, mesmo chegando depois da nova', async () => {
    const a = deferred<string>()
    const b = deferred<string>()
    const view = render(<Probe keyName="a" load={() => a.promise} />)
    view.rerender(<Probe keyName="b" load={() => b.promise} />)

    await act(async () => b.resolve('dados de B'))
    await act(async () => a.resolve('dados de A (atrasados)'))

    expect(screen.getByTestId('data')).toHaveTextContent('dados de B')
    expect(screen.queryByText(/atrasados/)).toBeNull()
  })

  it('o erro atrasado de uma chave que ficou para trás também é descartado', async () => {
    const a = deferred<string>()
    const b = deferred<string>()
    const view = render(<Probe keyName="a" load={() => a.promise} />)
    view.rerender(<Probe keyName="b" load={() => b.promise} />)

    await act(async () => a.reject(new Error('falha de A')))
    await act(async () => b.resolve('dados de B'))

    expect(status()).toBe('ready')
    expect(screen.queryByTestId('error')).toBeNull()
  })

  it('voltar a uma chave anterior consulta de novo (nada de cache entre chaves)', async () => {
    const load = vi.fn((name: string) => Promise.resolve(`dados de ${name}`))
    const view = render(<Probe keyName="a" load={() => load('a')} />)
    await act(async () => {})
    view.rerender(<Probe keyName="b" load={() => load('b')} />)
    await act(async () => {})

    view.rerender(<Probe keyName="a" load={() => load('a')} />)
    expect(status()).toBe('loading')
    await act(async () => {})

    expect(screen.getByTestId('data')).toHaveTextContent('dados de a')
    expect(load).toHaveBeenCalledTimes(3)
  })

  it('depois de desmontar, a resposta que chega não faz nada (nem erro no console)', async () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const query = deferred<string>()
    const view = render(<Probe keyName="a" load={() => query.promise} />)
    view.unmount()

    await act(async () => query.resolve('tarde demais'))

    expect(spy).not.toHaveBeenCalled()
  })

  it('só consulta uma vez por chave, mesmo a tela renderizando de novo', async () => {
    const load = vi.fn(() => Promise.resolve('x'))
    const view = render(<Probe keyName="a" load={load} />)
    await act(async () => {})

    view.rerender(<Probe keyName="a" load={load} />)
    view.rerender(<Probe keyName="a" load={load} />)

    expect(load).toHaveBeenCalledTimes(1)
  })
})
