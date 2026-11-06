import { useEffect, useState } from 'react'
import './App.css'

type Status = 'carregando' | 'UP' | 'indisponível'

function App() {
  const [status, setStatus] = useState<Status>('carregando')

  useEffect(() => {
    fetch('/api/v1/health', { credentials: 'include' })
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
      .then((body: { status: string }) => setStatus(body.status === 'UP' ? 'UP' : 'indisponível'))
      .catch(() => setStatus('indisponível'))
  }, [])

  return (
    <main>
      <h1>Tostão</h1>
      <p>
        API: <strong data-testid="api-status">{status}</strong>
      </p>
    </main>
  )
}

export default App
