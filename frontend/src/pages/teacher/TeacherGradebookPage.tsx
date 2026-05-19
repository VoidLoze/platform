import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { ApiError, fetchGradebook, setManualGrade } from "../../api";
import type { GradebookCourse, GradebookEntry } from "../../types";

function isGradeLocked(entry: GradebookEntry): boolean {
  return entry.status === "COMPLETED" && entry.finalGradeValue != null;
}

export function TeacherGradebookPage() {
  const { courseId } = useParams<{ courseId: string }>();
  const [journal, setJournal] = useState<GradebookCourse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [draftGrades, setDraftGrades] = useState<Record<string, string>>({});
  const [busyId, setBusyId] = useState<string | null>(null);

  useEffect(() => {
    if (!courseId) return;
    void fetchGradebook(courseId)
      .then((data) => {
        setJournal(data);
        const init: Record<string, string> = {};
        data.entries.forEach((e) => {
          init[e.labWorkId] = e.finalGradeValue == null ? "" : String(e.finalGradeValue);
        });
        setDraftGrades(init);
      })
      .catch((e) => setError(e instanceof ApiError ? e.message : "Ошибка загрузки журнала"));
  }, [courseId]);

  async function saveGrade(labWorkId: string, entry: GradebookEntry) {
    if (isGradeLocked(entry)) return;
    const value = Number(draftGrades[labWorkId]);
    if (!Number.isFinite(value)) return;
    setBusyId(labWorkId);
    setError(null);
    try {
      await setManualGrade(labWorkId, value);
      if (courseId) {
        const data = await fetchGradebook(courseId);
        setJournal(data);
        const init: Record<string, string> = {};
        data.entries.forEach((e) => {
          init[e.labWorkId] = e.finalGradeValue == null ? "" : String(e.finalGradeValue);
        });
        setDraftGrades(init);
      }
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Не удалось сохранить оценку");
    } finally {
      setBusyId(null);
    }
  }

  if (!courseId) return null;

  return (
    <div>
      <p className="muted">
        <Link to={`/teacher/groups/${courseId}`}>← К группе</Link>
      </p>
      <h1 className="page-title">Журнал оценок</h1>
      <p className="muted">{journal?.courseTitle ?? "Группа"}</p>
      {error && <div className="flash error">{error}</div>}

      <div className="card">
        <table className="table">
          <thead>
            <tr>
              <th>Студент</th>
              <th>Задание</th>
              <th>Статус</th>
              <th>Оценка</th>
              <th>Действия</th>
            </tr>
          </thead>
          <tbody>
            {journal?.entries.map((e) => {
              const locked = isGradeLocked(e);
              return (
                <tr key={e.labWorkId}>
                  <td>
                    {e.studentName}
                    <div className="muted">{e.studentEmail}</div>
                  </td>
                  <td>{e.assignmentTitle}</td>
                  <td>
                    <span className="pill">{e.status}</span>
                  </td>
                  <td style={{ minWidth: 120 }}>
                    {locked ? (
                      <strong>{e.finalGradeValue?.toFixed(1)}</strong>
                    ) : (
                      <input
                        type="number"
                        value={draftGrades[e.labWorkId] ?? ""}
                        onChange={(ev) => setDraftGrades((prev) => ({ ...prev, [e.labWorkId]: ev.target.value }))}
                        placeholder="Балл"
                      />
                    )}
                  </td>
                  <td>
                    <div className="row" style={{ flexWrap: "wrap", gap: "0.35rem" }}>
                      <Link to={`/teacher/labs/${e.labWorkId}`} className="btn">
                        Открыть работу
                      </Link>
                      {!locked && (
                        <button
                          type="button"
                          className="btn secondary"
                          disabled={busyId === e.labWorkId}
                          onClick={() => void saveGrade(e.labWorkId, e)}
                        >
                          {busyId === e.labWorkId ? "Сохранение..." : "Сохранить балл"}
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
        {(journal?.entries.length ?? 0) === 0 && <p className="muted">Пока нет отправленных работ для журнала.</p>}
      </div>
    </div>
  );
}
