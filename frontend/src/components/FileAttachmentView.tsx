import { useCallback, useEffect, useState, type CSSProperties } from "react";
import { ApiError, downloadFileByKey, fetchAuthenticatedFileBlob, triggerBlobDownload } from "../api";

type FileKind = "java" | "code" | "archive" | "image" | "document" | "other";

type FileInfo = {
  name: string;
  kind: FileKind;
  label: string;
  canInlinePreview: boolean;
};

function describeFile(fileKey: string): FileInfo {
  const name = fileKey.split("/").pop() ?? fileKey;
  const lower = name.toLowerCase();
  if (/\.(zip|jar|tar\.gz|tgz)$/.test(lower)) {
    return { name, kind: "archive", label: "Архив", canInlinePreview: false };
  }
  if (/\.(png|jpe?g|gif|webp|bmp|svg)$/.test(lower)) {
    return { name, kind: "image", label: "Изображение", canInlinePreview: true };
  }
  if (/\.java$/.test(lower)) {
    return { name, kind: "java", label: "Java", canInlinePreview: true };
  }
  if (/\.(kt|py|js|ts|tsx|jsx|c|cpp|h|hpp|cs|go|rs|scala|rb|php|sql|sh)$/.test(lower)) {
    return { name, kind: "code", label: "Код", canInlinePreview: true };
  }
  if (/\.(txt|md|json|xml|html|css|yml|yaml|properties|gradle)$/.test(lower)) {
    return { name, kind: "document", label: "Текст", canInlinePreview: true };
  }
  if (/\.(pdf|doc|docx)$/.test(lower)) {
    return { name, kind: "document", label: "Документ", canInlinePreview: false };
  }
  return { name, kind: "other", label: "Файл", canInlinePreview: false };
}

const PREVIEW_BOX: CSSProperties = {
  margin: 0,
  fontSize: "0.8rem",
  lineHeight: 1.45,
  maxHeight: "12rem",
  overflow: "auto",
  padding: "0.65rem 0.75rem",
  background: "var(--surface2, #1c3152)",
  borderRadius: 8,
  border: "1px solid rgba(255, 255, 255, 0.08)",
  whiteSpace: "pre",
  maxWidth: "100%",
};

type Props = {
  fileKey: string;
  title?: string;
};

export function FileAttachmentView({ fileKey, title = "Прикреплённый файл" }: Props) {
  const info = describeFile(fileKey);
  const [textPreview, setTextPreview] = useState<string | null>(null);
  const [imageUrl, setImageUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [downloading, setDownloading] = useState(false);
  const [previewOpen, setPreviewOpen] = useState(false);

  useEffect(() => {
    setPreviewOpen(false);
    setTextPreview(null);
    setImageUrl(null);
    setError(null);
    setLoading(false);
  }, [fileKey]);

  useEffect(() => {
    if (!previewOpen || !info.canInlinePreview) return;

    let revoked: string | null = null;
    let cancelled = false;
    setLoading(true);
    setError(null);

    void (async () => {
      try {
        const blob = await fetchAuthenticatedFileBlob(fileKey);
        if (cancelled) return;
        if (info.kind === "image") {
          const url = URL.createObjectURL(blob);
          revoked = url;
          setImageUrl(url);
        } else {
          const text = await blob.text();
          const max = 80_000;
          setTextPreview(text.length > max ? text.slice(0, max) + "\n\n… (обрезано)" : text);
        }
      } catch (e) {
        if (!cancelled) {
          setError(e instanceof ApiError ? e.message : "Не удалось загрузить файл");
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();

    return () => {
      cancelled = true;
      if (revoked) URL.revokeObjectURL(revoked);
    };
  }, [fileKey, previewOpen, info.canInlinePreview, info.kind]);

  const onDownload = useCallback(async () => {
    setDownloading(true);
    setError(null);
    try {
      const { url, filename } = await downloadFileByKey(fileKey, { suggestedFilename: info.name });
      triggerBlobDownload(url, filename);
      window.setTimeout(() => URL.revokeObjectURL(url), 4000);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Не удалось скачать файл");
    } finally {
      setDownloading(false);
    }
  }, [fileKey, info.name]);

  return (
    <div className="card" style={{ overflow: "hidden", maxWidth: "100%" }}>
      <div className="row" style={{ justifyContent: "space-between", alignItems: "flex-start", flexWrap: "wrap", gap: "0.5rem" }}>
        <div>
          <h3 style={{ margin: 0 }}>{title}</h3>
          <div className="row" style={{ gap: "0.35rem", marginTop: "0.35rem", flexWrap: "wrap", alignItems: "center" }}>
            <span className="pill">{info.label}</span>
            <span className="muted" style={{ fontSize: "0.85rem", wordBreak: "break-all" }}>
              {info.name}
            </span>
          </div>
        </div>
        <div className="row" style={{ gap: "0.35rem", flexWrap: "wrap" }}>
          {info.canInlinePreview && (
            <button type="button" className="btn secondary" onClick={() => setPreviewOpen((v) => !v)}>
              {previewOpen ? "Скрыть предпросмотр" : "Показать предпросмотр"}
            </button>
          )}
          <button type="button" className="btn secondary" disabled={downloading} onClick={() => void onDownload()}>
            {downloading ? "Загрузка…" : "Скачать"}
          </button>
        </div>
      </div>

      {info.kind === "archive" && (
        <p className="muted" style={{ margin: "0.5rem 0 0", fontSize: "0.85rem" }}>
          Архив нельзя показать в браузере — скачайте и откройте локально.
        </p>
      )}

      {info.kind === "other" && !info.canInlinePreview && (
        <p className="muted" style={{ margin: "0.5rem 0 0", fontSize: "0.85rem" }}>
          Предпросмотр недоступен — используйте «Скачать».
        </p>
      )}

      {error && <div className="flash error" style={{ marginTop: "0.5rem" }}>{error}</div>}

      {previewOpen && info.canInlinePreview && (
        <div style={{ marginTop: "0.5rem", maxWidth: "100%", overflow: "hidden" }}>
          {loading && <p className="muted">Загрузка…</p>}
          {!loading && imageUrl && (
            <img
              src={imageUrl}
              alt={info.name}
              style={{ display: "block", maxWidth: "100%", maxHeight: "14rem", borderRadius: 8, objectFit: "contain" }}
            />
          )}
          {!loading && textPreview != null && <pre style={PREVIEW_BOX}>{textPreview}</pre>}
        </div>
      )}
    </div>
  );
}
