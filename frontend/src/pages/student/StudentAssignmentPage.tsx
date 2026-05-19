import { useEffect, useMemo, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { Modal } from "../../components/Modal";
import {
  ApiError,
  downloadFileByKey,
  triggerBlobDownload,
  fetchAssignment,
  fetchAssignmentMaterials,
  fetchAssignmentTest,
  fetchCourse,
  fetchMyAssignmentTestStatus,
  fetchMyLabs,
  submitAssignmentTest,
  submitLab,
  uploadFile,
} from "../../api";
import { useAssignmentAlerts } from "../../context/AssignmentAlertsContext";
import type { Assignment, AssignmentMaterial, AssignmentTest, LabWorkSummary } from "../../types";
import { isAssignmentPastDue } from "../../utils/assignmentDue";

export function StudentAssignmentPage() {
  const { assignmentId } = useParams<{ assignmentId: string }>();
  const navigate = useNavigate();
  const { markAssignmentSeen } = useAssignmentAlerts();

  const [assignment, setAssignment] = useState<Assignment | null>(null);
  const [courseTitle, setCourseTitle] = useState<string>("");
  const [materials, setMaterials] = useState<AssignmentMaterial[]>([]);
  const [test, setTest] = useState<AssignmentTest | null>(null);
  const [testAttempt, setTestAttempt] = useState<{ attempted: boolean; score: number | null } | null>(null);
  const [myLab, setMyLab] = useState<LabWorkSummary | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const [text, setText] = useState("");
  const [answerFile, setAnswerFile] = useState<File | null>(null);
  const [submittingWork, setSubmittingWork] = useState(false);

  const [testAnswers, setTestAnswers] = useState<Record<string, string>>({});
  const [submittingTest, setSubmittingTest] = useState(false);
  const [resultModalOpen, setResultModalOpen] = useState(false);
  const [lastResult, setLastResult] = useState<{ correct: number; total: number; score: number } | null>(null);

  const isTest = Boolean(test);
  const pastDue = assignment ? isAssignmentPastDue(assignment.dueDate) : false;

  useEffect(() => {
    if (!assignmentId) return;
    setLoading(true);
    setError(null);
    void (async () => {
      try {
        const a = await fetchAssignment(assignmentId);
        setAssignment(a);
        markAssignmentSeen(a.courseId, a.id);

        const [c, m, labs] = await Promise.all([
          fetchCourse(a.courseId),
          fetchAssignmentMaterials(assignmentId),
          fetchMyLabs(),
        ]);
        setCourseTitle(c.title);
        setMaterials(m);
        const lab = labs.find((l) => l.assignmentId === assignmentId) ?? null;
        setMyLab(lab);

        let t: AssignmentTest | null = null;
        try {
          t = await fetchAssignmentTest(assignmentId);
        } catch {
          t = null;
        }
        setTest(t);
        if (t) {
          const init: Record<string, string> = {};
          t.questions.forEach((q) => init[q.id] = "");
          setTestAnswers(init);
          const status = await fetchMyAssignmentTestStatus(assignmentId);
          setTestAttempt(status);
        } else {
          setTestAttempt(null);
        }
      } catch (e) {
        setError(e instanceof ApiError ? e.message : "Не удалось загрузить задание");
      } finally {
        setLoading(false);
      }
    })();
  }, [assignmentId, markAssignmentSeen]);

  const allQuestionsAnswered = useMemo(() => {
    if (!test) return false;
    return test.questions.every((q) => Boolean(testAnswers[q.id]));
  }, [test, testAnswers]);

  async function onSubmitWork() {
    if (!assignmentId) return;
    if (!text.trim() && !answerFile) {
      setError("Добавьте текст ответа или прикрепите файл.");
      return;
    }
    setSubmittingWork(true);
    setError(null);
    try {
      let attachmentKey: string | null = null;
      if (answerFile) {
        const uploaded = await uploadFile(answerFile, "student-answers");
        attachmentKey = uploaded.key;
      }
      const res = await submitLab(assignmentId, {
        textContent: text.trim() || null,
        attachmentKey,
        language: null,
      });
      navigate(`/student/labs/${res.id}`);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Не удалось отправить работу");
    } finally {
      setSubmittingWork(false);
    }
  }

  async function onSubmitTest() {
    if (!assignmentId) return;
    setSubmittingTest(true);
    setError(null);
    try {
      const res = await submitAssignmentTest(assignmentId, testAnswers);
      setLastResult(res);
      setResultModalOpen(true);
      setTestAttempt({ attempted: true, score: res.score });
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Ошибка отправки теста");
    } finally {
      setSubmittingTest(false);
    }
  }

  if (!assignmentId) {
    return null;
  }

  return (
    <div className="page-hero">
      <p className="muted">
        <Link to="/student/calendar">← Календарь</Link>
        {assignment && (
          <>
            {" · "}
            <Link to={`/student/groups/${assignment.courseId}`}>Группа</Link>
          </>
        )}
      </p>

      {loading && <p className="muted">Загрузка задания…</p>}
      {error && <div className="flash error">{error}</div>}

      {assignment && !loading && (
        <>
          <div className="hero-inner">
            <p className="hero-kicker">{courseTitle || "Группа"}</p>
            <h1 className="hero-title">{assignment.topicTitle}</h1>
            <p className="muted">
              Дедлайн: {new Date(assignment.dueDate).toLocaleString()}
              {pastDue && (
                <>
                  {" "}
                  <span className="pill warn">просрочено</span>
                </>
              )}
              {" · "}
              Макс. балл: {assignment.maxScore}
            </p>
          </div>

          {assignment.topicDescription && (
            <div className="card premium-card">
              <h3>Условие задания</h3>
              <p style={{ whiteSpace: "pre-wrap", margin: 0 }}>{assignment.topicDescription}</p>
            </div>
          )}

          {materials.length > 0 && (
            <div className="card premium-card">
              <h3>Материалы от преподавателя</h3>
              {materials.map((m) => (
                <div key={m.id} className="row" style={{ marginBottom: "0.5rem", justifyContent: "space-between" }}>
                  <span>{m.originalName}</span>
                  <button
                    type="button"
                    className="btn secondary"
                    onClick={() =>
                      void downloadFileByKey(m.fileKey, {
                        suggestedFilename: m.originalName,
                        contentType: m.contentType,
                      }).then(({ url, filename }) => {
                        triggerBlobDownload(url, filename);
                        window.setTimeout(() => URL.revokeObjectURL(url), 4000);
                      })
                    }
                  >
                    Скачать
                  </button>
                </div>
              ))}
            </div>
          )}

          {isTest ? (
            <div className="card premium-card">
              <h3>Контрольный тест</h3>
              {testAttempt?.attempted ? (
                <>
                  <p className="flash ok">Тест уже сдан. Результат: {Number(testAttempt.score ?? 0).toFixed(1)}</p>
                  <p className="muted">Повторная отправка недоступна.</p>
                </>
              ) : (
                <>
                  <p className="muted">Выберите один вариант на каждый вопрос. Попытка одна.</p>
                  <div className="student-test-modal" style={{ marginTop: "0.75rem" }}>
                    {test?.questions.map((q, index) => (
                      <div key={q.id} className="field student-test-question">
                        <label>
                          {index + 1}. {q.questionText}
                        </label>
                        <div className="test-options-grid">
                          {q.options.slice(0, 6).map((opt) => (
                            <label key={`${q.id}-${opt}`} className="test-option-tile">
                              <input
                                type="radio"
                                name={`q-${q.id}`}
                                value={opt}
                                checked={testAnswers[q.id] === opt}
                                onChange={(e) => setTestAnswers((prev) => ({ ...prev, [q.id]: e.target.value }))}
                              />
                              <span>{opt}</span>
                            </label>
                          ))}
                        </div>
                      </div>
                    ))}
                  </div>
                  <button
                    type="button"
                    className="btn glow-btn"
                    disabled={submittingTest || !allQuestionsAnswered}
                    onClick={() => void onSubmitTest()}
                  >
                    {submittingTest ? "Отправка…" : "Отправить тест"}
                  </button>
                </>
              )}
            </div>
          ) : myLab ? (
            <div className="card premium-card">
              <h3>Работа уже отправлена</h3>
              <p className="muted">
                Статус: <span className="pill">{myLab.status}</span>
                {myLab.finalGradeValue != null && (
                  <>
                    {" "}
                    · Оценка: <strong>{myLab.finalGradeValue.toFixed(1)}</strong>
                  </>
                )}
              </p>
              <Link to={`/student/labs/${myLab.id}`} className="btn">
                Открыть мою сдачу
              </Link>
            </div>
          ) : (
            <div className="card premium-card">
              <h3>Сдача лабораторной работы</h3>
              <p className="muted" style={{ marginTop: 0 }}>
                Прочитайте условие выше, при необходимости скачайте материалы. Отправьте текстовый ответ и/или файл с решением.
              </p>
              <div className="field">
                <label>Текстовый ответ</label>
                <textarea
                  value={text}
                  onChange={(e) => setText(e.target.value)}
                  placeholder="Опишите решение, выводы или комментарий к файлу"
                  rows={8}
                />
              </div>
              <div className="field">
                <label>Файл с работой</label>
                <input type="file" onChange={(e) => setAnswerFile(e.target.files?.[0] ?? null)} />
                {answerFile && <p className="muted small-print">Выбран: {answerFile.name}</p>}
              </div>
              <button type="button" className="btn glow-btn" disabled={submittingWork} onClick={() => void onSubmitWork()}>
                {submittingWork ? "Отправка…" : "Отправить работу"}
              </button>
            </div>
          )}
        </>
      )}

      <Modal
        open={resultModalOpen && Boolean(lastResult)}
        title="Результат теста"
        onClose={() => setResultModalOpen(false)}
        footer={
          <div className="modal-actions">
            <button type="button" className="btn glow-btn" onClick={() => setResultModalOpen(false)}>
              Закрыть
            </button>
          </div>
        }
      >
        {lastResult && (
          <>
            <p>Тест отправлен.</p>
            <p className="muted">
              Верных ответов: {lastResult.correct} из {lastResult.total}
            </p>
            <p className="muted">Результат: {lastResult.score.toFixed(1)}</p>
          </>
        )}
      </Modal>
    </div>
  );
}
