import { useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { ApiError, fetchUsers } from "../api";
import { UserAvatar } from "../components/UserAvatar";
import type { UserProfile } from "../types";

function roleLabel(role: UserProfile["role"]): string {
  if (role === "ROLE_TEACHER") return "Преподаватель";
  if (role === "ROLE_STUDENT") return "Студент";
  if (role === "ROLE_ADMIN") return "Администратор";
  return role;
}

export function UserViewPage() {
  const { userId } = useParams<{ userId: string }>();
  const [users, setUsers] = useState<UserProfile[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void fetchUsers()
      .then(setUsers)
      .catch((e) => setError(e instanceof ApiError ? e.message : "Не удалось загрузить профиль пользователя"));
  }, []);

  const target = useMemo(() => users.find((u) => u.id === userId) ?? null, [users, userId]);

  return (
    <div className="page-hero">
      <p className="muted">
        <Link to="/chat">← К чатам</Link>
      </p>
      {error && <div className="flash error">{error}</div>}
      {!target && !error && <div className="muted">Пользователь не найден.</div>}
      {target && (
        <div className="card premium-card">
          <div className="row" style={{ alignItems: "center", gap: "0.75rem" }}>
            <UserAvatar user={target} size={56} />
            <div>
              <h1 className="hero-title" style={{ marginBottom: "0.25rem" }}>
                {target.firstName} {target.lastName}
              </h1>
              <p className="muted" style={{ margin: 0 }}>
                {roleLabel(target.role)}
              </p>
            </div>
          </div>
          <div className="field" style={{ marginTop: "1rem" }}>
            <label>Email</label>
            <div>{target.email}</div>
          </div>
          <div className="field">
            <label>О себе</label>
            <div className="muted">{target.bio || "Пользователь пока не добавил описание."}</div>
          </div>
        </div>
      )}
    </div>
  );
}
