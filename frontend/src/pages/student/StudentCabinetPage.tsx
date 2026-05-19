import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { motion } from "framer-motion";
import { ApiError, fetchMyLabs, fetchStudentCalendar } from "../../api";
import { useAuth } from "../../context/AuthContext";
import { UserAvatar } from "../../components/UserAvatar";
import type { CalendarTask, LabWorkSummary } from "../../types";

const easeApple = [0.22, 1, 0.36, 1] as const;

export function StudentCabinetPage() {
  const { user } = useAuth();
  const [tasks, setTasks] = useState<CalendarTask[]>([]);
  const [labs, setLabs] = useState<LabWorkSummary[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void Promise.all([fetchStudentCalendar(), fetchMyLabs()])
      .then(([calendar, myLabs]) => {
        setTasks(calendar);
        setLabs(myLabs);
      })
      .catch((e) => setError(e instanceof ApiError ? e.message : "Не удалось загрузить кабинет"));
  }, []);

  const avgGrade = useMemo(() => {
    const withGrade = labs.filter((l) => l.finalGradeValue !== null);
    if (withGrade.length === 0) {
      return null;
    }
    const sum = withGrade.reduce((acc, cur) => acc + (cur.finalGradeValue ?? 0), 0);
    return sum / withGrade.length;
  }, [labs]);

  const nearest = tasks.slice(0, 8);

  return (
    <div className="cabinet-page apple-page">
      <motion.section
        className="cabinet-hero glass-panel"
        initial={{ opacity: 0, y: 24 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.65, ease: easeApple }}
      >
        <div className="cabinet-hero-main">
          <div>
            <p className="hero-kicker">Личный кабинет</p>
            <h1 className="hero-title">
              Здравствуйте{user?.firstName ? `, ${user.firstName}` : ""}
            </h1>
            <div className="cabinet-hero-actions">
              <Link to="/profile" className="btn glow-btn">
                Настроить профиль
              </Link>
              <Link to="/student/calendar" className="btn secondary">
                Календарь
              </Link>
            </div>
          </div>
          {user && (
            <motion.div
              className="cabinet-hero-card"
              initial={{ opacity: 0, scale: 0.94 }}
              animate={{ opacity: 1, scale: 1 }}
              transition={{ delay: 0.12, duration: 0.55, ease: easeApple }}
            >
              <UserAvatar user={user} size={72} className="cabinet-hero-avatar" />
              <div className="cabinet-hero-card-text">
                <span className="cabinet-hero-name">
                  {user.firstName} {user.lastName}
                </span>
                {user.bio && <p className="cabinet-hero-bio muted">{user.bio}</p>}
              </div>
            </motion.div>
          )}
        </div>
      </motion.section>

      {error && <div className="flash error">{error}</div>}

      <div className="stat-grid">
        {[
          { label: "Работ отправлено", value: String(labs.length), delay: 0.08 },
          { label: "Оценено", value: String(labs.filter((l) => l.finalGradeValue !== null).length), delay: 0.12 },
          {
            label: "Средний балл",
            value: avgGrade === null ? "—" : avgGrade.toFixed(1),
            highlight: avgGrade !== null && avgGrade >= 70,
            delay: 0.16,
          },
        ].map((s) => (
          <motion.div
            key={s.label}
            className="stat-tile glass-panel"
            initial={{ opacity: 0, y: 18 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: s.delay, duration: 0.5, ease: easeApple }}
          >
            <span className="stat-tile-label muted">{s.label}</span>
            <span className={`stat-tile-value ${s.highlight ? "stat-tile-value--ok" : ""}`}>{s.value}</span>
          </motion.div>
        ))}
      </div>

      <motion.div
        className="glass-panel cabinet-table-panel"
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.2, duration: 0.55, ease: easeApple }}
      >
        <div className="cabinet-table-head">
          <h2 className="panel-title">Ближайшие дедлайны</h2>
          <Link to="/student/calendar" className="btn secondary">
            Полный календарь
          </Link>
        </div>
        <div className="table-scroll">
          <table className="table table--apple">
            <thead>
              <tr>
                <th>Задание</th>
                <th>Дедлайн</th>
                <th>Статус</th>
              </tr>
            </thead>
            <tbody>
              {nearest.map((task) => (
                <tr key={task.id}>
                  <td>{task.title}</td>
                  <td>{new Date(task.dueDate).toLocaleString()}</td>
                  <td>
                    <span
                      className={`pill ${task.status === "SUBMITTED" ? "ok" : task.status === "OVERDUE" ? "warn" : ""}`}
                    >
                      {task.status}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {nearest.length === 0 && <p className="muted table-empty">Пока нет задач в календаре.</p>}
      </motion.div>
    </div>
  );
}
