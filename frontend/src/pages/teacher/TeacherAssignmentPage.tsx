import { useEffect, useState, type Dispatch, type FormEvent, type SetStateAction, type TextareaHTMLAttributes } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { Modal } from "../../components/Modal";
import {
  ApiError,
  createAssignment,
  deleteLabWork,
  fetchAssignment,
  fetchAssignmentTest,
  fetchSubmissions,
  generateAssignmentTestPreview,
  generateAssignmentTestPreviewFromDraft,
  reviewLabWorkWithAi,
  upsertAssignmentTest,
} from "../../api";
import type { Assignment, AssignmentSubmissionRosterRow, AssignmentTestQuestion } from "../../types";

type AssignmentDraftPayload = {
  courseId: string;
  topicTitle: string;
  topicDescription: string | null;
  subjectArea: string | null;
  dueDate: string;
  allowLateSubmission: boolean;
  latePenaltyPercent: number;
  maxScore: number;
  title?: string;
  questionCount?: number;
  questions?: AssignmentTestQuestion[];
};

export function TeacherAssignmentPage() {
  const navigate = useNavigate();
  const { assignmentId } = useParams<{ assignmentId: string }>();
  const [searchParams] = useSearchParams();
  const isDraftMode = assignmentId === "new";
  const [assignment, setAssignment] = useState<Assignment | null>(null);
  const [assignmentDraft, setAssignmentDraft] = useState<AssignmentDraftPayload | null>(null);
  const [rows, setRows] = useState<AssignmentSubmissionRosterRow[]>([]);
  const [testTitle, setTestTitle] = useState("Контрольный тест");
  const [testMaxScore, setTestMaxScore] = useState(100);
  const [questions, setQuestions] = useState<AssignmentTestQuestion[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [savingTest, setSavingTest] = useState(false);

  const [testModalOpen, setTestModalOpen] = useState(false);
  const [genCount, setGenCount] = useState(5);
  const [genNotes, setGenNotes] = useState("");
  const [modalQuestions, setModalQuestions] = useState<AssignmentTestQuestion[]>([]);
  const [modalTitle, setModalTitle] = useState("Сгенерированный тест");
  const [modalMaxScore, setModalMaxScore] = useState(100);
  const [generating, setGenerating] = useState(false);
  const [draftLoaded, setDraftLoaded] = useState(false);
  const [lockedGenCount, setLockedGenCount] = useState<number | null>(null);

  function navigateToGroupAfterSave() {
    const targetCourseId = assignment?.courseId ?? assignmentDraft?.courseId;
    if (targetCourseId) {
      navigate(`/teacher/groups/${targetCourseId}`);
      return;
    }
    navigate("/teacher");
  }

  useEffect(() => {
    if (!assignmentId) {
      return;
    }
    const queryCountRaw = searchParams.get("questionCount");
    const queryCount =
      queryCountRaw != null && Number.isFinite(Number(queryCountRaw))
        ? Math.max(1, Math.min(50, Math.trunc(Number(queryCountRaw))))
        : null;

    if (isDraftMode) {
      const rawDraft = sessionStorage.getItem("assignment-test-draft:new");
      if (!rawDraft) {
        setError("Черновик не найден. Вернитесь в группу и подготовьте тест заново.");
        return;
      }
      try {
        const parsed = JSON.parse(rawDraft) as AssignmentDraftPayload;
        setAssignmentDraft(parsed);
        if (typeof parsed.title === "string") {
          setTestTitle(parsed.title);
        }
        if (typeof parsed.maxScore === "number" && Number.isFinite(parsed.maxScore)) {
          setTestMaxScore(parsed.maxScore);
          setModalMaxScore(parsed.maxScore);
        }
        if (Array.isArray(parsed.questions)) {
          setQuestions(parsed.questions);
        }
        const countCandidate = typeof parsed.questionCount === "number" && Number.isFinite(parsed.questionCount) ? parsed.questionCount : queryCount;
        if (countCandidate != null) {
          const count = Math.max(1, Math.min(50, Math.trunc(countCandidate)));
          setGenCount(count);
          setLockedGenCount(count);
        }
        setDraftLoaded(true);
      } catch {
        setError("Не удалось прочитать черновик теста.");
      }
      return;
    }

    void Promise.all([
      fetchAssignment(assignmentId),
      fetchSubmissions(assignmentId),
      fetchAssignmentTest(assignmentId).catch(() => null),
    ])
      .then(([a, s, t]) => {
        setAssignment(a);
        setRows(s);
        if (t) {
          setTestTitle(t.title);
          setTestMaxScore(t.maxScore);
          setQuestions(t.questions);
        } else {
          const rawDraft = sessionStorage.getItem(`assignment-test-draft:${assignmentId}`);
          if (rawDraft) {
            try {
              const parsed = JSON.parse(rawDraft) as {
                title?: string;
                maxScore?: number;
                questionCount?: number;
                questions?: AssignmentTestQuestion[];
              };
              if (typeof parsed.title === "string") {
                setTestTitle(parsed.title);
              }
              if (typeof parsed.maxScore === "number" && Number.isFinite(parsed.maxScore)) {
                setTestMaxScore(parsed.maxScore);
              }
              if (Array.isArray(parsed.questions)) {
                setQuestions(parsed.questions);
              }
              if (typeof parsed.questionCount === "number" && Number.isFinite(parsed.questionCount)) {
                const count = Math.max(1, Math.min(50, Math.trunc(parsed.questionCount)));
                setGenCount(count);
                setLockedGenCount(count);
              } else if (queryCount != null) {
                setGenCount(queryCount);
                setLockedGenCount(queryCount);
              }
              setDraftLoaded(true);
            } catch {
              // ignore broken draft payload
            } finally {
              sessionStorage.removeItem(`assignment-test-draft:${assignmentId}`);
            }
          } else if (queryCount != null) {
            setGenCount(queryCount);
            setLockedGenCount(queryCount);
          }
        }
      })
      .catch((e) => setError(e instanceof ApiError ? e.message : "Ошибка загрузки"));
  }, [assignmentId, isDraftMode, searchParams]);

  async function runReview(labId: string | null) {
    if (!labId) return;
    setBusyId(labId);
    setError(null);
    try {
      await reviewLabWorkWithAi(labId);
      if (assignmentId) {
        const s = await fetchSubmissions(assignmentId);
        setRows(s);
      }
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Ошибка ИИ-проверки");
    } finally {
      setBusyId(null);
    }
  }

  async function onDeleteLabWork(labId: string | null) {
    if (!labId || !assignmentId) return;
    if (!window.confirm("Удалить эту работу из очереди? Это действие необратимо.")) return;
    setBusyId(labId);
    setError(null);
    try {
      await deleteLabWork(labId);
      setRows(await fetchSubmissions(assignmentId));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Не удалось удалить работу");
    } finally {
      setBusyId(null);
    }
  }

  async function onSaveTest() {
    if (!assignmentId) return;
    setSavingTest(true);
    setError(null);
    setSuccess(null);
    try {
      const normalizedQuestions = normalizeQuestionsForSave(questions);
      if (normalizedQuestions.length === 0) {
        setError("Добавьте хотя бы один корректный вопрос перед сохранением.");
        return;
      }
      const targetAssignmentId = await ensureAssignmentForSave();
      await upsertAssignmentTest(targetAssignmentId, {
        title: testTitle.trim() || "Контрольный тест",
        maxScore: Math.max(1, testMaxScore),
        questions: normalizedQuestions,
      });
      setQuestions(normalizedQuestions);
      setSuccess("Тест сохранен.");
      sessionStorage.removeItem("assignment-test-draft:new");
      navigateToGroupAfterSave();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Не удалось сохранить тест");
    } finally {
      setSavingTest(false);
    }
  }

  function openTestWizard() {
    setModalTitle(testTitle);
    setModalMaxScore(testMaxScore);
    setModalQuestions([...questions]);
    setGenNotes("");
    setGenCount(lockedGenCount ?? Math.max(1, Math.min(50, questions.length || 5)));
    setTestModalOpen(true);
  }

  async function runGenerateInModal() {
    if (!assignmentId) return;
    setGenerating(true);
    setError(null);
    try {
      const preview = isDraftMode
        ? await generateAssignmentTestPreviewFromDraft({
            topicTitle: assignmentDraft?.topicTitle ?? "",
            topicDescription: assignmentDraft?.topicDescription ?? null,
            subjectArea: assignmentDraft?.subjectArea ?? null,
            questionCount: lockedGenCount ?? genCount,
            notes: genNotes || null,
          })
        : await generateAssignmentTestPreview(assignmentId, {
            questionCount: lockedGenCount ?? genCount,
            notes: genNotes || null,
          });
      setModalTitle(preview.title);
      setModalMaxScore(preview.maxScore);
      setModalQuestions(preview.questions);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Не удалось сгенерировать тест");
    } finally {
      setGenerating(false);
    }
  }

  async function saveFromModal() {
    if (!assignmentId) return;
    setSavingTest(true);
    setError(null);
    setSuccess(null);
    try {
      const normalizedQuestions = normalizeQuestionsForSave(modalQuestions);
      if (normalizedQuestions.length === 0) {
        setError("В модальном редакторе нет корректных вопросов для сохранения.");
        return;
      }
      const targetAssignmentId = await ensureAssignmentForSave();
      await upsertAssignmentTest(targetAssignmentId, {
        title: modalTitle.trim() || "Контрольный тест",
        maxScore: Math.max(1, modalMaxScore),
        questions: normalizedQuestions,
      });
      setTestTitle(modalTitle);
      setTestMaxScore(modalMaxScore);
      setQuestions(normalizedQuestions);
      setTestModalOpen(false);
      setSuccess("Тест сохранен.");
      sessionStorage.removeItem("assignment-test-draft:new");
      navigateToGroupAfterSave();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Не удалось сохранить тест");
    } finally {
      setSavingTest(false);
    }
  }

  function applyToEditorOnly() {
    setTestTitle(modalTitle);
    setTestMaxScore(modalMaxScore);
    setQuestions(modalQuestions);
    setTestModalOpen(false);
  }

  function updateQuestionOption(
    setter: Dispatch<SetStateAction<AssignmentTestQuestion[]>>,
    questionIndex: number,
    optionIndex: number,
    value: string,
  ) {
    setter((prev) =>
      prev.map((q, i) => {
        if (i !== questionIndex) return q;
        const nextOptions = [...(q.options ?? [])];
        nextOptions[optionIndex] = value;
        return { ...q, options: nextOptions };
      }),
    );
  }

  function addQuestionOption(setter: Dispatch<SetStateAction<AssignmentTestQuestion[]>>, questionIndex: number) {
    setter((prev) => prev.map((q, i) => (i === questionIndex ? { ...q, options: [...(q.options ?? []), ""] } : q)));
  }

  function removeQuestionOption(
    setter: Dispatch<SetStateAction<AssignmentTestQuestion[]>>,
    questionIndex: number,
    optionIndex: number,
  ) {
    setter((prev) =>
      prev.map((q, i) => (i === questionIndex ? { ...q, options: (q.options ?? []).filter((_, idx) => idx !== optionIndex) } : q)),
    );
  }

  function removeQuestion(setter: Dispatch<SetStateAction<AssignmentTestQuestion[]>>, questionIndex: number) {
    setter((prev) => prev.filter((_, idx) => idx !== questionIndex));
  }

  function normalizeQuestionsForSave(input: AssignmentTestQuestion[]): AssignmentTestQuestion[] {
    return input
      .map((q) => {
        const options = (q.options ?? []).map((opt) => opt.trim()).filter(Boolean);
        const correct = (q.correctAnswer ?? "").trim();
        return {
          ...q,
          id: q.id || crypto.randomUUID(),
          questionText: (q.questionText ?? "").trim(),
          questionType: "single_choice",
          options,
          correctAnswer: correct,
          points: Number.isFinite(q.points) && q.points > 0 ? q.points : 1,
        };
      })
      .filter((q) => q.questionText && q.options.length > 0 && q.correctAnswer);
  }

  function autoGrow(e: FormEvent<HTMLTextAreaElement>) {
    const el = e.currentTarget;
    el.style.height = "0px";
    el.style.height = `${Math.min(220, Math.max(38, el.scrollHeight))}px`;
  }

  const compactAreaProps: TextareaHTMLAttributes<HTMLTextAreaElement> = {
    rows: 1,
    onInput: autoGrow,
    style: { minHeight: "2.2rem", maxHeight: "220px" },
  };

  if (!assignmentId) {
    return null;
  }

  async function ensureAssignmentForSave(): Promise<string> {
    if (!isDraftMode) {
      return assignmentId;
    }
    if (!assignmentDraft) {
      throw new Error("Черновик задания не найден");
    }
    const created = await createAssignment(assignmentDraft.courseId, {
      topicTitle: assignmentDraft.topicTitle,
      topicDescription: assignmentDraft.topicDescription ?? undefined,
      subjectArea: assignmentDraft.subjectArea ?? undefined,
      dueDate: assignmentDraft.dueDate,
      allowLateSubmission: assignmentDraft.allowLateSubmission,
      latePenaltyPercent: assignmentDraft.latePenaltyPercent,
      maxScore: assignmentDraft.maxScore,
    });
    return created.id;
  }

  return (
    <div className="page-hero">
      <div className="hero-inner">
        {assignment && (
          <p className="muted">
            <Link to={`/teacher/groups/${assignment.courseId}`}>← К группе</Link>
          </p>
        )}
        {isDraftMode && assignmentDraft && (
          <p className="muted">
            <Link to={`/teacher/groups/${assignmentDraft.courseId}`}>← К группе</Link>
          </p>
        )}
        <p className="hero-kicker">Задание</p>
        <h1 className="hero-title">{assignment?.topicTitle ?? assignmentDraft?.topicTitle ?? "Загрузка…"}</h1>
      </div>

      {error && <div className="flash error">{error}</div>}
      {success && <div className="flash ok">{success}</div>}
      {draftLoaded && <div className="flash ok">Черновик теста загружен. Проверьте вопросы и нажмите «Сохранить тест» для публикации.</div>}
      {isDraftMode && <div className="flash ok">Задание будет создано только после нажатия «Сохранить тест».</div>}

      <div className="card premium-card">
        <div className="row" style={{ justifyContent: "space-between", alignItems: "center" }}>
          <h3 style={{ margin: 0 }}>Тест к заданию</h3>
          <button type="button" className="btn glow-btn" onClick={() => openTestWizard()}>
            Мастер теста и ИИ
          </button>
        </div>
        <div className="field">
          <label>Название теста</label>
          <input value={testTitle} onChange={(e) => setTestTitle(e.target.value)} placeholder="Например: Итоговый тест по теме" />
        </div>
        <div className="field">
          <label>Макс. балл теста</label>
          <input type="number" value={testMaxScore} onChange={(e) => setTestMaxScore(Number(e.target.value))} min={1} />
        </div>
        {questions.map((q, idx) => (
          <div key={q.id || idx} className="nest-card">
            <div className="field">
              <label>Вопрос</label>
              <input
                value={q.questionText}
                onChange={(e) => setQuestions((prev) => prev.map((x, i) => (i === idx ? { ...x, questionText: e.target.value } : x)))}
                placeholder="Текст вопроса"
              />
            </div>
            <div className="field">
              <label>Варианты ответа</label>
              {(q.options ?? []).map((opt, optIdx) => (
                <div key={`${q.id || idx}-opt-${optIdx}`} className="row test-option-row" style={{ marginBottom: "0.35rem" }}>
                  <textarea
                    {...compactAreaProps}
                    value={opt}
                    onChange={(e) => updateQuestionOption(setQuestions, idx, optIdx, e.target.value)}
                    placeholder={`Вариант ${optIdx + 1}`}
                  />
                  <button type="button" className="btn secondary" onClick={() => removeQuestionOption(setQuestions, idx, optIdx)}>
                    Удалить
                  </button>
                </div>
              ))}
              <button type="button" className="btn secondary" onClick={() => addQuestionOption(setQuestions, idx)}>
                Добавить вариант
              </button>
            </div>
            <div className="field">
              <label>Правильный ответ</label>
              <textarea
                {...compactAreaProps}
                value={q.correctAnswer ?? ""}
                onChange={(e) => setQuestions((prev) => prev.map((x, i) => (i === idx ? { ...x, correctAnswer: e.target.value } : x)))}
                placeholder="Должен совпадать с одним из вариантов"
              />
            </div>
            <div className="row">
              <button type="button" className="btn danger" onClick={() => removeQuestion(setQuestions, idx)}>
                Удалить вопрос
              </button>
            </div>
          </div>
        ))}
        <div className="row" style={{ marginTop: "0.75rem" }}>
          <button
            type="button"
            className="btn secondary"
            onClick={() =>
              setQuestions((prev) => [
                ...prev,
                { id: crypto.randomUUID(), questionText: "", questionType: "single_choice", options: [], correctAnswer: "", points: 1 },
              ])
            }
          >
            Добавить вопрос
          </button>
          <button type="button" className="btn" onClick={() => void onSaveTest()} disabled={savingTest}>
            {savingTest ? "Сохранение..." : "Сохранить тест"}
          </button>
        </div>
      </div>

      <Modal
        open={testModalOpen}
        title="Мастер теста"
        wide
        onClose={() => setTestModalOpen(false)}
        footer={
          <div className="modal-actions">
            <button type="button" className="btn secondary" onClick={() => setTestModalOpen(false)}>
              Закрыть
            </button>
            <button type="button" className="btn secondary" onClick={() => applyToEditorOnly()}>
              Только в форму на странице
            </button>
            <button type="button" className="btn glow-btn" disabled={savingTest || modalQuestions.length === 0} onClick={() => void saveFromModal()}>
              {savingTest ? "Сохранение…" : "Сохранить в задание"}
            </button>
          </div>
        }
      >
        <div className="gen-toolbar">
          <div className="field inline-field">
            <label>Вопросов</label>
            <input type="number" min={1} max={50} value={lockedGenCount ?? genCount} disabled />
          </div>
          <div className="field" style={{ flex: 1 }}>
            <label>Контекст для ИИ (необязательно)</label>
            <input value={genNotes} onChange={(e) => setGenNotes(e.target.value)} placeholder="Например: акцент на исключениях и тестах" />
          </div>
          <button type="button" className="btn glow-btn" disabled={generating} onClick={() => void runGenerateInModal()}>
            {generating ? "Генерация…" : "Сгенерировать"}
          </button>
        </div>

        <div className="field">
          <label>Название теста</label>
          <input value={modalTitle} onChange={(e) => setModalTitle(e.target.value)} placeholder="Название теста" />
        </div>
        <div className="field">
          <label>Макс. балл</label>
          <input type="number" value={modalMaxScore} onChange={(e) => setModalMaxScore(Number(e.target.value))} min={1} />
        </div>

        <div className="modal-scroll">
          {modalQuestions.map((q, idx) => (
            <div key={q.id || idx} className="nest-card">
              <div className="field">
                <label>Вопрос {idx + 1}</label>
                <textarea
                  rows={2}
                  value={q.questionText}
                  onChange={(e) =>
                    setModalQuestions((prev) => prev.map((x, i) => (i === idx ? { ...x, questionText: e.target.value } : x)))
                  }
                  placeholder="Текст вопроса"
                />
              </div>
              <div className="field">
                <label>Варианты ответа</label>
                {(q.options ?? []).map((opt, optIdx) => (
                  <div key={`${q.id || idx}-modal-opt-${optIdx}`} className="row test-option-row" style={{ marginBottom: "0.35rem" }}>
                    <textarea
                      {...compactAreaProps}
                      value={opt}
                      onChange={(e) => updateQuestionOption(setModalQuestions, idx, optIdx, e.target.value)}
                      placeholder={`Вариант ${optIdx + 1}`}
                    />
                    <button type="button" className="btn secondary" onClick={() => removeQuestionOption(setModalQuestions, idx, optIdx)}>
                      Удалить
                    </button>
                  </div>
                ))}
                <button type="button" className="btn secondary" onClick={() => addQuestionOption(setModalQuestions, idx)}>
                  Добавить вариант
                </button>
              </div>
              <div className="field">
                <label>Правильный ответ</label>
                <textarea
                  {...compactAreaProps}
                  value={q.correctAnswer ?? ""}
                  onChange={(e) =>
                    setModalQuestions((prev) => prev.map((x, i) => (i === idx ? { ...x, correctAnswer: e.target.value } : x)))
                  }
                  placeholder="Правильный ответ"
                />
              </div>
              <div className="field">
                <label>Баллы</label>
                <input
                  type="number"
                  step="0.1"
                  value={q.points}
                  onChange={(e) =>
                    setModalQuestions((prev) => prev.map((x, i) => (i === idx ? { ...x, points: Number(e.target.value) } : x)))
                  }
                />
              </div>
              <div className="row">
                <button type="button" className="btn danger" onClick={() => removeQuestion(setModalQuestions, idx)}>
                  Удалить вопрос
                </button>
              </div>
            </div>
          ))}
          {modalQuestions.length === 0 && <p className="muted">Сгенерируйте тест или добавьте вопросы на основной форме.</p>}
        </div>
      </Modal>

      {!isDraftMode &&       <div className="card premium-card">
        <h3>Сдачи по группе</h3>
        <p className="muted" style={{ marginTop: 0 }}>
          Все ученики группы: текстовая работа (последняя отправка) и балл теста по этому заданию, если тест был добавлен.
        </p>
        <table className="table">
          <thead>
            <tr>
              <th>Студент</th>
              <th>Текстовая работа</th>
              <th>Балл работы</th>
              <th>Тест</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.studentId}>
                <td>
                  {r.studentName}
                  <div className="muted" style={{ fontSize: "0.8rem" }}>
                    {r.studentEmail}
                  </div>
                </td>
                <td>
                  {r.labWorkId ? (
                    <>
                      <span className="pill">{r.labStatus}</span>
                      {r.labSubmittedAt && (
                        <div className="muted" style={{ fontSize: "0.78rem" }}>
                          {new Date(r.labSubmittedAt).toLocaleString()}
                        </div>
                      )}
                      {r.labSubmissionCount > 1 && (
                        <div className="muted" style={{ fontSize: "0.78rem" }}>
                          отправок: {r.labSubmissionCount}
                        </div>
                      )}
                    </>
                  ) : (
                    <span className="muted">не сдано</span>
                  )}
                </td>
                <td>{r.labFinalGrade != null ? r.labFinalGrade.toFixed(1) : "—"}</td>
                <td>
                  {!r.assignmentHasTest ? (
                    <span className="muted">нет теста</span>
                  ) : r.testTaken ? (
                    r.testScore != null ? r.testScore.toFixed(1) : "—"
                  ) : (
                    <span className="muted">не проходил</span>
                  )}
                </td>
                <td>
                  {r.labWorkId ? (
                    <>
                      <Link to={`/teacher/labs/${r.labWorkId}`} className="btn secondary">
                        Просмотр
                      </Link>
                      <button
                        type="button"
                        className="btn secondary"
                        disabled={busyId === r.labWorkId}
                        onClick={() => void runReview(r.labWorkId)}
                        style={{ marginLeft: "0.5rem" }}
                      >
                        {busyId === r.labWorkId ? "ИИ…" : "ИИ-проверка"}
                      </button>
                      <button
                        type="button"
                        className="btn danger"
                        disabled={busyId === r.labWorkId}
                        onClick={() => void onDeleteLabWork(r.labWorkId)}
                        style={{ marginLeft: "0.5rem" }}
                      >
                        Удалить
                      </button>
                    </>
                  ) : (
                    <span className="muted">—</span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {rows.length === 0 && <p className="muted">В группе пока нет учеников или не удалось загрузить список.</p>}
      </div>}
    </div>
  );
}
