import { AnimatePresence, motion, useReducedMotion } from "framer-motion";
import { useEffect, useRef, useState } from "react";
import { NavLink, Outlet, useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { useAssignmentAlerts } from "../context/AssignmentAlertsContext";
import { useChatInbox } from "../context/ChatInboxContext";
import { UserAvatar } from "./UserAvatar";
import type { UserRole } from "../types";

function roleLabel(role: UserRole): string {
  switch (role) {
    case "ROLE_STUDENT":
      return "Студент";
    case "ROLE_TEACHER":
      return "Преподаватель";
    case "ROLE_ADMIN":
      return "Админ";
    default:
      return role;
  }
}

export function Layout() {
  const navigate = useNavigate();
  const { user, logout } = useAuth();
  const { unreadCount: assignmentUnread, unreadItems, toast, dismissToast, markAllSeen, markAssignmentSeen } = useAssignmentAlerts();
  const { totalUnread } = useChatInbox();
  const location = useLocation();
  const reduceMotion = useReducedMotion();
  const [alertsOpen, setAlertsOpen] = useState(false);
  const alertsRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    setAlertsOpen(false);
  }, [location.pathname]);

  useEffect(() => {
    if (!alertsOpen) return;
    function onDocClick(e: MouseEvent) {
      if (!alertsRef.current?.contains(e.target as Node)) {
        setAlertsOpen(false);
      }
    }
    function onEsc(e: KeyboardEvent) {
      if (e.key === "Escape") setAlertsOpen(false);
    }
    document.addEventListener("mousedown", onDocClick);
    document.addEventListener("keydown", onEsc);
    return () => {
      document.removeEventListener("mousedown", onDocClick);
      document.removeEventListener("keydown", onEsc);
    };
  }, [alertsOpen]);

  return (
    <div className="app-shell">
      <header className="top-nav">
        <div className="brand-block">
          <div className="brand">МИИТ Portal</div>
          <div className="brand-tagline">Цифровая образовательная среда</div>
        </div>
        {user ? (
          <nav className="nav-links">
            <NavLink to="/" end className={({ isActive }) => (isActive ? "active" : undefined)}>
              Главная
            </NavLink>
            {user.role === "ROLE_STUDENT" && (
              <>
                <div className="nav-alerts" ref={alertsRef}>
                  <button
                    type="button"
                    className="btn secondary nav-alerts-btn"
                    onClick={() => setAlertsOpen((prev) => !prev)}
                    aria-expanded={alertsOpen}
                    aria-haspopup="menu"
                  >
                    Уведомления
                    {assignmentUnread > 0 ? (
                      <span className="nav-badge" aria-label={`Новых заданий: ${assignmentUnread}`}>
                        {assignmentUnread > 99 ? "99+" : assignmentUnread}
                      </span>
                    ) : null}
                  </button>
                  {alertsOpen && (
                    <div className="nav-alerts-menu" role="menu">
                      <div className="nav-alerts-head">
                        <strong>Новые задания</strong>
                        <button type="button" className="btn secondary nav-alerts-clear" onClick={markAllSeen}>
                          Очистить
                        </button>
                      </div>
                      {unreadItems.length === 0 ? (
                        <p className="muted nav-alerts-empty">Новых заданий нет.</p>
                      ) : (
                        <ul className="nav-alerts-list">
                          {unreadItems.map((item) => (
                            <li key={`${item.courseId}:${item.assignmentId}`}>
                              <NavLink
                                to={`/student/groups/${item.courseId}?assignmentId=${encodeURIComponent(item.assignmentId)}`}
                                className="nav-alerts-link"
                                onClick={() => markAssignmentSeen(item.courseId, item.assignmentId)}
                              >
                                <span className="nav-alerts-title">{item.assignmentTitle}</span>
                                <span className="nav-alerts-meta">
                                  {item.courseTitle} · дедлайн {new Date(item.dueDate).toLocaleString()}
                                </span>
                              </NavLink>
                            </li>
                          ))}
                        </ul>
                      )}
                    </div>
                  )}
                </div>
                <NavLink to="/student/cabinet" className={({ isActive }) => (isActive ? "active" : undefined)}>
                  Кабинет
                </NavLink>
                <NavLink to="/student/groups" className={({ isActive }) => (isActive ? "active" : undefined)}>
                  Группы
                  {assignmentUnread > 0 ? (
                    <span className="nav-badge" aria-label={`Новых заданий: ${assignmentUnread}`}>
                      {assignmentUnread > 99 ? "99+" : assignmentUnread}
                    </span>
                  ) : null}
                </NavLink>
                <NavLink to="/student/calendar" className={({ isActive }) => (isActive ? "active" : undefined)}>
                  Календарь
                </NavLink>
                <NavLink to="/student/labs" className={({ isActive }) => (isActive ? "active" : undefined)}>
                  Мои работы
                </NavLink>
                <NavLink
                  to="/student/ai-check"
                  className={({ isActive }) =>
                    isActive || location.pathname.startsWith("/student/ai-check/") ? "active" : undefined
                  }
                >
                  AI-проверка
                </NavLink>
              </>
            )}
            {user.role === "ROLE_TEACHER" && (
              <>
                <NavLink to="/teacher/cabinet" className={({ isActive }) => (isActive ? "active" : undefined)}>
                  Кабинет преподавателя
                </NavLink>
                <NavLink to="/teacher" className={({ isActive }) => (isActive ? "active" : undefined)}>
                  Мои группы
                </NavLink>
              </>
            )}
            <NavLink
              to="/chat"
              className={({ isActive }) =>
                ["nav-link-chat", isActive || location.pathname.startsWith("/chat/") ? "active" : undefined]
                  .filter(Boolean)
                  .join(" ")
              }
            >
              Чат
              {totalUnread > 0 ? (
                <span className="nav-badge" aria-label={`Непрочитанных сообщений: ${totalUnread}`}>
                  {totalUnread > 99 ? "99+" : totalUnread}
                </span>
              ) : null}
            </NavLink>
            <NavLink to="/profile" className={({ isActive }) => (isActive ? "active" : undefined)}>
              Профиль
            </NavLink>
            {user.role === "ROLE_ADMIN" && (
              <>
                <NavLink to="/admin/users" className={({ isActive }) => (isActive ? "active" : undefined)}>
                  Пользователи
                </NavLink>
                <NavLink to="/admin/invite-keys" className={({ isActive }) => (isActive ? "active" : undefined)}>
                  Приглашения
                </NavLink>
                <NavLink to="/admin/ai-providers" className={({ isActive }) => (isActive ? "active" : undefined)}>
                  AI-провайдеры
                </NavLink>
              </>
            )}
            <div className="nav-user">
              <UserAvatar user={user} size={34} />
              <span className="nav-user-meta muted">
                <span className="nav-user-name">
                  {user.firstName} {user.lastName}
                </span>
                <span className="nav-user-role">{roleLabel(user.role)}</span>
              </span>
            </div>
            <button type="button" className="btn secondary nav-logout" onClick={() => logout()}>
              Выйти
            </button>
          </nav>
        ) : (
          <nav className="nav-links">
            <NavLink to="/login" className={({ isActive }) => (isActive ? "active" : undefined)}>
              Вход
            </NavLink>
            <NavLink to="/register" className={({ isActive }) => (isActive ? "active" : undefined)}>
              Регистрация
            </NavLink>
          </nav>
        )}
      </header>
      <main className="app-main">
        <AnimatePresence mode="wait">
          <motion.div
            key={location.pathname}
            className="page-transition-root"
            initial={reduceMotion ? false : { opacity: 0, y: 10 }}
            animate={{ opacity: 1, y: 0 }}
            exit={reduceMotion ? undefined : { opacity: 0, y: -6 }}
            transition={{ duration: reduceMotion ? 0 : 0.2, ease: [0.22, 1, 0.36, 1] }}
          >
            <Outlet />
          </motion.div>
        </AnimatePresence>
        {user?.role === "ROLE_STUDENT" && toast && (
          <button
            type="button"
            className="assignment-toast"
            role="status"
            aria-live="polite"
            onClick={() => {
              markAssignmentSeen(toast.courseId, toast.assignmentId);
              dismissToast();
              void navigate(`/student/groups/${toast.courseId}?assignmentId=${encodeURIComponent(toast.assignmentId)}`);
            }}
          >
            <div className="assignment-toast-title">{toast.title}</div>
            <div className="assignment-toast-subtitle">{toast.subtitle}</div>
            <span
              className="assignment-toast-close"
              onClick={(e) => {
                e.stopPropagation();
                dismissToast();
              }}
              aria-label="Закрыть уведомление"
              role="button"
            >
              ×
            </span>
          </button>
        )}
      </main>
    </div>
  );
}
