import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { ApiError, fetchCourses, fetchStudentCalendar } from "../../api";
import type { CalendarTask, Course } from "../../types";
import { Modal } from "../../components/Modal";

function statusLabel(s: CalendarTask["status"]): { text: string; className: string } {
  switch (s) {
    case "SUBMITTED":
      return { text: "Сдано", className: "ok" };
    case "OVERDUE":
      return { text: "Просрочено", className: "warn" };
    default:
      return { text: "К выполнению", className: "" };
  }
}

export function StudentCalendarPage() {
  const [tasks, setTasks] = useState<CalendarTask[]>([]);
  const [courses, setCourses] = useState<Course[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [detail, setDetail] = useState<CalendarTask | null>(null);

  useEffect(() => {
    void Promise.all([fetchStudentCalendar(), fetchCourses()])
      .then(([t, c]) => {
        setTasks(t);
        setCourses(c);
      })
      .catch((e) => setError(e instanceof ApiError ? e.message : "Не удалось загрузить календарь"));
  }, []);

  const courseTitle = useMemo(() => {
    const m = new Map<string, string>();
    for (const c of courses) {
      m.set(c.id, c.title);
    }
    return m;
  }, [courses]);

  const sorted = useMemo(() => {
    return [...tasks].sort((a, b) => new Date(a.dueDate).getTime() - new Date(b.dueDate).getTime());
  }, [tasks]);

  const upcoming = sorted.filter((t) => t.status !== "SUBMITTED");
  const done = sorted.filter((t) => t.status === "SUBMITTED");

  return (
    <div className="page-hero">
      <div className="hero-inner">
        <p className="hero-kicker">Расписание</p>
        <h1 className="hero-title">Дедлайны и работы</h1>
      </div>

      {error && <div className="flash error">{error}</div>}

      <div className="calendar-grid">
        <section className="calendar-col">
          <h2 className="section-title">Предстоящие</h2>
          <div className="task-stack">
            {upcoming.length === 0 && <p className="muted">Нет активных дедлайнов — отличная работа.</p>}
            {upcoming.map((t) => {
              const st = statusLabel(t.status);
              const due = new Date(t.dueDate);
              return (
                <button type="button" key={t.id} className="task-card" onClick={() => setDetail(t)}>
                  <div className="task-card-top">
                    <span className={`pill ${st.className}`}>{st.text}</span>
                    <time className="task-due">{due.toLocaleString()}</time>
                  </div>
                  <div className="task-course">{courseTitle.get(t.courseId) ?? "Группа"}</div>
                  <div className="task-title">{t.title}</div>
                </button>
              );
            })}
          </div>
        </section>

        <section className="calendar-col calendar-col--done">
          <h2 className="section-title">Выполнено</h2>
          <div className="task-stack">
            {done.length === 0 && <p className="muted">Здесь появятся сданные работы.</p>}
            {done.map((t) => (
              <div key={t.id} className="task-card task-card--static">
                <div className="task-card-top">
                  <span className="pill ok">Сдано</span>
                  <time className="task-due muted">{new Date(t.dueDate).toLocaleDateString()}</time>
                </div>
                <div className="task-course">{courseTitle.get(t.courseId) ?? "Группа"}</div>
                <div className="task-title">{t.title}</div>
              </div>
            ))}
          </div>
        </section>
      </div>

      <Modal
        open={detail != null}
        title={detail?.title ?? ""}
        onClose={() => setDetail(null)}
        footer={
          detail && (
            <div className="row" style={{ justifyContent: "flex-end", gap: "0.75rem" }}>
              <Link className="btn secondary" to={`/student/groups/${detail.courseId}`}>
                Группа
              </Link>
              <Link className="btn glow-btn" to={`/student/assignments/${detail.assignmentId}`}>
                {detail.status === "SUBMITTED"
                  ? "Открыть задание"
                  : detail.status === "OVERDUE"
                    ? "Сдать работу"
                    : "Выполнить задание"}
              </Link>
            </div>
          )
        }
      >
        {detail && (
          <>
            <p className="muted">
              Группа: <strong>{courseTitle.get(detail.courseId) ?? detail.courseId}</strong>
            </p>
            <p>
              Дедлайн: <strong>{new Date(detail.dueDate).toLocaleString()}</strong>
            </p>
            <p>
              Статус: <span className={`pill ${statusLabel(detail.status).className}`}>{statusLabel(detail.status).text}</span>
            </p>
          </>
        )}
      </Modal>
    </div>
  );
}
