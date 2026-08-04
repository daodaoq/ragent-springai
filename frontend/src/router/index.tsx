import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import MainLayout from '../layouts/MainLayout'
import LoginPage from '../pages/LoginPage'
import RegisterPage from '../pages/RegisterPage'
import QuestionListPage from '../pages/QuestionListPage'
import QuestionDetailPage from '../pages/QuestionDetailPage'
import AskPage from '../pages/AskPage'
import ChatPage from '../pages/ChatPage'
import KnowledgeBasePage from '../pages/KnowledgeBasePage'
import DashboardPage from '../pages/DashboardPage'
import FeedbackPage from '../pages/FeedbackPage'
import ProfilePage from '../pages/ProfilePage'
import UserManagePage from '../pages/UserManagePage'
import LogsPage from '../pages/LogsPage'
import { useAuthStore } from '../store/auth'
import type { ReactNode } from 'react'

/** 需要登录才能访问 */
function RequireAuth({ children }: { children: ReactNode }) {
  const token = useAuthStore((s) => s.token)
  if (!token) return <Navigate to="/login" replace />
  return <>{children}</>
}

/** 需要指定角色才能访问 */
function RequireRole({ roles, children }: { roles: string[]; children: ReactNode }) {
  const user = useAuthStore((s) => s.user)
  if (!user || !roles.includes(user.role)) return <Navigate to="/" replace />
  return <>{children}</>
}

export default function AppRouter() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<MainLayout />}>
          <Route path="/" element={<QuestionListPage />} />
          <Route path="/questions/:id" element={<QuestionDetailPage />} />
          <Route
            path="/ask"
            element={
              <RequireAuth>
                <AskPage />
              </RequireAuth>
            }
          />
          <Route path="/ai" element={<ChatPage />} />
          <Route path="/kb" element={<KnowledgeBasePage />} />
          <Route
            path="/dashboard"
            element={
              <RequireAuth>
                <DashboardPage />
              </RequireAuth>
            }
          />
          <Route
            path="/feedback"
            element={
              <RequireAuth>
                <RequireRole roles={['ADMIN', 'TEACHER']}>
                  <FeedbackPage />
                </RequireRole>
              </RequireAuth>
            }
          />
          <Route
            path="/profile"
            element={
              <RequireAuth>
                <ProfilePage />
              </RequireAuth>
            }
          />
          <Route
            path="/users"
            element={
              <RequireAuth>
                <RequireRole roles={['ADMIN']}>
                  <UserManagePage />
                </RequireRole>
              </RequireAuth>
            }
          />
          <Route
            path="/logs"
            element={
              <RequireAuth>
                <RequireRole roles={['ADMIN']}>
                  <LogsPage />
                </RequireRole>
              </RequireAuth>
            }
          />
        </Route>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  )
}
