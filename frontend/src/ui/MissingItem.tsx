import { Card } from '../design-system/src'
import { ButtonLink } from './ButtonLink'

/**
 * "Não encontrada" dentro da estrutura do aplicativo (uma conta ou categoria que não existe no workspace atual, ou é de outro: as duas
 * respostas são iguais, sem pista de existir em outro lugar), com o caminho de volta.
 */
export function MissingItem({ title, backTo, backLabel }: { title: string; backTo: string; backLabel: string }) {
  return (
    <>
      <h1 className="heading-1">{title}</h1>
      <Card>
        <div className="stack">
          <p className="text-body">Ela não existe neste workspace ou foi removida.</p>
          <div>
            <ButtonLink to={backTo}>{backLabel}</ButtonLink>
          </div>
        </div>
      </Card>
    </>
  )
}
