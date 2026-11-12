import { Route, Routes } from 'react-router'
import { EditAccountPage, NewAccountPage } from './accounts/AccountFormPage'
import { AccountsPage } from './accounts/AccountsPage'
import { BudgetsPage } from './budgets/BudgetsPage'
import { CardPage } from './cards/CardPage'
import { RequireAuth } from './auth/RequireAuth'
import { CategoriesPage } from './categories/CategoriesPage'
import { EditCategorySheet, NewCategorySheet, NewSubcategorySheet } from './categories/CategoryFormPage'
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
        <Route path="contas/:id" element={<EditAccountPage />} />
        <Route path="orcamentos" element={<BudgetsPage />} />
        <Route path="categorias" element={<CategoriesPage />}>
          <Route path="nova" element={<NewCategorySheet />} />
          <Route path=":id" element={<EditCategorySheet />} />
          <Route path=":id/subcategorias/nova" element={<NewSubcategorySheet />} />
        </Route>
        <Route path="perfil" element={<ProfilePage />} />
      </Route>
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  )
}
