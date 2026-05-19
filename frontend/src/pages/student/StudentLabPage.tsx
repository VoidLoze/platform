import { useEffect, useRef, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import {
  ApiError,
  fetchAiCheckJob,
  fetchLatestLabAiReview,
  fetchLabDetail,
  reviewLabWorkWithAi,
  setManualGrade,
  submitTest,
} from "../../api";
import { FileAttachmentView } from "../../components/FileAttachmentView";
import { LabAiReviewModal } from "../../components/LabAiReviewModal";
import { TeacherLabExecutedTests } from "../../components/TeacherLabExecutedTests";
import { useAuth } from "../../context/AuthContext";
import type { AiCheckJob, LabWorkDetail } from "../../types";

function TextSubmissionPreview({ text, title = "Текстовая отправка" }: { text: string; title?: string }) {
  const [open, setOpen] = useState(false);
  const lines = text.split("\n").length;
  const long = text.length > 400 || lines > 12;
  return (
    <div className="card">
      <div className="row" style={{ justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: "0.35rem" }}>
        <h3 style={{ margin: 0 }}>{title}</h3>
        {long && (
          <button type="button" className="btn secondary" onClick={() => setOpen((v) => !v)}>
            {open ? "Скрыть текст" : "Показать текст"}
          </button>
        )}
      </div>
      {!long || open ? (
        <pre
          style={{
            whiteSpace: "pre-wrap",
            margin: "0.5rem 0 0",
            fontSize: "0.85rem",
            maxHeight: long ? "12rem" : undefined,
            overflow: long ? "auto" : undefined,
            maxWidth: "100%",
          }}
        >
          {text}
        </pre>
      ) : (
        <p className="muted" style={{ margin: "0.5rem 0 0", fontSize: "0.85rem" }}>
          Текстовый ответ ({lines} строк) — нажмите «Показать текст».
        </p>
      )}
    </div>
  );
}

function TeacherSubmissionBlock({
  lab,
  submissionRef,
}: {
  lab: LabWorkDetail;
  submissionRef: { current: HTMLDivElement | null };
}) {
  const hasText = Boolean(lab.textContent?.trim());
  const hasFile = Boolean(lab.attachmentKey);
  if (!hasText && !hasFile) {
    return (
      <div ref={submissionRef} id="lab-submission" className="card">
        <h3>Сдача студента</h3>
        <p className="muted" style={{ margin: 0 }}>
          Нет текста и файла.
        </p>
      </div>
    );
  }
  return (
    <div ref={submissionRef} id="lab-submission">
      {hasText && <TextSubmissionPreview text={lab.textContent ?? ""} />}
      {hasFile && lab.attachmentKey && <FileAttachmentView fileKey={lab.attachmentKey} title="Файл студента" />}
    </div>
  );
}

export function StudentLabPage() {
  const { user } = useAuth();
  const submissionRef = useRef<HTMLDivElement>(null);
  const { labId } = useParams<{ labId: string }>();
  const [searchParams, setSearchParams] = useSearchParams();
  const [lab, setLab] = useState<LabWorkDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [msg, setMsg] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [extJob, setExtJob] = useState<AiCheckJob | null>(null);
  const [extPollId, setExtPollId] = useState<string | null>(null);
  const [gradeDraft, setGradeDraft] = useState("");
  const [reviewModalOpen, setReviewModalOpen] = useState(false);
  const openedReviewOnceRef = useRef(false);
  const canEditTest = user?.role === "ROLE_STUDENT";
  const isTeacher = user?.role === "ROLE_TEACHER";

  useEffect(() => {
    if (!labId) {
      return;
    }
    void fetchLabDetail(labId)
      .then((d) => {
        setLab(d);
        setGradeDraft(d.finalGradeValue == null ? "" : String(d.finalGradeValue));
        const init: Record<string, string> = {};
        d.generatedTest?.questions.forEach((q) => {
          init[q.id] = "";
        });
        setAnswers(init);
      })
      .catch((e) => setError(e instanceof ApiError ? e.message : "Ошибка загрузки"));
  }, [labId]);

  useEffect(() => {
    if (!labId || user?.role !== "ROLE_TEACHER") return;
    void fetchLatestLabAiReview(labId)
      .then((j) => {
        if (j) setExtJob(j);
      })
      .catch(() => {
        /* нет предыдущей проверки */
      });
  }, [labId, user?.role]);

  useEffect(() => {
    if (searchParams.get("review") === "1" && !openedReviewOnceRef.current) {
      openedReviewOnceRef.current = true;
      setReviewModalOpen(true);
      const next = new URLSearchParams(searchParams);
      next.delete("review");
      setSearchParams(next, { replace: true });
    }
  }, [searchParams, setSearchParams]);

  useEffect(() => {
    if (!reviewModalOpen) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setReviewModalOpen(false);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [reviewModalOpen]);

  useEffect(() => {
    if (!extPollId || !labId) return;
    let cancelled = false;
    async function pollLoop() {
      while (!cancelled) {
        try {
          const j = await fetchAiCheckJob(extPollId);
          if (cancelled) return;
          setExtJob(j);
          if (j.status !== "QUEUED" && j.status !== "RUNNING") {
            const d = await fetchLabDetail(labId);
            if (!cancelled) {
              setLab(d);
              setGradeDraft(d.finalGradeValue == null ? "" : String(d.finalGradeValue));
              if (j.status === "DONE") {
                setMsg("ИИ-проверка завершена.");
                if (!openedReviewOnceRef.current) {
                  openedReviewOnceRef.current = true;
                  setReviewModalOpen(true);
                }
              } else if (j.status === "FAILED") {
                setError(j.errorMessage ?? "ИИ-проверка завершилась с ошибкой");
              }
            }
            break;
          }
        } catch {
          break;
        }
        await new Promise((r) => setTimeout(r, 2500));
      }
    }
    void pollLoop();
    return () => {
      cancelled = true;
    };
  }, [extPollId, labId]);

  async function onSubmitTest() {
    if (!labId) {
      return;
    }
    setBusy(true);
    setError(null);
    setMsg(null);
    try {
      const res = (await submitTest(labId, answers)) as { finalCombinedScore?: number };
      setMsg(`Тест отправлен. Итог: ${JSON.stringify(res)}`);
      const d = await fetchLabDetail(labId);
      setLab(d);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Ошибка отправки теста");
    } finally {
      setBusy(false);
    }
  }

  async function onTeacherFullAiReview() {
    if (!labId || !lab) return;
    if (!lab.textContent?.trim() && !lab.attachmentKey) {
      setError("Студент не приложил ни текст, ни файл — проверять нечего.");
      return;
    }
    setBusy(true);
    setError(null);
    setMsg(null);
    try {
      const job = await reviewLabWorkWithAi(labId);
      setExtJob(job);
      setExtPollId(job.id);
      const parts: string[] = [];
      if (lab.textContent?.trim()) parts.push("текст");
      if (lab.attachmentKey) parts.push("файл");
      setMsg(`ИИ-проверка запущена (${parts.join(" и ")}). Статус обновляется ниже.`);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Не удалось запустить ИИ-проверку");
    } finally {
      setBusy(false);
    }
  }

  async function onSaveGrade() {
    if (!labId) return;
    const value = Number(gradeDraft);
    if (!Number.isFinite(value)) {
      setError("Укажите числовую оценку.");
      return;
    }
    setBusy(true);
    setError(null);
    setMsg(null);
    try {
      await setManualGrade(labId, value);
      const updated = await fetchLabDetail(labId);
      setLab(updated);
      setGradeDraft(updated.finalGradeValue == null ? "" : String(updated.finalGradeValue));
      setMsg("Оценка сохранена.");
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Не удалось сохранить оценку");
    } finally {
      setBusy(false);
    }
  }

  if (!labId) {
    return null;
  }

  const primaryExt = extJob?.results?.find((r) => r.primary) ?? extJob?.results?.[0];

  function scrollToSubmission() {
    submissionRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  return (
    <div>
      <p className="muted">
        {isTeacher ? (
          <>
            {lab?.courseId && (
              <>
                <Link to={`/teacher/groups/${lab.courseId}/gradebook`}>← Журнал оценок</Link>
                {" · "}
              </>
            )}
            <Link to={`/teacher/assignments/${lab?.assignmentId ?? ""}`}>К заданию</Link>
            {" · "}
            <Link to="/teacher">Кабинет</Link>
          </>
        ) : (
          <Link to="/student/labs">← К моим работам</Link>
        )}
      </p>
      <h1 className="page-title">{lab?.assignmentTitle ?? "Работа"}</h1>
      <p className="muted">
        Группа: {lab?.courseTitle} · Статус: <span className="pill">{lab?.status}</span>
        {isTeacher && lab?.studentDisplayName && (
          <>
            {" "}
            · Студент: <strong>{lab.studentDisplayName}</strong>
          </>
        )}
      </p>
      {error && <div className="flash error">{error}</div>}
      {msg && <div className="flash ok">{msg}</div>}

      {isTeacher && lab?.assignmentDescription && (
        <div className="card">
          <h3>Условие задания</h3>
          <p style={{ whiteSpace: "pre-wrap", margin: 0 }}>{lab.assignmentDescription}</p>
        </div>
      )}

      {isTeacher && lab && <TeacherSubmissionBlock lab={lab} submissionRef={submissionRef} />}

      {isTeacher && lab && (
        <div className="card premium-card" style={{ marginBottom: "1rem" }}>
          <h3>Оценка задания</h3>
          {lab.status === "COMPLETED" && lab.finalGradeValue != null ? (
            <p style={{ margin: 0 }}>
              Итоговая оценка: <strong>{lab.finalGradeValue}</strong>
              {lab.finalGradeLetter ? ` (${lab.finalGradeLetter})` : null}
              <span className="muted"> — изменить нельзя, работу можно просмотреть ниже.</span>
            </p>
          ) : (
            <>
              <p className="muted" style={{ marginTop: 0 }}>
                Итоговый балл (макс. {lab.assignmentMaxScore ?? "—"}). После сохранения оценку изменить нельзя.
              </p>
              <div className="row" style={{ alignItems: "flex-end", flexWrap: "wrap", gap: "0.5rem" }}>
                <div className="field" style={{ marginBottom: 0, minWidth: 140 }}>
                  <label htmlFor="teacher-grade">Балл</label>
                  <input
                    id="teacher-grade"
                    type="number"
                    min={0}
                    max={lab.assignmentMaxScore ?? undefined}
                    step={0.1}
                    value={gradeDraft}
                    onChange={(e) => setGradeDraft(e.target.value)}
                    placeholder={`0–${lab.assignmentMaxScore ?? ""}`}
                  />
                </div>
                <button type="button" className="btn" disabled={busy} onClick={() => void onSaveGrade()}>
                  {busy ? "Сохранение…" : "Выставить оценку"}
                </button>
              </div>
            </>
          )}
        </div>
      )}

      {isTeacher && lab && (
        <div className="card" style={{ marginBottom: "1rem" }}>
          <h3>ИИ-проверка всей работы</h3>
          <p className="muted" style={{ marginTop: 0 }}>
            Проверяется всё, что сдал студент: текст, файл или оба. Для кода — полный конвейер с запуском тестов.
          </p>
          <div className="row" style={{ flexWrap: "wrap", gap: "0.5rem" }}>
            <button
              type="button"
              className="btn"
              disabled={busy || (!lab.textContent?.trim() && !lab.attachmentKey)}
              onClick={() => void onTeacherFullAiReview()}
            >
              {busy ? "Запуск…" : "Запустить ИИ-проверку"}
            </button>
            <button
              type="button"
              className="btn secondary"
              disabled={!lab.textContent?.trim() && !lab.attachmentKey}
              onClick={scrollToSubmission}
            >
              Просмотр работы
            </button>
          </div>
          {extJob && (
            <div style={{ marginTop: "0.75rem" }}>
              <p className="muted" style={{ margin: 0 }}>
                Проверка: <span className="pill">{extJob.status}</span>
                {extJob.status === "RUNNING" || extJob.status === "QUEUED" ? " — подождите…" : null}
              </p>
              {primaryExt && extJob.status === "DONE" && (
                <p style={{ marginTop: "0.5rem" }}>
                  Итог: <strong>{primaryExt.score}</strong> / {primaryExt.maxScore} — {primaryExt.summary}
                </p>
              )}
              <TeacherLabExecutedTests job={extJob} />
            </div>
          )}
        </div>
      )}

      {!isTeacher && lab?.textContent && <TextSubmissionPreview text={lab.textContent} title="Ваша отправка" />}

      {!isTeacher && lab?.attachmentKey && <FileAttachmentView fileKey={lab.attachmentKey} title="Прикреплённый файл" />}

      {(lab?.aiReview || (extJob?.status === "DONE" && primaryExt)) && (
        <div className="card">
          <h3>ИИ-проверка</h3>
          <p style={{ marginTop: 0 }}>
            Балл:{" "}
            <strong>{primaryExt?.score ?? lab?.aiReview?.scoreValue}</strong> /{" "}
            {primaryExt?.maxScore ?? lab?.aiReview?.maxScore}
          </p>
          {(primaryExt?.summary ?? lab?.aiReview?.summary) && (
            <p className="muted">{primaryExt?.summary ?? lab?.aiReview?.summary}</p>
          )}
          <button type="button" className="btn secondary" onClick={() => setReviewModalOpen(true)}>
            Открыть полный отчёт
          </button>
        </div>
      )}

      <LabAiReviewModal
        open={reviewModalOpen}
        onClose={() => setReviewModalOpen(false)}
        aiReview={lab?.aiReview ?? null}
        extJob={extJob}
        primaryExt={primaryExt}
      />

      {!isTeacher && lab?.generatedTest && lab.generatedTest.questions.length > 0 && (
        <div className="card">
          <h3>Дополнительные вопросы</h3>
          <p className="muted">
            {canEditTest
              ? "Ответьте на вопросы. Эталонные ответы скрыты до проверки преподавателем."
              : "Режим просмотра: редактирование ответов отключено."}
          </p>
          {lab.generatedTest.questions.map((q) => (
            <div key={q.id} className="field">
              <label>
                {q.text} {q.difficulty ? <span className="pill">{q.difficulty}</span> : null}
              </label>
              <input
                value={answers[q.id] ?? ""}
                onChange={(e) => setAnswers((prev) => ({ ...prev, [q.id]: e.target.value }))}
                placeholder="Ваш ответ"
                disabled={!canEditTest}
              />
            </div>
          ))}
          <button type="button" className="btn" disabled={!canEditTest || busy || lab.status === "COMPLETED"} onClick={() => void onSubmitTest()}>
            {busy ? "Отправка…" : "Отправить ответы"}
          </button>
        </div>
      )}

    </div>
  );
}
