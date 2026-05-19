import { useEffect, useState } from "react";
import { ApiError, fetchAiCheckSettings, updateAiCheckSettings } from "../../api";
import type { AiCheckSettings } from "../../types";

const SUBJECTS = ["MATH", "PHYSICS", "CS", "HISTORY", "LITERATURE", "LANGUAGE", "BIOLOGY", "CHEMISTRY", "GENERAL"] as const;
const PROVIDERS = ["GIGACHAT", "DEEPSEEK", "QWEN"] as const;

export function AdminAiProvidersPage() {
  const [settings, setSettings] = useState<AiCheckSettings | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    void fetchAiCheckSettings()
      .then(setSettings)
      .catch((e) => setError(e instanceof ApiError ? e.message : "Ошибка загрузки"));
  }, []);

  async function save() {
    if (!settings) return;
    setBusy(true);
    setError(null);
    try {
      const saved = await updateAiCheckSettings(settings);
      setSettings(saved);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Не удалось сохранить");
    } finally {
      setBusy(false);
    }
  }

  if (!settings) {
    return <div className="muted">Загрузка…</div>;
  }

  return (
    <div>
      <h1 className="page-title">AI-провайдеры проверки</h1>
      {error && <div className="flash error">{error}</div>}
      <div className="card">
        <div className="field">
          <label>Провайдер по умолчанию</label>
          <select
            value={settings.defaultProvider}
            onChange={(e) => setSettings((s) => (s ? { ...s, defaultProvider: e.target.value } : s))}
          >
            {PROVIDERS.map((p) => (
              <option key={p} value={p}>
                {p}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label>Fallback chain (порядок)</label>
          <input
            value={settings.fallbackChain.join(",")}
            onChange={(e) => setSettings((s) => (s ? { ...s, fallbackChain: e.target.value.split(",").map((x) => x.trim()).filter(Boolean) } : s))}
            placeholder="GIGACHAT,DEEPSEEK,QWEN"
          />
        </div>
      </div>

      <div className="card">
        <h3>Предметные правила</h3>
        {SUBJECTS.map((subj) => (
          <div className="field" key={subj}>
            <label>{subj}</label>
            <select
              value={settings.subjectPolicy[subj] ?? ""}
              onChange={(e) =>
                setSettings((s) =>
                  s
                    ? {
                        ...s,
                        subjectPolicy: {
                          ...s.subjectPolicy,
                          [subj]: e.target.value,
                        },
                      }
                    : s,
                )
              }
            >
              <option value="">(по умолчанию)</option>
              {PROVIDERS.map((p) => (
                <option key={p} value={p}>
                  {p}
                </option>
              ))}
            </select>
          </div>
        ))}
      </div>

      <button type="button" className="btn" disabled={busy} onClick={() => void save()}>
        {busy ? "Сохранение..." : "Сохранить настройки"}
      </button>
    </div>
  );
}
