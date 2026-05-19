import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { ApiError, fetchMyLabs } from "../../api";
import type { LabWorkSummary } from "../../types";

export function StudentLabsPage() {
  const [labs, setLabs] = useState<LabWorkSummary[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void fetchMyLabs()
      .then(setLabs)
      .catch((e) => setError(e instanceof ApiError ? e.message : "Ошибка загрузки"));
  }, []);

  return (
    <div>
      <h1 className="page-title">Мои лабораторные</h1>
      {error && <div className="flash error">{error}</div>}
      <div className="card">
        <table className="table">
          <thead>
            <tr>
              <th>Задание</th>
              <th>Статус</th>
              <th>Оценка</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {labs.map((l) => (
              <tr key={l.id}>
                <td>{l.assignmentTitle}</td>
                <td>
                  <span className="pill">{l.status}</span>
                </td>
                <td>{l.finalGradeValue != null ? l.finalGradeValue.toFixed(1) : "—"}</td>
                <td>
                  <Link to={`/student/labs/${l.id}`} className="btn secondary">
                    Открыть
                  </Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {labs.length === 0 && <p className="muted">Пока нет отправленных работ.</p>}
      </div>
    </div>
  );
}
