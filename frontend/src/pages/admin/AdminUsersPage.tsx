import { useEffect, useState } from "react";
import { ApiError, fetchAdminUsers } from "../../api";
import type { UserProfile } from "../../types";

export function AdminUsersPage() {
  const [users, setUsers] = useState<UserProfile[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void fetchAdminUsers()
      .then(setUsers)
      .catch((e) => setError(e instanceof ApiError ? e.message : "Ошибка"));
  }, []);

  return (
    <div>
      <h1 className="page-title">Пользователи</h1>
      {error && <div className="flash error">{error}</div>}
      <div className="card">
        <table className="table">
          <thead>
            <tr>
              <th>Имя</th>
              <th>Email</th>
              <th>Роль</th>
            </tr>
          </thead>
          <tbody>
            {users.map((u) => (
              <tr key={u.id}>
                <td>
                  {u.firstName} {u.lastName}
                </td>
                <td>{u.email}</td>
                <td>
                  <span className="pill">{u.role}</span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
