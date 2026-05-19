import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { ApiError, fetchCourses } from "../../api";
import { GroupAvatar } from "../../components/GroupAvatar";
import type { Course } from "../../types";
import { groupDescriptionForView, parseGroupMeta } from "../../utils/groupSubject";

export function StudentCoursesPage() {
  const [groups, setGroups] = useState<Course[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void fetchCourses()
      .then(setGroups)
      .catch((e) => setError(e instanceof ApiError ? e.message : "Не удалось загрузить группы"));
  }, []);

  return (
    <div className="page-hero">
      <div className="hero-inner">
        <p className="hero-kicker">Обучение</p>
        <h1 className="hero-title">Мои группы</h1>
      </div>
      {error && <div className="flash error">{error}</div>}
      <div className="group-grid">
        {groups.map((c) => (
          <Link to={`/student/groups/${c.id}`} className="group-card" key={c.id}>
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
      <div className="card premium-card">
        {groups.length === 0 && (
          <p className="muted">Вы пока не добавлены ни в одну группу. Дождитесь приглашения от преподавателя.</p>
        )}
      </div>
    </div>
  );
}
