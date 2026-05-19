import { useCallback, useEffect, useState } from "react";
import type { ChatAttachment } from "../../types";
import { downloadFileByKey, fetchAuthenticatedFileBlob, fileKeyToUrlPath, triggerBlobDownload } from "../../api";

function isInlineImage(att: ChatAttachment): boolean {
  const ct = att.contentType?.toLowerCase() ?? "";
  if (ct.startsWith("image/")) {
    return true;
  }
  const name = att.originalName?.toLowerCase() ?? "";
  return /\.(jpg|jpeg|png|gif|webp|bmp|svg|heic|heif)$/i.test(name);
}

function ChatInlineImage({
  fileKey,
  contentType,
  originalName,
  onOpenLightbox,
}: {
  fileKey: string;
  contentType: string | null;
  originalName: string | null;
  onOpenLightbox: (src: string, alt: string) => void;
}) {
  const [src, setSrc] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [triedDirectUrl, setTriedDirectUrl] = useState(false);

  useEffect(() => {
    let revoked: string | null = null;
    let cancelled = false;
    void (async () => {
      try {
        const blob = await fetchAuthenticatedFileBlob(fileKey);
        const ct = contentType?.trim();
        let display = blob;
        if ((!blob.type || blob.type === "application/octet-stream") && ct?.startsWith("image/")) {
          display = new Blob([await blob.arrayBuffer()], { type: ct });
        } else if (!blob.type || blob.type === "application/octet-stream") {
          if (ct?.startsWith("image/")) {
            display = new Blob([await blob.arrayBuffer()], { type: ct });
          } else {
            display = new Blob([await blob.arrayBuffer()], { type: "image/jpeg" });
          }
        }
        if (cancelled) {
          return;
        }
        const url = URL.createObjectURL(display);
        revoked = url;
        setSrc(url);
      } catch {
        if (!cancelled) setFailed(true);
      }
    })();
    return () => {
      cancelled = true;
      if (revoked) {
        URL.revokeObjectURL(revoked);
      }
    };
  }, [fileKey, contentType]);

  if (failed && !triedDirectUrl) {
    // Fallback: some gateways handle auth via cookie/session and block blob fetch with bearer header.
    const directSrc = fileKeyToUrlPath(fileKey);
    return (
      <img
        src={directSrc}
        alt=""
        className="chat-inline-image"
        loading="lazy"
        onLoad={() => {
          setSrc(directSrc);
          setFailed(false);
          setTriedDirectUrl(true);
        }}
        onError={() => {
          setTriedDirectUrl(true);
          setFailed(true);
        }}
      />
    );
  }

  if (failed) {
    return <span className="muted chat-attachment-fallback">Не удалось показать фото</span>;
  }
  if (!src) {
    return <span className="muted chat-attachment-loading">Изображение…</span>;
  }
  const alt = originalName?.trim() || "Вложение";
  return (
    <button
      type="button"
      className="chat-inline-image-hit"
      aria-label={`Открыть изображение: ${alt}`}
      onClick={() => onOpenLightbox(src, alt)}
    >
      <img src={src} alt="" className="chat-inline-image" loading="lazy" />
    </button>
  );
}

export function ChatMessageAttachments({ attachments }: { attachments: ChatAttachment[] }) {
  const [lightbox, setLightbox] = useState<{ src: string; alt: string } | null>(null);
  const [downloadError, setDownloadError] = useState<string | null>(null);

  const closeLightbox = useCallback(() => setLightbox(null), []);

  useEffect(() => {
    if (!lightbox) {
      return;
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        closeLightbox();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [lightbox, closeLightbox]);

  if (attachments.length === 0) {
    return null;
  }
  return (
    <>
      <div className="chat-attachments-stack">
        {downloadError && <span className="chat-attachment-fallback">{downloadError}</span>}
        {attachments.map((att, i) => (
          <div key={`${att.fileKey}-${i}`} className="chat-attachment-item">
            {isInlineImage(att) ? (
              <ChatInlineImage
                fileKey={att.fileKey}
                contentType={att.contentType}
                originalName={att.originalName ?? null}
                onOpenLightbox={(src, alt) => setLightbox({ src, alt })}
              />
            ) : (
              <button
                type="button"
                className="chat-attachment-download"
                onClick={() => {
                  setDownloadError(null);
                  void downloadFileByKey(att.fileKey, {
                    suggestedFilename: att.originalName ?? null,
                    contentType: att.contentType ?? null,
                  })
                    .then(({ url, filename }) => {
                      triggerBlobDownload(url, filename);
                      window.setTimeout(() => URL.revokeObjectURL(url), 4000);
                    })
                    .catch(() => {
                      setDownloadError("Не удалось скачать файл");
                    });
                }}
              >
                {att.originalName ?? "Файл"}
              </button>
            )}
          </div>
        ))}
      </div>
      {lightbox ? (
        <div
          className="chat-lightbox"
          role="dialog"
          aria-modal="true"
          aria-label={lightbox.alt}
          onClick={closeLightbox}
        >
          <button type="button" className="chat-lightbox-close" aria-label="Закрыть" onClick={closeLightbox}>
            ×
          </button>
          <img
            src={lightbox.src}
            alt={lightbox.alt}
            className="chat-lightbox-img"
            onClick={(e) => e.stopPropagation()}
          />
        </div>
      ) : null}
    </>
  );
}
