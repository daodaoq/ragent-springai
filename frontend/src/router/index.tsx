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

export default function AppRouter() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<MainLayout />}>
          <Route path="/" element={<QuestionListPage />} />
          <Route path="/questions/:id" element={<QuestionDetailPage />} />
          <Route path="/ask" element={<AskPage />} />
          <Route path="/ai" element={<ChatPage />} />
          <Route path="/kb" element={<KnowledgeBasePage />} />
          <Route path="/dashboard" element={<DashboardPage />} />
        </Route>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  )
}
