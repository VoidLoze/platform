import type { ReactNode } from "react";

export function Modal({
  open,
  title,
  children,
  onClose,
  footer,
  wide,
  size,
}: {
  open: boolean;
  title: string;
  children: ReactNode;
  onClose: () => void;
  footer?: ReactNode;
  wide?: boolean;
  /** Широкое модальное окно для поиска пользователей и подобных сценариев */
  size?: "default" | "wide" | "xlarge";
}) {
  if (!open) {
    return null;
  }
  const resolvedSize = size ?? (wide ? "wide" : "default");
  const panelClass = [
    "modal-panel",
    resolvedSize === "wide" && "modal-panel--wide",
    resolvedSize === "xlarge" && "modal-panel--xlarge",
  ]
    .filter(Boolean)
    .join(" ");
  return (
    <div className="modal-root" role="dialog" aria-modal="true" aria-labelledby="modal-title">
      <button type="button" className="modal-backdrop" aria-label="Закрыть" onClick={onClose} />
      <div className={panelClass}>
        <div className="modal-head">
          <h2 id="modal-title">{title}</h2>
          <button type="button" className="modal-close" onClick={onClose}>
            ×
          </button>
        </div>
        <div className="modal-body">{children}</div>
        {footer != null && <div className="modal-footer">{footer}</div>}
      </div>
    </div>
  );
}
