import { Navigate, useNavigate } from 'react-router'
import { createRecurrence } from '../api/recurrences'
import { usePermissions } from '../permissions/usePermissions'
import { buildCreateRecurrence } from '../validation/recurrenceForm'
import { RecurrenceForm } from './RecurrenceForm'
import { useSheetContext } from './sheetContext'

const BACK = '/recorrencias'

/** `/recorrencias/nova`: a folha que cadastra uma recorrência; fecha com o aviso e a lista atrás se atualiza. */
export function NewRecurrenceSheet() {
  const { workspaceId, reference, changed } = useSheetContext()
  const { canWrite } = usePermissions()
  const navigate = useNavigate()

  if (!canWrite) return <Navigate to={BACK} replace state={{ notice: 'Este workspace é só para consulta: você não pode criar recorrências.' }} />

  return (
    <RecurrenceForm
      reference={reference}
      onClose={() => void navigate(BACK, { replace: true })}
      onSubmit={async (values) => {
        await createRecurrence(workspaceId, buildCreateRecurrence(values))
        changed()
        void navigate(BACK, { replace: true, state: { notice: 'Recorrência criada.' } })
      }}
    />
  )
}
