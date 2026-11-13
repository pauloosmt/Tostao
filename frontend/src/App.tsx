import { lazy, Suspense, type ComponentType, type ReactNode } from 'react'
import { Route, Routes } from 'react-router'
import { EditAccountPage, NewAccountPage } from './accounts/AccountFormPage'
import { AccountsPage } from './accounts/AccountsPage'
import { RequireAuth } from './auth/RequireAuth'
import { useAuth } from './auth/useAuth'
import { NewTransactionSheet, TransactionSheet } from './transactions/TransactionFormPage'
import { TransactionsPage } from './transactions/TransactionsPage'
import { Logo } from './design-system/src'
import { HomePage } from './pages/HomePage'
import { LoginPage } from './pages/LoginPage'
import { NotFoundPage } from './pages/NotFoundPage'
import { ProfilePage } from './pages/ProfilePage'
import { RegisterPage } from './pages/RegisterPage'
import { ErrorNotice } from './ui/ErrorNotice'
import { LoadingScreen } from './ui/LoadingScreen'

// As telas menos usadas carregam sob demanda (um pedaço de JavaScript por tela, na primeira visita): o que a pessoa baixa para abrir o
// aplicativo fica dentro do orçamento de 150 KB. As folhas (rotas filhas) carregam junto, sem esconder a lista que está atrás.
const named = <T,>(load: () => Promise<T>, name: keyof T) => lazy(() => load().then((module) => ({ default: module[name] as ComponentType })))
const BudgetsPage = named(() => import('./budgets/BudgetsPage'), 'BudgetsPage')
const CardPage = named(() => import('./cards/CardPage'), 'CardPage')
const CategoriesPage = named(() => import('./categories/CategoriesPage'), 'CategoriesPage')
const NewCategorySheet = named(() => import('./categories/CategoryFormPage'), 'NewCategorySheet')
const EditCategorySheet = named(() => import('./categories/CategoryFormPage'), 'EditCategorySheet')
const NewSubcategorySheet = named(() => import('./categories/CategoryFormPage'), 'NewSubcategorySheet')
const ImportPage = named(() => import('./imports/ImportPage'), 'ImportPage')
const MembersPage = named(() => import('./members/MembersPage'), 'MembersPage')
const RecurrencesPage = named(() => import('./recurrences/RecurrencesPage'), 'RecurrencesPage')
const NewRecurrenceSheet = named(() => import('./recurrences/RecurrenceSheets'), 'NewRecurrenceSheet')
const RecurrenceDetailSheet = named(() => import('./recurrences/RecurrenceDetailSheet'), 'RecurrenceDetailSheet')

/** Uma folha que carrega sob demanda: enquanto o pedaço chega, nada aparece no lugar dela (a lista de trás continua). */
const Sheet = ({ children }: { children: ReactNode }) => <Suspense fallback={null}>{children}</Suspense>

/** As rotas do Tostão, atrás da consulta inicial da sessão (carregando, ou o servidor fora do ar com "Tentar de novo"). */
export function App() {
  const { state, retry } = useAuth()

  if (state.status === 'loading') return <LoadingScreen />

  if (state.status === 'unavailable') {
    return (
      <main className="screen-center">
        <Logo variant="lockup" size={48} />
        <h1 className="heading-1">Não foi possível abrir o Tostão</h1>
        <ErrorNotice onRetry={retry}>Não foi possível falar com o servidor. Tente de novo.</ErrorNotice>
      </main>
    )
  }

  return (
    <Routes>
      <Route path="/entrar" element={<LoginPage />} />
      <Route path="/cadastro" element={<RegisterPage />} />
      <Route element={<RequireAuth />}>
        <Route index element={<HomePage />} />
        <Route path="lancamentos" element={<TransactionsPage />}>
          <Route path="novo" element={<NewTransactionSheet />} />
          <Route path=":id" element={<TransactionSheet />} />
        </Route>
        <Route path="contas" element={<AccountsPage />} />
        <Route path="contas/nova" element={<NewAccountPage />} />
        <Route path="contas/:id/fatura" element={<CardPage />} />
        <Route path="contas/:id/importar" element={<ImportPage />} />
        <Route path="contas/:id" element={<EditAccountPage />} />
        <Route path="orcamentos" element={<BudgetsPage />} />
        <Route path="categorias" element={<CategoriesPage />}>
          <Route path="nova" element={<Sheet><NewCategorySheet /></Sheet>} />
          <Route path=":id" element={<Sheet><EditCategorySheet /></Sheet>} />
          <Route path=":id/subcategorias/nova" element={<Sheet><NewSubcategorySheet /></Sheet>} />
        </Route>
        <Route path="recorrencias" element={<RecurrencesPage />}>
          <Route path="nova" element={<Sheet><NewRecurrenceSheet /></Sheet>} />
          <Route path=":id" element={<Sheet><RecurrenceDetailSheet /></Sheet>} />
        </Route>
        <Route path="membros" element={<MembersPage />} />
        <Route path="perfil" element={<ProfilePage />} />
      </Route>
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  )
}
