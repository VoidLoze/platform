import { useCallback, useEffect, useState } from "react";
import { ApiError, fetchAdminRegistrationInvites, generateAdminRegistrationInvites } from "../../api";
import type { RegistrationInviteSecrets } from "../../types";

function sourceLabel(source: string): string {
  switch (source) {
    case "ENV":
      return "из переменных окружения";
    case "FILE":
      return "из файла на диске";
    case "MIXED":
      return "смешанно (env + файл)";
    default:
      return source;
  }
}

async function copyText(text: string): Promise<void> {
  await navigator.clipboard.writeText(text);
}

export function AdminInviteKeysPage() {
  const [data, setData] = useState<RegistrationInviteSecrets | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [copiedKeys, setCopiedKeys] = useState<Record<string, true>>({});
  const [count, setCount] = useState(10);

  const load = useCallback(() => {
    setError(null);
    return fetchAdminRegistrationInvites()
      .then(setData)
      .catch((e) => setError(e instanceof ApiError ? e.message : "Ошибка загрузки"));
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const onCopy = (key: string, value: string) => {
    if (copiedKeys[key]) {
      return;
    }
    void copyText(value)
      .then(() => {
        setCopiedKeys((prev) => ({ ...prev, [key]: true }));
      })
      .catch(() => {
        setError("Не удалось скопировать код. Проверьте доступ к буферу обмена.");
      });
  };

  const onGenerate = (scope: "teacher" | "admin" | "both") => {
    const n = Number.isFinite(count) ? Math.max(1, Math.min(500, Math.trunc(count))) : 1;
    setBusy(true);
    setError(null);
    void generateAdminRegistrationInvites(scope, n)
      .then((next) => {
        setData(next);
        setCopiedKeys({});
      })
      .catch((e) => setError(e instanceof ApiError ? e.message : "Ошибка"))
      .finally(() => setBusy(false));
  };

  return (
    <div>
      <h1 className="page-title">Коды приглашения</h1>
      <p className="muted" style={{ marginTop: "-0.5rem", marginBottom: "1.25rem", maxWidth: "52rem" }}>
        Коды одноразовые: после успешной регистрации код списывается автоматически и повторно не работает. Можно
        генерировать любое количество кодов.
      </p>
      {error && <div className="flash error">{error}</div>}
      {!data && !error && <div className="muted">Загрузка…</div>}
      {data && (
        <div className="card admin-invite-card">
          <div className="admin-invite-meta">
            <span className="pill">Источник: {sourceLabel(data.source)}</span>
            <span className="muted admin-invite-path" title={data.persistedAbsolutePath}>
              {data.persistedFile} → {data.persistedAbsolutePath}
            </span>
          </div>

          <div className="admin-invite-meta" style={{ marginBottom: "0.75rem" }}>
            <label className="muted" htmlFor="invite-count">Количество новых кодов:</label>
            <input
              id="invite-count"
              type="number"
              min={1}
              max={500}
              value={count}
              onChange={(e) => setCount(Number(e.target.value))}
              style={{ width: "7rem" }}
            />
            <button type="button" className="btn secondary" disabled={busy} onClick={() => onGenerate("teacher")}>
              Добавить для преподавателей
            </button>
            <button type="button" className="btn secondary" disabled={busy} onClick={() => onGenerate("admin")}>
              Добавить для админов
            </button>
          </div>

          <div className="admin-invite-row">
            <div className="admin-invite-label">Преподаватели: {data.teacherCount}</div>
            <div className="admin-invite-actions" style={{ flexWrap: "wrap", gap: "0.5rem" }}>
              {data.teacherCodes.map((code, idx) => (
                <button
                  key={code}
                  type="button"
                  className="btn secondary"
                  onClick={() => onCopy(`teacher-${idx}`, code)}
                  disabled={Boolean(copiedKeys[`teacher-${idx}`])}
                >
                  {copiedKeys[`teacher-${idx}`] ? "Скопировано" : `Код ${idx + 1}`}
                </button>
              ))}
            </div>
          </div>

          <div className="admin-invite-row">
            <div className="admin-invite-label">Администраторы: {data.adminCount}</div>
            <div className="admin-invite-actions" style={{ flexWrap: "wrap", gap: "0.5rem" }}>
              {data.adminCodes.map((code, idx) => (
                <button
                  key={code}
                  type="button"
                  className="btn secondary"
                  onClick={() => onCopy(`admin-${idx}`, code)}
                  disabled={Boolean(copiedKeys[`admin-${idx}`])}
                >
                  {copiedKeys[`admin-${idx}`] ? "Скопировано" : `Код ${idx + 1}`}
                </button>
              ))}
            </div>
          </div>

          <div className="admin-invite-footer">
            <button type="button" className="btn secondary" disabled={busy} onClick={() => onGenerate("both")}>
              Добавить обоим
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
