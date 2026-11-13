// A importação de extratos (spec 013): a prévia lê o arquivo sem gravar nada; a confirmação cria os lançamentos escolhidos.
import { request } from './client'
import type { ImportConfirmResult, ImportLineInput, ImportPreview } from './types'

const base = (wid: string, accountId: string) => `/workspaces/${encodeURIComponent(wid)}/accounts/${encodeURIComponent(accountId)}/import`

/** Mais folga que o padrão: o envio de até 2 MB e a leitura (de um PDF, por exemplo) podem passar dos 15 s. */
const UPLOAD_TIMEOUT_MS = 60_000

/** Lê o extrato (OFX, CSV ou PDF, até 2 MB) e devolve a prévia, sem gravar nada. A parte do arquivo se chama `file`. */
export function previewStatement(wid: string, accountId: string, file: File) {
  const form = new FormData()
  form.append('file', file)
  return request<ImportPreview>('POST', `${base(wid, accountId)}/preview`, { body: form, timeoutMs: UPLOAD_TIMEOUT_MS })
}

/** Confirma as linhas escolhidas (de 1 a 1.000): cada uma vira um lançamento efetivado da conta. */
export const confirmImport = (wid: string, accountId: string, lines: ImportLineInput[]) =>
  request<ImportConfirmResult>('POST', base(wid, accountId), { body: { lines }, timeoutMs: UPLOAD_TIMEOUT_MS })
