import { useLocation } from 'react-router'

/** Mostra onde o roteador está (caminho e consulta) para os testes conferirem para onde a tela levou. */
export function LocationProbe() {
  const { pathname, search } = useLocation()
  return <p data-testid="location">{pathname + search}</p>
}
