import { Modal } from "./Modal";
import { TeacherLabExecutedTests } from "./TeacherLabExecutedTests";
import type { AIReview, AiCheckJob, AiCheckResult } from "../types";

type Props = {
  open: boolean;
  onClose: () => void;
  aiReview: AIReview | null;
  extJob: AiCheckJob | null;
  primaryExt: AiCheckResult | null | undefined;
};

export function LabAiReviewModal({ open, onClose, aiReview, extJob, primaryExt }: Props) {
  const score = primaryExt?.score ?? aiReview?.scoreValue;
  const maxScore = primaryExt?.maxScore ?? aiReview?.maxScore;
  const summary = primaryExt?.summary ?? aiReview?.summary;
  const detailed = primaryExt?.detailedFeedback ?? aiReview?.detailedFeedback;
  const recommendations =
    primaryExt?.recommendations?.length
      ? primaryExt.recommendations
          .map((r) => `${r.title}: ${r.description}${r.url ? ` (${r.url})` : ""}`)
          .join("\n")
      : aiReview?.recommendations;

  if (!open || (!aiReview && !primaryExt)) {
    return null;
  }

  return (
    <Modal
      open={open}
      title="ИИ-проверка"
      onClose={onClose}
      size="wide"
      footer={
        <div className="modal-actions">
          <button type="button" className="btn" onClick={onClose}>
            Закрыть
          </button>
        </div>
      }
    >
      {score != null && maxScore != null && (
        <p style={{ marginTop: 0 }}>
          Балл: <strong>{score}</strong> / {maxScore}
        </p>
      )}
      {summary && <p className="muted">{summary}</p>}
      {detailed && <p style={{ whiteSpace: "pre-wrap" }}>{detailed}</p>}
      {recommendations && (
        <p className="muted" style={{ whiteSpace: "pre-wrap", marginBottom: extJob ? "1rem" : 0 }}>
          {recommendations}
        </p>
      )}
      {extJob && <TeacherLabExecutedTests job={extJob} />}
    </Modal>
  );
}
