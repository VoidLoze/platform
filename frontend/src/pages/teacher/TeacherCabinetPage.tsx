import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { motion } from "framer-motion";
import { ApiError, fetchTeacherCalendar, fetchTeacherProgress } from "../../api";
import { useAuth } from "../../context/AuthContext";
import { GroupAvatar } from "../../components/GroupAvatar";
import { UserAvatar } from "../../components/UserAvatar";
import type { CalendarTask, CourseProgress } from "../../types";
import { isAssignmentPastDue } from "../../utils/assignmentDue";

const easeApple = [0.22, 1, 0.36, 1] as const;

function TaskRow({ task, overdue }: { task: CalendarTask; overdue: boolean }) {
  return (
    <tr className={overdue ? "table-row-overdue" : undefined}>
      <td>
        <div className="cabinet-task-cell">
          <GroupAvatar title={task.courseTitle ?? "Группа"} avatarFileKey={task.courseAvatarFileKey ?? null} size={30} />
          <div>
            <div className="cabinet-task-group">{task.courseTitle ?? "Группа"}</div>
            <div className="cabinet-task-title">{task.title}</div>
          </div>
        </div>
      </td>
      <td>
        {overdue && <span className="pill warn">истёк</span>}
        <div className="muted" style={{ fontSize: overdue ? "0.82rem" : undefined, marginTop: overdue ? "0.2rem" : undefined }}>
          {new Date(task.dueDate).toLocaleString()}
        </div>
      </td>
      <td>
        <Link to={`/teacher/assignments/${task.assignmentId}`} className="btn secondary">
          Сдачи
        </Link>
        {overdue && task.courseId && (
          <Link
            to={`/teacher/groups/${task.courseId}?due=past`}
            className="btn secondary"
            style={{ marginLeft: "0.4rem" }}
          >
            Группа
          </Link>
        )}
      </td>
    </tr>
  );
}


export function TeacherCabinetPage() {
  const { user } = useAuth();
  const [calendar, setCalendar] = useState<CalendarTask[]>([]);
  const [progress, setProgress] = useState<CourseProgress[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void Promise.all([fetchTeacherCalendar(), fetchTeacherProgress()])
      .then(([tasks, stats]) => {
        setCalendar(tasks);
        setProgress(stats);
      })
      .catch((e) => setError(e instanceof ApiError ? e.message : "Не удалось загрузить кабинет преподавателя"));
  }, []);

  const pastDueTasks = useMemo(
    () => calendar.filter((t) => t.status === "OVERDUE" || isAssignmentPastDue(t.dueDate)),
    [calendar],
  );
  const upcomingTasks = useMemo(
    () => calendar.filter((t) => t.status !== "OVERDUE" && !isAssignmentPastDue(t.dueDate)),
    [calendar],
  );

  return (
    <motion.div
      className="cabinet-page apple-page"
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.45, ease: easeApple }}
    >
      <motion.section
        className="cabinet-hero glass-panel"
        initial={{ opacity: 0, y: 24 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.65, ease: easeApple }}
      >
        <div className="cabinet-hero-main">
          <div>
            <p className="hero-kicker">Преподаватель</p>
            <h1 className="hero-title">Кабинет{user?.firstName ? `, ${user.firstName}` : ""}</h1>
            <div className="cabinet-hero-actions">
              <Link to="/profile" className="btn glow-btn">
                Настроить профиль
              </Link>
              <Link to="/teacher" className="btn secondary">
                Мои группы
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

      <motion.div
        className="glass-panel cabinet-table-panel cabinet-table-panel--overdue"
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.08, duration: 0.55, ease: easeApple }}
      >
        <h2 className="panel-title">Дедлайн истёк — проверка сдач</h2>
        <p className="muted" style={{ marginTop: 0, marginBottom: "0.75rem" }}>
          Задания, по которым дедлайн уже прошёл. Откройте список сдач и проверьте работы учеников.
        </p>
        <div className="table-scroll">
          <table className="table table--apple">
            <thead>
              <tr>
                <th>Группа / задание</th>
                <th>Дедлайн</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {pastDueTasks.map((task) => (
                <TaskRow key={task.id} task={task} overdue />
              ))}
            </tbody>
          </table>
        </div>
        {pastDueTasks.length === 0 && <p className="muted table-empty">Нет заданий с истёкшим дедлайном.</p>}
      </motion.div>

      <motion.div
        className="glass-panel cabinet-table-panel"
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.12, duration: 0.55, ease: easeApple }}
      >
        <h2 className="panel-title">Предстоящие дедлайны</h2>
        <div className="table-scroll">
          <table className="table table--apple">
            <thead>
              <tr>
                <th>Группа / задание</th>
                <th>Дедлайн</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {upcomingTasks.slice(0, 12).map((task) => (
                <TaskRow key={task.id} task={task} overdue={false} />
              ))}
            </tbody>
          </table>
        </div>
        {upcomingTasks.length === 0 && <p className="muted table-empty">Нет активных дедлайнов впереди.</p>}
      </motion.div>

      <motion.div
        className="glass-panel cabinet-table-panel"
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.18, duration: 0.55, ease: easeApple }}
      >
        <h2 className="panel-title">Сводка по успеваемости</h2>
        <div className="table-scroll">
          <table className="table table--apple">
            <thead>
              <tr>
                <th>Группа</th>
                <th>Заданий</th>
                <th>Сдач</th>
                <th>Средний балл</th>
                <th>Студентов</th>
              </tr>
            </thead>
            <tbody>
              {progress.map((course) => (
                <tr key={course.courseId}>
                  <td>
                    <Link to={`/teacher/groups/${course.courseId}?due=past`}>{course.courseTitle}</Link>
                  </td>
                  <td>{course.assignmentsCount}</td>
                  <td>{course.submissionsCount}</td>
                  <td>{course.averageGrade.toFixed(1)}</td>
                  <td>{course.students.length}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {progress.length === 0 && <p className="muted table-empty">Пока нет групп для анализа.</p>}
      </motion.div>
    </motion.div>
  );
}
