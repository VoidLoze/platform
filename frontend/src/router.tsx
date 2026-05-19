import { lazy, Suspense, useEffect, type ReactNode } from "react";
import { Navigate, Route, Routes, useParams } from "react-router-dom";
import { useAuth } from "./context/AuthContext";
import { Layout } from "./components/Layout";
import type { UserRole } from "./types";

const loadLoginPage = () => import("./pages/LoginPage");
const loadRegisterPage = () => import("./pages/RegisterPage");
const loadHomePage = () => import("./pages/HomePage");
const loadStudentCoursesPage = () => import("./pages/student/StudentCoursesPage");
const loadStudentCoursePage = () => import("./pages/student/StudentCoursePage");
const loadStudentAssignmentPage = () => import("./pages/student/StudentAssignmentPage");
const loadStudentLabsPage = () => import("./pages/student/StudentLabsPage");
const loadStudentLabPage = () => import("./pages/student/StudentLabPage");
const loadStudentCabinetPage = () => import("./pages/student/StudentCabinetPage");
const loadStudentCalendarPage = () => import("./pages/student/StudentCalendarPage");
const loadStudentAiCheckPage = () => import("./pages/student/StudentAiCheckPage");
const loadStudentAiCheckCodePage = () => import("./pages/student/StudentAiCheckCodePage");
const loadStudentAiCheckGeneralPage = () => import("./pages/student/StudentAiCheckGeneralPage");
const loadTeacherDashboardPage = () => import("./pages/teacher/TeacherDashboardPage");
const loadTeacherCreateGroupPage = () => import("./pages/teacher/TeacherCreateGroupPage");
const loadTeacherCoursePage = () => import("./pages/teacher/TeacherCoursePage");
const loadTeacherAssignmentPage = () => import("./pages/teacher/TeacherAssignmentPage");
const loadTeacherCabinetPage = () => import("./pages/teacher/TeacherCabinetPage");
const loadTeacherGradebookPage = () => import("./pages/teacher/TeacherGradebookPage");
const loadAdminUsersPage = () => import("./pages/admin/AdminUsersPage");
const loadAdminInviteKeysPage = () => import("./pages/admin/AdminInviteKeysPage");
const loadAdminAiProvidersPage = () => import("./pages/admin/AdminAiProvidersPage");
const loadChatHubPage = () => import("./pages/chat/ChatHubPage");
const loadChatRoomPage = () => import("./pages/chat/ChatRoomPage");
const loadProfilePage = () => import("./pages/ProfilePage");
const loadUserViewPage = () => import("./pages/UserViewPage");

const LoginPage = lazy(() => loadLoginPage().then((m) => ({ default: m.LoginPage })));
const RegisterPage = lazy(() => loadRegisterPage().then((m) => ({ default: m.RegisterPage })));
const HomePage = lazy(() => loadHomePage().then((m) => ({ default: m.HomePage })));
const StudentCoursesPage = lazy(() => loadStudentCoursesPage().then((m) => ({ default: m.StudentCoursesPage })));
const StudentCoursePage = lazy(() => loadStudentCoursePage().then((m) => ({ default: m.StudentCoursePage })));
const StudentAssignmentPage = lazy(() => loadStudentAssignmentPage().then((m) => ({ default: m.StudentAssignmentPage })));
const StudentLabsPage = lazy(() => loadStudentLabsPage().then((m) => ({ default: m.StudentLabsPage })));
const StudentLabPage = lazy(() => loadStudentLabPage().then((m) => ({ default: m.StudentLabPage })));
const StudentCabinetPage = lazy(() => loadStudentCabinetPage().then((m) => ({ default: m.StudentCabinetPage })));
const StudentCalendarPage = lazy(() => loadStudentCalendarPage().then((m) => ({ default: m.StudentCalendarPage })));
const StudentAiCheckPage = lazy(() => loadStudentAiCheckPage().then((m) => ({ default: m.StudentAiCheckPage })));
const StudentAiCheckCodePage = lazy(() => loadStudentAiCheckCodePage().then((m) => ({ default: m.StudentAiCheckCodePage })));
const StudentAiCheckGeneralPage = lazy(() => loadStudentAiCheckGeneralPage().then((m) => ({ default: m.StudentAiCheckGeneralPage })));
const TeacherDashboardPage = lazy(() => loadTeacherDashboardPage().then((m) => ({ default: m.TeacherDashboardPage })));
const TeacherCreateGroupPage = lazy(() => loadTeacherCreateGroupPage().then((m) => ({ default: m.TeacherCreateGroupPage })));
const TeacherCoursePage = lazy(() => loadTeacherCoursePage().then((m) => ({ default: m.TeacherCoursePage })));
const TeacherAssignmentPage = lazy(() => loadTeacherAssignmentPage().then((m) => ({ default: m.TeacherAssignmentPage })));
const TeacherCabinetPage = lazy(() => loadTeacherCabinetPage().then((m) => ({ default: m.TeacherCabinetPage })));
const TeacherGradebookPage = lazy(() => loadTeacherGradebookPage().then((m) => ({ default: m.TeacherGradebookPage })));
const AdminUsersPage = lazy(() => loadAdminUsersPage().then((m) => ({ default: m.AdminUsersPage })));
const AdminInviteKeysPage = lazy(() => loadAdminInviteKeysPage().then((m) => ({ default: m.AdminInviteKeysPage })));
const AdminAiProvidersPage = lazy(() => loadAdminAiProvidersPage().then((m) => ({ default: m.AdminAiProvidersPage })));
const ChatHubPage = lazy(() => loadChatHubPage().then((m) => ({ default: m.ChatHubPage })));
const ChatRoomPage = lazy(() => loadChatRoomPage().then((m) => ({ default: m.ChatRoomPage })));
const ProfilePage = lazy(() => loadProfilePage().then((m) => ({ default: m.ProfilePage })));
const UserViewPage = lazy(() => loadUserViewPage().then((m) => ({ default: m.UserViewPage })));

function RequireAuth({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth();
  if (loading) {
    return <div className="muted">Загрузка…</div>;
  }
  if (!user) {
    return <Navigate to="/login" replace />;
  }
  return children;
}

function RequireRole({ roles, children }: { roles: UserRole[]; children: ReactNode }) {
  const { user } = useAuth();
  if (!user || !roles.includes(user.role)) {
    return <Navigate to="/" replace />;
  }
  return children;
}

function RedirectStudentGroup() {
  const { courseId } = useParams<{ courseId: string }>();
  return <Navigate to={`/student/groups/${courseId ?? ""}`} replace />;
}

function RedirectTeacherGroup() {
  const { courseId } = useParams<{ courseId: string }>();
  return <Navigate to={`/teacher/groups/${courseId ?? ""}`} replace />;
}

function RedirectTeacherGradebook() {
  const { courseId } = useParams<{ courseId: string }>();
  return <Navigate to={`/teacher/groups/${courseId ?? ""}/gradebook`} replace />;
}

function LazyPage({ children }: { children: ReactNode }) {
  return <Suspense fallback={<div className="muted">Загрузка…</div>}>{children}</Suspense>;
}

export function AppRoutes() {
  const { user } = useAuth();
  // Prefetch the most probable next routes after auth to hide lazy-load latency.
  useEffect(() => {
    void loadProfilePage();
    if (!user) {
      void loadLoginPage();
      void loadRegisterPage();
      return;
    }
    void loadChatHubPage();
    if (user.role === "ROLE_STUDENT") {
      void loadStudentCoursesPage();
      void loadStudentAssignmentPage();
      void loadStudentLabsPage();
      void loadStudentAiCheckPage();
      void loadStudentAiCheckCodePage();
      void loadStudentAiCheckGeneralPage();
      void loadStudentCalendarPage();
      return;
    }
    if (user.role === "ROLE_TEACHER") {
      void loadTeacherDashboardPage();
      void loadTeacherCreateGroupPage();
      void loadTeacherCabinetPage();
      void loadStudentAiCheckPage();
      void loadStudentAiCheckCodePage();
      void loadStudentAiCheckGeneralPage();
      return;
    }
    if (user.role === "ROLE_ADMIN") {
      void loadAdminUsersPage();
      void loadAdminInviteKeysPage();
      void loadAdminAiProvidersPage();
    }
  }, [user]);

  return (
    <Routes>
      <Route element={<Layout />}>
        <Route path="/login" element={<LazyPage><LoginPage /></LazyPage>} />
        <Route path="/register" element={<LazyPage><RegisterPage /></LazyPage>} />
        <Route path="/" element={<LazyPage><HomePage /></LazyPage>} />
        <Route
          path="/profile"
          element={
            <RequireAuth>
              <LazyPage><ProfilePage /></LazyPage>
            </RequireAuth>
          }
        />
        <Route
          path="/student/cabinet"
          element={
            <RequireAuth>
              <RequireRole roles={["ROLE_STUDENT"]}>
                <LazyPage><StudentCabinetPage /></LazyPage>
              </RequireRole>
            </RequireAuth>
          }
        />
        <Route
          path="/student/groups"
          element={
            <RequireAuth>
              <RequireRole roles={["ROLE_STUDENT"]}>
                <LazyPage><StudentCoursesPage /></LazyPage>
              </RequireRole>
            </RequireAuth>
          }
        />
        <Route path="/student/courses" element={<Navigate to="/student/groups" replace />} />
        <Route
          path="/student/groups/:courseId"
          element={
            <RequireAuth>
              <RequireRole roles={["ROLE_STUDENT"]}>
                <LazyPage><StudentCoursePage /></LazyPage>
              </RequireRole>
            </RequireAuth>
          }
        />
        <Route path="/student/courses/:courseId" element={<RedirectStudentGroup />} />
        <Route
          path="/student/assignments/:assignmentId"
          element={
            <RequireAuth>
              <RequireRole roles={["ROLE_STUDENT"]}>
                <LazyPage><StudentAssignmentPage /></LazyPage>
              </RequireRole>
            </RequireAuth>
          }
        />
        <Route
          path="/student/calendar"
          element={
            <RequireAuth>
              <RequireRole roles={["ROLE_STUDENT"]}>
                <LazyPage><StudentCalendarPage /></LazyPage>
              </RequireRole>
            </RequireAuth>
          }
        />
        <Route
          path="/student/labs"
          element={
            <RequireAuth>
              <RequireRole roles={["ROLE_STUDENT"]}>
                <LazyPage><StudentLabsPage /></LazyPage>
              </RequireRole>
            </RequireAuth>
          }
        />
        <Route
          path="/student/ai-check"
          element={
            <RequireAuth>
              <RequireRole roles={["ROLE_STUDENT"]}>
                <LazyPage><StudentAiCheckPage /></LazyPage>
              </RequireRole>
            </RequireAuth>
          }
        />
        <Route
          path="/student/ai-check/code"
          element={
            <RequireAuth>
              <RequireRole roles={["ROLE_STUDENT"]}>
                <LazyPage><StudentAiCheckCodePage /></LazyPage>
              </RequireRole>
            </RequireAuth>
          }
        />
        <Route
          path="/student/ai-check/general"
          element={
            <RequireAuth>
              <RequireRole roles={["ROLE_STUDENT"]}>
                <LazyPage><StudentAiCheckGeneralPage /></LazyPage>
              </RequireRole>
            </RequireAuth>
          }
        />
        <Route
          path="/student/labs/:labId"
          element={
            <RequireAuth>
              <RequireRole roles={["ROLE_STUDENT"]}>
                <LazyPage><StudentLabPage /></LazyPage>
              </RequireRole>
            </RequireAuth>
          }
        />
        <Route
          path="/teacher/labs/:labId"
          element={
            <RequireAuth>
              <RequireRole roles={["ROLE_TEACHER"]}>
                <LazyPage><StudentLabPage /></LazyPage>
              </RequireRole>
            </RequireAuth>
          }
        />
        <Route
          path="/teacher/ai-check"
          element={
            <RequireAuth>
              <RequireRole roles={["ROLE_TEACHER"]}>
                <LazyPage><StudentAiCheckPage /></LazyPage>
              </RequireRole>
            </RequireAuth>
          }
        />
        <Route
          path="/teacher/ai-check/code"
          element={
            <RequireAuth>
              <RequireRole roles={["ROLE_TEACHER"]}>
                <LazyPage><StudentAiCheckCodePage /></LazyPage>
              </RequireRole>
            </RequireAuth>
          }
        />
        <Route
          path="/teacher/ai-check/general"
          element={
            <RequireAuth>
              <RequireRole roles={["ROLE_TEACHER"]}>
                <LazyPage><StudentAiCheckGeneralPage /></LazyPage>
              </RequireRole>
            </RequireAuth>
          }
        />
        <Route
          path="/teacher/cabinet"
          element={
            <RequireAuth>
              <RequireRole roles={["ROLE_TEACHER"]}>
                <LazyPage><TeacherCabinetPage /></LazyPage>
              </RequireRole>
            </RequireAuth>
          }
        />
        <Route
          path="/teacher"
          element={
            <RequireAuth>
              <RequireRole roles={["ROLE_TEACHER"]}>
                <LazyPage><TeacherDashboardPage /></LazyPage>
              </RequireRole>
            </RequireAuth>
          }
        />
        <Route
          path="/teacher/groups/new"
          element={
            <RequireAuth>
              <RequireRole roles={["ROLE_TEACHER"]}>
                <LazyPage><TeacherCreateGroupPage /></LazyPage>
              </RequireRole>
            </RequireAuth>
          }
        />
        <Route
          path="/teacher/groups/:courseId"
          element={
            <RequireAuth>
              <RequireRole roles={["ROLE_TEACHER"]}>
                <LazyPage><TeacherCoursePage /></LazyPage>
              </RequireRole>
            </RequireAuth>
          }
        />
        <Route path="/teacher/courses/:courseId" element={<RedirectTeacherGroup />} />
        <Route
          path="/teacher/groups/:courseId/gradebook"
          element={
            <RequireAuth>
              <RequireRole roles={["ROLE_TEACHER"]}>
                <LazyPage><TeacherGradebookPage /></LazyPage>
              </RequireRole>
            </RequireAuth>
          }
        />
        <Route path="/teacher/courses/:courseId/gradebook" element={<RedirectTeacherGradebook />} />
        <Route
          path="/teacher/assignments/:assignmentId"
          element={
            <RequireAuth>
              <RequireRole roles={["ROLE_TEACHER"]}>
                <LazyPage><TeacherAssignmentPage /></LazyPage>
              </RequireRole>
            </RequireAuth>
          }
        />
        <Route
          path="/chat"
          element={
            <RequireAuth>
              <LazyPage><ChatHubPage /></LazyPage>
            </RequireAuth>
          }
        />
        <Route
          path="/chat/:roomId"
          element={
            <RequireAuth>
              <LazyPage><ChatRoomPage /></LazyPage>
            </RequireAuth>
          }
        />
        <Route
          path="/users/:userId"
          element={
            <RequireAuth>
              <LazyPage><UserViewPage /></LazyPage>
            </RequireAuth>
          }
        />
        <Route
          path="/admin/users"
          element={
            <RequireAuth>
              <RequireRole roles={["ROLE_ADMIN"]}>
                <LazyPage><AdminUsersPage /></LazyPage>
              </RequireRole>
            </RequireAuth>
          }
        />
        <Route
          path="/admin/invite-keys"
          element={
            <RequireAuth>
              <RequireRole roles={["ROLE_ADMIN"]}>
                <LazyPage><AdminInviteKeysPage /></LazyPage>
              </RequireRole>
            </RequireAuth>
          }
        />
        <Route
          path="/admin/ai-providers"
          element={
            <RequireAuth>
              <RequireRole roles={["ROLE_ADMIN"]}>
                <LazyPage><AdminAiProvidersPage /></LazyPage>
              </RequireRole>
            </RequireAuth>
          }
        />
      </Route>
    </Routes>
  );
}
