import type { AiCheckExecutedTest, AiCheckJob, AiCheckResult } from "../types";

function testStatusPillClass(status: string | undefined): string {
  const u = (status ?? "").toUpperCase();
  if (u === "PASSED") return "pill ok";
  if (u === "FAILED" || u === "TIMEOUT" || u === "FALLBACK") return "pill warn";
  if (u === "SKIPPED") return "pill";
  return "pill";
}

function testStatusLabel(status: string | undefined): string {
  const u = (status ?? "").toUpperCase();
  if (u === "FALLBACK") return "Автопрогон";
  return status ?? "—";
}

function pickPrimaryResult(job: AiCheckJob | null): AiCheckResult | null {
  if (!job?.results?.length) return null;
  return job.results.find((r) => r.primary) ?? job.results[0];
}

export function collectExecutedTests(job: AiCheckJob | null): AiCheckExecutedTest[] {
  const primary = pickPrimaryResult(job);
  if (!primary) return [];
  const list: AiCheckExecutedTest[] = [
    ...(primary.executedTests ?? []),
    ...(primary.projectResult?.executedTests ?? []),
    ...(primary.fileResults ?? []).flatMap((f) => f.executedTests ?? []),
  ];
  const seen = new Set<string>();
  const out: AiCheckExecutedTest[] = [];
  for (const t of list) {
    const key = `${t.name}|${t.command}|${t.filePath ?? ""}`;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(t);
  }
  return out;
}

type Props = {
  job: AiCheckJob | null;
};

export function TeacherLabExecutedTests({ job }: Props) {
  const tests = collectExecutedTests(job);
  if (!job || job.status !== "DONE" || tests.length === 0) {
    return null;
  }

  return (
    <div style={{ marginTop: "0.75rem" }}>
      <h4 style={{ margin: "0 0 0.5rem" }}>Прогоны тестов ({tests.length})</h4>
      <p className="muted" style={{ marginTop: 0, fontSize: "0.85rem" }}>
        Baseline и автотесты платформы по сдаче студента (для кода — не менее трёх разных сценариев).
      </p>
      <table className="table" style={{ fontSize: "0.82rem" }}>
        <thead>
          <tr>
            <th>Тест</th>
            <th>Статус</th>
            <th>Вывод</th>
          </tr>
        </thead>
        <tbody>
          {tests.map((t, idx) => (
            <tr key={`${t.name}-${idx}`}>
              <td>
                <strong>{t.name}</strong>
                {t.filePath ? (
                  <div className="muted" style={{ fontSize: "0.78rem" }}>
                    {t.filePath}
                  </div>
                ) : null}
              </td>
              <td>
                <span className={testStatusPillClass(t.status)}>
                  {testStatusLabel(t.status)}
                </span>
              </td>
              <td>
                <pre
                  style={{
                    margin: 0,
                    maxHeight: "6rem",
                    overflow: "auto",
                    whiteSpace: "pre-wrap",
                    fontSize: "0.75rem",
                  }}
                >
                  {t.output?.trim() || "—"}
                </pre>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
