import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { ApiError, fetchMyCourses } from "../../api";
import { GroupAvatar } from "../../components/GroupAvatar";
import type { Course } from "../../types";
import { groupDescriptionForView, parseGroupMeta } from "../../utils/groupSubject";

export function TeacherDashboardPage() {
  const [courses, setCourses] = useState<Course[]>([]);
  const [error, setError] = useState<string | null>(null);

  async function reload() {
    const list = await fetchMyCourses();
    setCourses(list);
  }

  useEffect(() => {
    void reload().catch((e) => setError(e instanceof ApiError ? e.message : "Ошибка"));
  }, []);

  return (
    <div>
      <h1 className="page-title">Панель преподавателя</h1>
      {error && <div className="flash error">{error}</div>}

      <div className="card">
        <h3>Управление группами</h3>
        <p className="muted" style={{ marginTop: 0 }}>
          Создание новой группы вынесено в отдельный раздел.
        </p>
        <Link to="/teacher/groups/new" className="btn secondary">
          Перейти к созданию группы
        </Link>
      </div>

      <div className="group-grid">
        {courses.map((c) => (
          <Link to={`/teacher/groups/${c.id}`} className="group-card" key={c.id}>
            <GroupAvatar title={c.title} avatarFileKey={c.avatarFileKey} />
            <div className="group-card-meta">
              <div className="group-card-title">{c.title}</div>
              <div className="muted">
                {groupDescriptionForView(c.description) || `Предмет: ${parseGroupMeta(c.description).subject || "не задан"}`}
              </div>
            </div>
          </Link>
        ))}
      </div>
      {courses.length === 0 && <p className="muted">Создайте первую группу выше.</p>}
    </div>
  );
}
