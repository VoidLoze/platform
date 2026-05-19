import { useEffect, useMemo, useState } from "react";
import { Link, useLocation, useParams } from "react-router-dom";
import { GroupAvatar } from "../../components/GroupAvatar";
import { Modal } from "../../components/Modal";
import { useAssignmentAlerts } from "../../context/AssignmentAlertsContext";
import {
  ApiError,
  downloadFileByKey,
  triggerBlobDownload,
  fetchGroupMaterials,
  fetchGroupPosts,
  fetchAssignmentMaterials,
  fetchMyAssignmentTestStatus,
  fetchAssignmentTest,
  fetchAssignments,
  fetchCourse,
  submitLab,
  submitAssignmentTest,
  uploadFile,
} from "../../api";
import type { Assignment, AssignmentMaterial, AssignmentTest, Course, GroupMaterial, GroupPost } from "../../types";

export function StudentCoursePage() {
  const { courseId } = useParams<{ courseId: string }>();
  const location = useLocation();
  const { markAssignmentSeen } = useAssignmentAlerts();
  const [course, setCourse] = useState<Course | null>(null);
  const [assignments, setAssignments] = useState<Assignment[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [openId, setOpenId] = useState<string | null>(null);
  const [text, setText] = useState("");
  const [answerFile, setAnswerFile] = useState<File | null>(null);
  const [materials, setMaterials] = useState<AssignmentMaterial[]>([]);
  const [testsByAssignment, setTestsByAssignment] = useState<Record<string, AssignmentTest | null>>({});
  const [testModalOpen, setTestModalOpen] = useState(false);
  const [resultModalOpen, setResultModalOpen] = useState(false);
  const [activeTest, setActiveTest] = useState<AssignmentTest | null>(null);
  const [activeAssignmentId, setActiveAssignmentId] = useState<string | null>(null);
  const [testAnswers, setTestAnswers] = useState<Record<string, string>>({});
  const [submittingTest, setSubmittingTest] = useState(false);
  const [submittingWork, setSubmittingWork] = useState(false);
  const [lastResult, setLastResult] = useState<{ correct: number; total: number; score: number } | null>(null);
  const [attemptStatus, setAttemptStatus] = useState<Record<string, { attempted: boolean; score: number | null }>>({});
  const [posts, setPosts] = useState<GroupPost[]>([]);
  const [groupMaterials, setGroupMaterials] = useState<GroupMaterial[]>([]);
  const [activeTab, setActiveTab] = useState<"posts" | "materials" | "assignments">("posts");

  useEffect(() => {
    if (!courseId) {
      return;
    }
    void Promise.all([fetchCourse(courseId), fetchAssignments(courseId), fetchGroupPosts(courseId), fetchGroupMaterials(courseId)])
      .then(([c, a, p, gm]) => {
        setCourse(c);
        setAssignments(a);
        setPosts(p);
        setGroupMaterials(gm);
      })
      .catch((e) => setError(e instanceof ApiError ? e.message : "Ошибка загрузки"));
  }, [courseId, markAssignmentSeen]);

  useEffect(() => {
    if (assignments.length === 0) {
      setTestsByAssignment({});
      return;
    }
    let cancelled = false;
    void Promise.all(
      assignments.map(async (a) => [a.id, await fetchAssignmentTest(a.id).catch(() => null)] as const),
    ).then((entries) => {
      if (!cancelled) {
        setTestsByAssignment(Object.fromEntries(entries));
      }
    });
    return () => {
      cancelled = true;
    };
  }, [assignments]);

  useEffect(() => {
    const testAssignmentIds = Object.entries(testsByAssignment)
      .filter(([, t]) => Boolean(t))
      .map(([id]) => id);
    if (testAssignmentIds.length === 0) return;
    let cancelled = false;
    void Promise.all(
      testAssignmentIds.map(async (assignmentId) => [
        assignmentId,
        await fetchMyAssignmentTestStatus(assignmentId).catch(() => ({ attempted: false, score: null })),
      ] as const),
    ).then((entries) => {
      if (!cancelled) {
        setAttemptStatus((prev) => ({ ...prev, ...Object.fromEntries(entries) }));
      }
    });
    return () => {
      cancelled = true;
    };
  }, [testsByAssignment]);

  useEffect(() => {
    if (!courseId) return;
    const params = new URLSearchParams(location.search);
    const assignmentId = params.get("assignmentId");
    if (!assignmentId) return;
    setActiveTab("assignments");
    void onOpenAssignment(assignmentId);
    markAssignmentSeen(courseId, assignmentId);
  }, [courseId, location.search, markAssignmentSeen]);

  async function onOpenAssignment(assignmentId: string) {
    const next = openId === assignmentId ? null : assignmentId;
    setOpenId(next);
    setText("");
    setAnswerFile(null);
    if (!next) return;
    if (courseId) {
      markAssignmentSeen(courseId, assignmentId);
    }
    try {
      const [m, t] = await Promise.all([
        fetchAssignmentMaterials(assignmentId),
        fetchAssignmentTest(assignmentId).catch(() => null),
      ]);
      setMaterials(m);
      setTestsByAssignment((prev) => ({ ...prev, [assignmentId]: t }));
      const init: Record<string, string> = {};
      t?.questions.forEach((q) => {
        init[q.id] = "";
      });
      setTestAnswers(init);
      if (t) {
        const status = await fetchMyAssignmentTestStatus(assignmentId).catch(() => ({ attempted: false, score: null }));
        setAttemptStatus((prev) => ({ ...prev, [assignmentId]: status }));
      }
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Не удалось загрузить материалы/тест");
    }
  }

  async function onSubmitAssignmentTest(assignmentId: string) {
    setSubmittingTest(true);
    try {
      const res = await submitAssignmentTest(assignmentId, testAnswers);
      setTestModalOpen(false);
      setLastResult(res);
      setResultModalOpen(true);
      setAttemptStatus((prev) => ({ ...prev, [assignmentId]: { attempted: true, score: res.score } }));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Ошибка отправки теста");
    } finally {
      setSubmittingTest(false);
    }
  }

  async function submitWork(assignmentId: string) {
    if (!text.trim() && !answerFile) {
      setError("Добавьте текст или прикрепите файл для отправки.");
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
      setOpenId(null);
      setText("");
      setAnswerFile(null);
      window.location.href = `/student/labs/${res.id}`;
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Не удалось отправить работу");
    } finally {
      setSubmittingWork(false);
    }
  }

  function openTestModal(assignmentId: string) {
    const test = testsByAssignment[assignmentId];
    if (!test) return;
    setActiveAssignmentId(assignmentId);
    setActiveTest(test);
    const init: Record<string, string> = {};
    test.questions.forEach((q) => {
      init[q.id] = "";
    });
    setTestAnswers(init);
    setTestModalOpen(true);
  }

  const allQuestionsAnswered = useMemo(() => {
    if (!activeTest) return false;
    return activeTest.questions.every((q) => Boolean(testAnswers[q.id]));
  }, [activeTest, testAnswers]);

  if (!courseId) {
    return null;
  }

  return (
    <div>
      <p className="muted">
        <Link to="/student/groups">← К группам</Link>
      </p>
      <div style={{ display: "flex", alignItems: "center", gap: "0.75rem", marginBottom: "0.65rem" }}>
        <GroupAvatar title={course?.title ?? "Группа"} avatarFileKey={course?.avatarFileKey} size={52} />
        <h1 className="page-title">{course?.title ?? "Группа"}</h1>
      </div>
      {error && <div className="flash error">{error}</div>}
      <div className="group-tab-row">
        <button type="button" className={`btn secondary ${activeTab === "posts" ? "active" : ""}`} onClick={() => setActiveTab("posts")}>
          Новости
        </button>
        <button type="button" className={`btn secondary ${activeTab === "materials" ? "active" : ""}`} onClick={() => setActiveTab("materials")}>
          Материалы
        </button>
        <button type="button" className={`btn secondary ${activeTab === "assignments" ? "active" : ""}`} onClick={() => setActiveTab("assignments")}>
          Задания
        </button>
      </div>

      {activeTab === "posts" && <div className="card">
        <h3>Новости группы</h3>
        {posts.length === 0 && <p className="muted">Новостей пока нет.</p>}
        {posts.map((p) => (
          <div key={p.id} className="card" style={{ marginTop: "0.65rem" }}>
            <strong>{p.title || "Объявление"}</strong>
            <p>{p.body}</p>
            <p className="muted">
              {p.authorName} · {new Date(p.createdAt).toLocaleString()}
            </p>
          </div>
        ))}
      </div>}
      {activeTab === "materials" && <div className="card">
        <h3>Материалы группы</h3>
        {groupMaterials.map((m) => (
          <div key={m.id} className="row" style={{ marginBottom: "0.35rem" }}>
            <span>{m.title || m.originalName}</span>
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
      </div>}
      {activeTab === "assignments" && <div className="card">
        <h3>Задания</h3>
        <table className="table">
          <thead>
            <tr>
              <th>Тема</th>
              <th>Тип</th>
              <th>Дедлайн</th>
              <th>Предмет</th>
              <th>Макс. балл</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {assignments.map((a) => (
              <tr key={a.id}>
                <td>{a.topicTitle}</td>
                <td>
                  <span className={`pill ${testsByAssignment[a.id] ? "ok" : ""}`}>
                    {testsByAssignment[a.id] ? "Тест" : "Обычное задание"}
                  </span>
                </td>
                <td>{new Date(a.dueDate).toLocaleString()}</td>
                <td>{a.subjectArea ?? "—"}</td>
                <td>{a.maxScore}</td>
                <td>
                  <Link to={`/student/assignments/${a.id}`} className="btn secondary">
                    {testsByAssignment[a.id] && attemptStatus[a.id]?.attempted ? "Сдано" : "Открыть"}
                  </Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>}
      {openId && (
        <div className="card">
          {materials.length > 0 && (
            <div className="card" style={{ marginBottom: "0.75rem" }}>
              <h3>Материалы задания</h3>
              {materials.map((m) => (
                <div key={m.id} className="row" style={{ marginBottom: "0.35rem" }}>
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
          {testsByAssignment[openId] ? (
            <div className="card" style={{ marginTop: "0.75rem" }}>
              <h3>Тест к заданию</h3>
              <p className="muted">
                {attemptStatus[openId]?.attempted
                  ? `Сдано · результат: ${Number(attemptStatus[openId]?.score ?? 0).toFixed(1)}`
                  : "Одна попытка на тест."}
              </p>
              <button
                type="button"
                className="btn secondary"
                disabled={Boolean(attemptStatus[openId]?.attempted)}
                onClick={() => openTestModal(openId)}
              >
                {attemptStatus[openId]?.attempted ? "Сдано" : "Открыть тест"}
              </button>
            </div>
          ) : (
            <div className="card" style={{ marginTop: "0.75rem" }}>
              <h3>Сдача работы</h3>
              <div className="field">
                <label>Комментарий к работе (опционально)</label>
                <textarea
                  value={text}
                  onChange={(e) => setText(e.target.value)}
                  placeholder="Коротко опишите, что вы сдаете"
                />
              </div>
              <div className="field">
                <label>Файл ответа</label>
                <input type="file" onChange={(e) => setAnswerFile(e.target.files?.[0] ?? null)} />
              </div>
              <button type="button" className="btn" disabled={submittingWork} onClick={() => void submitWork(openId)}>
                {submittingWork ? "Отправка…" : "Отправить работу"}
              </button>
            </div>
          )}
        </div>
      )}
      <Modal
        open={testModalOpen && Boolean(activeTest) && Boolean(activeAssignmentId)}
        title={activeTest?.title ?? "Тест"}
        size="xlarge"
        onClose={() => {
          if (!submittingTest) setTestModalOpen(false);
        }}
        footer={
          <div className="modal-actions">
            <button type="button" className="btn secondary" disabled={submittingTest} onClick={() => setTestModalOpen(false)}>
              Закрыть
            </button>
            <button
              type="button"
              className="btn glow-btn"
              disabled={!activeAssignmentId || submittingTest || !allQuestionsAnswered}
              onClick={() => activeAssignmentId && void onSubmitAssignmentTest(activeAssignmentId)}
            >
              {submittingTest ? "Отправка…" : "Отправить тест"}
            </button>
          </div>
        }
      >
        <div className="student-test-modal">
          <p className="student-test-modal-note">Выберите один вариант ответа на каждый вопрос. Попытка только одна.</p>
          {activeTest?.questions.map((q, index) => (
          <div key={q.id} className="field student-test-question">
            <label>
              {index + 1}. {q.questionText}
            </label>
            <div className="test-options-grid">
              {q.options.slice(0, 4).map((opt) => (
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
      </Modal>
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
            <p className="muted">Просмотр правильных/неправильных вариантов недоступен.</p>
          </>
        )}
      </Modal>
    </div>
  );
}
