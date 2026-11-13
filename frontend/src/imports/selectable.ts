import type { ImportLinePreview } from '../api/types'

/** A linha só se importa se foi lida por inteiro, tem chave e ainda não existe na conta. */
export const isSelectable = (line: ImportLinePreview): boolean =>
  line.problems.length === 0 && !line.existing && line.key !== null && line.occurredOn !== null && line.amountCents !== null
