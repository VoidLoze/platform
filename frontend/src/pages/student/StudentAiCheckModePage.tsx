import type { Dispatch, SetStateAction } from "react";
import { useEffect, useMemo, useRef, useState } from "react";
import { motion } from "framer-motion";
import { Link } from "react-router-dom";
import {
  ApiError,
  createAiCheckJob,
  fetchAiCheckJob,
  fetchAiCheckProgressEvents,
  fetchMyAiCheckJobs,
  streamAiCheckProgress,
  uploadFile,
} from "../../api";
import { useAuth } from "../../context/AuthContext";
import type { AiCheckExecutedTest, AiCheckGeneratedTest, AiCheckJob, AiCheckProgressEvent, AiCheckResult } from "../../types";

const SOURCE_LABEL: Record<"TEXT" | "IMAGE" | "CODE_ARCHIVE" | "CODE_GIT" | "DOCUMENT", string> = {
  TEXT: "Текст",
  IMAGE: "Фото/изображение",
  CODE_ARCHIVE: "Архив проекта",
  CODE_GIT: "Git-репозиторий",
  DOCUMENT: "Документ",
};

const PROVIDER_LABEL: Record<string, string> = {
  GIGACHAT: "GigaChat",
  DEEPSEEK: "DeepSeek",
  QWEN: "Qwen",
};

function providerDisplayName(provider: string | null | undefined): string {
  if (!provider) return "ИИ";
  const key = provider.toUpperCase().replace(/-/g, "_");
  const map = PROVIDER_LABEL as Record<string, string>;
  const short = provider.split(".").pop() ?? provider;
  return map[short.toUpperCase()] ?? map[key] ?? provider;
}

function aiCheckTestCounts(r: AiCheckResult | null | undefined): { passed: number; total: number } {
  if (!r?.executedTests?.length) return { passed: 0, total: 0 };
  const total = r.executedTests.length;
  const passed = r.executedTests.filter((e) => (e.status ?? "").toUpperCase() === "PASSED").length;
  return { passed, total };
}

function countExecutedInBlock(block: { executedTests?: AiCheckExecutedTest[] } | null | undefined): { passed: number; total: number } {
  const arr = block?.executedTests ?? [];
  const total = arr.length;
  const passed = arr.filter((e) => (e.status ?? "").toUpperCase() === "PASSED").length;
  return { passed, total };
}

/** Сумма прогонов: по каждому файлу из архива + корневые прогоны из ответа модели. */
function aggregateRuntimeTestStats(r: AiCheckResult | null | undefined): { passed: number; total: number } {
  if (!r) return { passed: 0, total: 0 };
  const fileBlocks = r.fileResults ?? [];
  if (fileBlocks.length > 0) {
    let passed = 0;
    let total = 0;
    for (const b of fileBlocks) {
      const c = countExecutedInBlock(b);
      passed += c.passed;
      total += c.total;
    }
    const root = countExecutedInBlock({ executedTests: r.executedTests });
    passed += root.passed;
    total += root.total;
    return { passed, total };
  }
  return aiCheckTestCounts(r);
}

const PROGRESS_PHASE_LABEL: Partial<Record<string, string>> = {
  JOB_STARTED: "Старт проверки",
  PREPARE_SOURCE: "Подготовка источника",
  SOURCE_READY: "Источник готов",
  RUNTIME_MATERIALIZE: "Сборка workspace",
  RUNTIME_LANG: "Определён язык",
  RUNTIME_DOCKER: "Запуск в контейнере",
  AI_RECOMMENDED_MATERIALIZE: "Подготовка к прогону тестов от ИИ",
  AI_RECOMMENDED_TEST: "Прогон рекомендованного теста",
  AI_SUGGESTED_TESTS_START: "Старт автопрогона по рекомендациям ИИ",
  AI_SUGGESTED_TESTS_DONE: "Автопрогон по рекомендациям ИИ завершён",
  AI_FALLBACK_PIPELINE: "Общий контроль кода по языку проекта",
  AI_PROVIDER_TRY: "Запрос к модели ИИ",
  AI_PROVIDER_DONE: "Ответ модели получен",
  AI_PROVIDER_SKIPPED: "Провайдер пропущен",
  AI_REGENERATE_TESTS: "Перегенерация тестов",
  AI_BASELINE_RUNTIME_START: "Базовый прогон кода",
  AI_BASELINE_RUNTIME_DONE: "Базовый прогон завершён",
  FILE_RUNTIME_START: "Автопрогон файла",
  FILE_RUNTIME_DONE: "Автопрогон файла завершён",
  JOB_DONE: "Проверка завершена",
  JOB_FAILED: "Ошибка выполнения",
};

/** Этапы docker/runtime не показываем ученику в ленте прогресса. */
const HIDDEN_PROGRESS_PHASES = new Set([
  "RUNTIME_MATERIALIZE",
  "RUNTIME_LANG",
  "RUNTIME_DOCKER",
  "RUNTIME_PIPELINE_START",
  "RUNTIME_PIPELINE_DONE",
  "AI_RECOMMENDED_TEST_CONTAINER",
]);

/**
 * Крупная строка в модалке: приоритет — какой файл сейчас в автопрогоне, иначе последний «видимый» этап.
 */
function liveProgressFromTimeline(timeline: AiCheckProgressEvent[]): { line: string; sub?: string } | null {
  if (timeline.length === 0) return null;

  for (let i = timeline.length - 1; i >= 0; i--) {
    const ev = timeline[i]!;
    if (ev.phase === "FILE_RUNTIME_START" && ev.filePath?.trim()) {
      return { line: "Сейчас проверяется файл", sub: ev.filePath.trim() };
    }
  }

  for (let i = timeline.length - 1; i >= 0; i--) {
    const ev = timeline[i]!;
    if (HIDDEN_PROGRESS_PHASES.has(ev.phase)) {
      continue;
    }
    if (ev.phase === "FILE_RUNTIME_DONE" && ev.filePath?.trim()) {
      return { line: "Завершён прогон файла", sub: ev.filePath.trim() };
    }
    if (ev.phase === "AI_PROVIDER_TRY" && ev.detail?.trim()) {
      return { line: "Идёт запрос к модели ИИ", sub: ev.detail.trim() };
    }
    if (ev.phase === "AI_PROVIDER_DONE" && ev.detail?.trim()) {
      return { line: "Ответ модели получен", sub: `Провайдер: ${ev.detail.trim()}` };
    }
    if (ev.phase === "AI_SUGGESTED_TESTS_START" && ev.detail?.trim()) {
      return { line: "Автопрогон по рекомендациям ИИ", sub: ev.detail.trim() };
    }
    if (ev.phase === "AI_BASELINE_RUNTIME_START") {
      return { line: PROGRESS_PHASE_LABEL[ev.phase] ?? ev.phase, sub: ev.detail?.trim() || undefined };
    }
    if (ev.phase === "PREPARE_SOURCE" || ev.phase === "SOURCE_READY") {
      return { line: PROGRESS_PHASE_LABEL[ev.phase] ?? ev.phase, sub: ev.detail?.trim() || undefined };
    }
    const label = PROGRESS_PHASE_LABEL[ev.phase] ?? ev.phase;
    if (ev.detail?.trim()) {
      return { line: label, sub: ev.detail.trim() };
    }
    return { line: label };
  }

  return { line: "Идёт подготовка среды выполнения (Docker)…" };
}

function stripRuntimeFooter(text: string | null | undefined): string {
  let t = (text ?? "").trim();
  if (!t) return "";
  t = t.replace(/\n\s*Runtime report:\s[\s\S]*$/i, "").trim();
  t = t.replace(/^\s*Runtime report:\s[\s\S]*$/i, "").trim();
  return t;
}

function truncateOut(s: string, maxLen: number): string {
  if (!s) return "";
  return s.length <= maxLen ? s : `${s.slice(0, maxLen)}\n… (обрезано)`;
}

function hasAiCheckRichDetail(r: AiCheckResult): boolean {
  if (stripRuntimeFooter(r.detailedFeedback)) return true;
  const n = (a: unknown[]) => (Array.isArray(a) ? a.length : 0);
  return (
    n(r.strengths) > 0 ||
    n(r.issues) > 0 ||
    n(r.findings) > 0 ||
    n(r.generatedTests) > 0 ||
    n(r.executedTests) > 0 ||
    n(r.recommendations) > 0
  );
}

function execStatusLabel(status: string): string {
  const u = (status ?? "").toUpperCase();
  if (u === "FALLBACK") return "Автопрогон (не сценарий)";
  return status;
}

function execStatusClass(status: string): string {
  const u = (status ?? "").toUpperCase();
  if (u === "PASSED") return "pill ok";
  if (u === "FAILED" || u === "TIMEOUT") return "pill warn";
  if (u === "FALLBACK") return "pill warn";
  if (u === "SKIPPED") return "pill";
  return "pill";
}

type Mode = "CODE" | "GENERAL";
type Source = "TEXT" | "IMAGE" | "CODE_ARCHIVE" | "CODE_GIT" | "DOCUMENT";
type Difficulty = "BEGINNER" | "INTERMEDIATE" | "ADVANCED";

type CriteriaTemplate = {
  id: string;
  mode: Mode;
  difficulty: Difficulty;
  title: string;
  keywords: string[];
  body: string;
};

const DIFFICULTY_ORDER: Difficulty[] = ["BEGINNER", "INTERMEDIATE", "ADVANCED"];
const DIFFICULTY_LABEL: Record<Difficulty, string> = {
  BEGINNER: "Простые",
  INTERMEDIATE: "Средние",
  ADVANCED: "Сложные",
};

const CRITERIA_TEMPLATES: CriteriaTemplate[] = [
  { id: "g-essay-basic", mode: "GENERAL", difficulty: "BEGINNER", title: "Сочинение: базовая проверка", keywords: ["сочинение", "грамматика", "структура"], body: "Проверь орфографию, пунктуацию, логику изложения и базовую структуру: вступление, основная часть, вывод." },
  { id: "g-essay-arg", mode: "GENERAL", difficulty: "INTERMEDIATE", title: "Сочинение: аргументация", keywords: ["эссе", "аргументы", "тезис"], body: "Оцени силу тезиса, релевантность аргументов, наличие контраргументов и качество выводов. Укажи слабые места аргументации." },
  { id: "g-history-facts", mode: "GENERAL", difficulty: "BEGINNER", title: "История: фактическая точность", keywords: ["история", "даты", "факты"], body: "Проверь фактическую точность: даты, события, персоналии, причинно-следственные связи. Отметь сомнительные формулировки." },
  { id: "g-history-analysis", mode: "GENERAL", difficulty: "ADVANCED", title: "История: исторический анализ", keywords: ["историография", "анализ", "источники"], body: "Оцени глубину исторического анализа, сопоставление точек зрения, корректность интерпретации источников и контекст эпохи." },
  { id: "g-math-solution", mode: "GENERAL", difficulty: "BEGINNER", title: "Математика: решение задачи", keywords: ["математика", "формулы", "вычисления"], body: "Проверь корректность формул, шагов преобразования и вычислений. Укажи, на каком шаге возникает ошибка и предложи исправление." },
  { id: "g-math-proof", mode: "GENERAL", difficulty: "ADVANCED", title: "Математика: доказательство", keywords: ["доказательство", "теорема", "строгость"], body: "Оцени строгую логическую структуру доказательства: полноту предпосылок, корректность переходов, отсутствие логических разрывов." },
  { id: "g-physics", mode: "GENERAL", difficulty: "INTERMEDIATE", title: "Физика: задача и размерности", keywords: ["физика", "размерность", "единицы"], body: "Проверь физический смысл решения, корректность единиц измерения, размерностный анализ и адекватность финального ответа." },
  { id: "g-chemistry", mode: "GENERAL", difficulty: "INTERMEDIATE", title: "Химия: реакции и баланс", keywords: ["химия", "уравнение", "стехиометрия"], body: "Проверь балансировку уравнений, корректность коэффициентов, стехиометрию и соответствие химическим законам." },
  { id: "g-biology", mode: "GENERAL", difficulty: "BEGINNER", title: "Биология: терминология", keywords: ["биология", "термины", "процессы"], body: "Проверь правильность биологических терминов, корректность описания процессов и отсутствие биологических неточностей." },
  { id: "g-geography", mode: "GENERAL", difficulty: "BEGINNER", title: "География: карта и данные", keywords: ["география", "карта", "регионы"], body: "Проверь географическую точность, корректность регионов, климатических характеристик и логичность пространственных выводов." },
  { id: "g-economics", mode: "GENERAL", difficulty: "ADVANCED", title: "Экономика: модель и выводы", keywords: ["экономика", "модель", "гипотеза"], body: "Оцени обоснованность экономической модели, корректность предпосылок, интерпретацию данных и ограничения выводов." },
  { id: "g-law", mode: "GENERAL", difficulty: "ADVANCED", title: "Право: юридическая логика", keywords: ["право", "норма", "квалификация"], body: "Проверь корректность правовой квалификации, ссылки на нормы, логическую связность правовой аргументации и полноту анализа." },
  { id: "g-philosophy", mode: "GENERAL", difficulty: "ADVANCED", title: "Философия: концептуальная глубина", keywords: ["философия", "концепция", "позиция"], body: "Оцени точность понятий, глубину концептуального анализа, согласованность позиции и работу с контраргументами." },
  { id: "g-foreign-lang", mode: "GENERAL", difficulty: "BEGINNER", title: "Иностранный язык: базовая проверка", keywords: ["английский", "язык", "грамматика"], body: "Проверь грамматику, лексику, стилистику, естественность формулировок и укажи улучшения для уровня B1-B2." },
  { id: "g-foreign-academic", mode: "GENERAL", difficulty: "INTERMEDIATE", title: "Иностранный язык: академический стиль", keywords: ["academic", "style", "formal"], body: "Оцени академический стиль: формальность, связность, точность терминов, избегание разговорных конструкций." },
  { id: "g-lab-report-basic", mode: "GENERAL", difficulty: "INTERMEDIATE", title: "Лабораторная: структура отчета", keywords: ["лабораторная", "отчет", "цель"], body: "Проверь наличие и качество разделов: цель, оборудование, методика, результаты, выводы. Укажи пробелы." },
  { id: "g-lab-report-advanced", mode: "GENERAL", difficulty: "ADVANCED", title: "Лабораторная: анализ погрешностей", keywords: ["погрешность", "эксперимент", "методика"], body: "Оцени методику эксперимента, корректность обработки данных, анализ погрешностей и достоверность выводов." },
  { id: "g-presentation", mode: "GENERAL", difficulty: "BEGINNER", title: "Презентация: ясность и структура", keywords: ["презентация", "слайды", "доклад"], body: "Проверь структуру слайдов, последовательность мысли, читаемость формулировок и соответствие цели доклада." },
  { id: "g-research", mode: "GENERAL", difficulty: "ADVANCED", title: "Исследовательская работа", keywords: ["исследование", "методология", "вывод"], body: "Оцени исследовательский дизайн, корректность методологии, валидность результатов, ограничения и качество выводов." },
  { id: "g-critical-review", mode: "GENERAL", difficulty: "ADVANCED", title: "Критический разбор", keywords: ["критический", "анализ", "ошибки"], body: "Сделай критический разбор: найди логические ошибки, неявные допущения, слабые доказательства и предложи улучшения." },

  { id: "c-style-basic", mode: "CODE", difficulty: "BEGINNER", title: "Код: базовый стиль", keywords: ["стиль", "нейминг", "читаемость"], body: "Проверь читаемость кода: нейминг, форматирование, дублирование, простоту функций. Дай короткие рекомендации." },
  { id: "c-bugs-basic", mode: "CODE", difficulty: "BEGINNER", title: "Код: поиск явных багов", keywords: ["баг", "ошибка", "edge case"], body: "Найди явные баги, неправильные условия, ошибки в обработке null/undefined и пропущенные edge-cases." },
  { id: "c-algorithms", mode: "CODE", difficulty: "INTERMEDIATE", title: "Код: алгоритмы и сложность", keywords: ["алгоритм", "сложность", "оптимизация"], body: "Оцени алгоритмическую сложность, узкие места по времени/памяти и предложи оптимизацию без потери читаемости." },
  { id: "c-tests", mode: "CODE", difficulty: "INTERMEDIATE", title: "Код: качество тестов", keywords: ["тесты", "покрытие", "unit"], body: "Проверь полноту тестов: позитивные/негативные сценарии, граничные условия, изоляция зависимостей и стабильность тестов." },
  { id: "c-architecture", mode: "CODE", difficulty: "ADVANCED", title: "Код: архитектурный обзор", keywords: ["архитектура", "слои", "responsibility"], body: "Оцени архитектуру: разделение ответственности, границы модулей, связность/зацепление, масштабируемость решений." },
  { id: "c-security-web", mode: "CODE", difficulty: "ADVANCED", title: "Код: безопасность web", keywords: ["security", "xss", "sql injection"], body: "Проверь уязвимости: XSS, CSRF, SQL/command injection, ошибки авторизации, утечки чувствительных данных и unsafe defaults." },
  { id: "c-api-design", mode: "CODE", difficulty: "INTERMEDIATE", title: "Код: API-дизайн", keywords: ["api", "контракты", "валидация"], body: "Оцени API: предсказуемость контрактов, валидацию входа, обработку ошибок, идемпотентность и удобство для клиента." },
  { id: "c-db-performance", mode: "CODE", difficulty: "ADVANCED", title: "Код: БД и производительность", keywords: ["sql", "индексы", "n+1"], body: "Проверь SQL/ORM часть: N+1, индексы, избыточные запросы, транзакции, блокировки и потенциальные деградации." },
  { id: "c-concurrency", mode: "CODE", difficulty: "ADVANCED", title: "Код: конкурентность", keywords: ["race condition", "потоки", "async"], body: "Проанализируй риски race condition, deadlock, некорректной синхронизации, ошибки async-цепочек и отмены операций." },
  { id: "c-refactor", mode: "CODE", difficulty: "INTERMEDIATE", title: "Код: план рефакторинга", keywords: ["refactor", "tech debt", "улучшение"], body: "Составь приоритетный план рефакторинга: быстрые улучшения, средние шаги, долгосрочные архитектурные изменения." },
  { id: "c-clean-code", mode: "CODE", difficulty: "BEGINNER", title: "Код: clean code", keywords: ["clean code", "функции", "комментарии"], body: "Оцени соблюдение clean code: короткие функции, явные имена, минимизация побочных эффектов, уместность комментариев." },
  { id: "c-oop-design", mode: "CODE", difficulty: "ADVANCED", title: "Код: OOP/DDD дизайн", keywords: ["oop", "ddd", "domain"], body: "Проверь модель предметной области, корректность абстракций, соблюдение инвариантов и выразительность доменных сущностей." },
  { id: "c-frontend-ux", mode: "CODE", difficulty: "INTERMEDIATE", title: "Frontend: UX и состояние", keywords: ["frontend", "state", "ux"], body: "Проверь управление состоянием, UX при загрузке/ошибках, предсказуемость UI, устойчивость к пустым данным и edge-cases." },
  { id: "c-devops-ci", mode: "CODE", difficulty: "ADVANCED", title: "CI/CD и надёжность", keywords: ["ci", "cd", "pipeline"], body: "Проверь надёжность пайплайна: воспроизводимость сборки, quality gates, кэширование, безопасность секретов и rollback-стратегии." },
];

function AiCheckTemplatesBlock({
  templateQuery,
  setTemplateQuery,
  autoSuggestedTemplates,
  groupedTemplates,
  selectedTemplateIds,
  toggleTemplateSelection,
  selectedTemplates,
  applySelectedTemplates,
  setSelectedTemplateIds,
}: {
  templateQuery: string;
  setTemplateQuery: (v: string) => void;
  autoSuggestedTemplates: CriteriaTemplate[];
  groupedTemplates: { difficulty: Difficulty; items: CriteriaTemplate[] }[];
  selectedTemplateIds: string[];
  toggleTemplateSelection: (id: string) => void;
  selectedTemplates: CriteriaTemplate[];
  applySelectedTemplates: () => void;
  setSelectedTemplateIds: Dispatch<SetStateAction<string[]>>;
}) {
  return (
    <>
      <input
        value={templateQuery}
        onChange={(e) => setTemplateQuery(e.target.value)}
        placeholder="Поиск: например 'лабораторная', 'безопасность', 'доказательство', 'API'"
      />
      {autoSuggestedTemplates.length > 0 && (
        <div style={{ marginTop: "0.55rem" }}>
          <div className="muted" style={{ marginBottom: "0.35rem" }}>
            Автоподбор по теме/тексту
          </div>
          <div style={{ display: "grid", gap: "0.35rem" }}>
            {autoSuggestedTemplates.map((tpl) => {
              const isPicked = selectedTemplateIds.includes(tpl.id);
              return (
                <button
                  key={`auto-${tpl.id}`}
                  type="button"
                  className={`btn secondary ${isPicked ? "active" : ""}`}
                  onClick={() => toggleTemplateSelection(tpl.id)}
                  style={{ textAlign: "left" }}
                >
                  {isPicked ? "✓ " : ""}
                  {tpl.title}
                </button>
              );
            })}
          </div>
        </div>
      )}
      {groupedTemplates.length === 0 ? (
        <p className="muted" style={{ marginTop: "0.45rem" }}>
          Ничего не найдено по запросу.
        </p>
      ) : (
        <div style={{ marginTop: "0.65rem", display: "grid", gap: "0.45rem" }}>
          {groupedTemplates.map((g) => (
            <details
              key={g.difficulty}
              style={{
                borderRadius: "6px",
                border: "1px solid rgba(255,255,255,.08)",
                padding: "0.4rem 0.5rem",
                background: "rgba(255,255,255,.03)",
              }}
            >
              <summary
                style={{
                  cursor: "pointer",
                  userSelect: "none",
                  fontWeight: 600,
                  fontSize: "0.92rem",
                }}
              >
                {DIFFICULTY_LABEL[g.difficulty]}
                <span className="muted" style={{ fontWeight: 400, marginLeft: "0.35rem", fontSize: "0.88em" }}>
                  ({g.items.length})
                </span>
              </summary>
              <div style={{ display: "grid", gap: "0.4rem", marginTop: "0.45rem", paddingLeft: "0.15rem" }}>
                {g.items.map((tpl) => (
                  <button
                    key={tpl.id}
                    type="button"
                    className={`btn secondary ${selectedTemplateIds.includes(tpl.id) ? "active" : ""}`}
                    onClick={() => {
                      toggleTemplateSelection(tpl.id);
                    }}
                    title={tpl.body}
                    style={{ textAlign: "left" }}
                  >
                    {selectedTemplateIds.includes(tpl.id) ? "✓ " : ""}
                    {tpl.title}
                  </button>
                ))}
              </div>
            </details>
          ))}
        </div>
      )}
      {selectedTemplates.length > 0 && (
        <div style={{ marginTop: "0.65rem", display: "grid", gap: "0.45rem" }}>
          <div className="muted">Выбрано шаблонов: {selectedTemplates.length}</div>
          <div className="row" style={{ gap: "0.45rem", flexWrap: "wrap" }}>
            <button type="button" className="btn secondary" onClick={applySelectedTemplates}>
              Собрать промпт из выбранных
            </button>
            <button type="button" className="btn secondary" onClick={() => setSelectedTemplateIds([])}>
              Сбросить выбор
            </button>
          </div>
        </div>
      )}
    </>
  );
}

export function StudentAiCheckModePage({ mode }: { mode: Mode }) {
  const { user } = useAuth();
  const aiBase = user?.role === "ROLE_TEACHER" ? "/teacher/ai-check" : "/student/ai-check";
  const allowed = mode === "CODE" ? (["TEXT", "CODE_ARCHIVE", "CODE_GIT"] as const) : (["TEXT", "DOCUMENT", "IMAGE"] as const);
  const [jobs, setJobs] = useState<AiCheckJob[]>([]);
  const [subject, setSubject] = useState(mode === "CODE" ? "Информатика" : "");
  /** В режиме кода чаще всего загружают ZIP — не оставляем «Текст» по умолчанию, чтобы не скрывать выбор архива. */
  const [sourceType, setSourceType] = useState<Source>(mode === "CODE" ? "CODE_ARCHIVE" : allowed[0]);
  const [text, setText] = useState("");
  const [gitUrl, setGitUrl] = useState("");
  const [instructions, setInstructions] = useState("");
  const [files, setFiles] = useState<File[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [timeline, setTimeline] = useState<AiCheckProgressEvent[]>([]);
  const [detailModal, setDetailModal] = useState<AiCheckResult | null>(null);
  const [detailScopeTab, setDetailScopeTab] = useState<"PROJECT" | "FILES">("PROJECT");
  const [detailFilePath, setDetailFilePath] = useState<string | null>(null);
  const [historyModalOpen, setHistoryModalOpen] = useState(false);
  const [progressModalOpen, setProgressModalOpen] = useState(false);
  /** Ошибка fetch/SSE потока этапов (иначе модалка «висит» на пустой ленте). */
  const [progressStreamError, setProgressStreamError] = useState<string | null>(null);
  const [templateQuery, setTemplateQuery] = useState("");
  const [selectedTemplateIds, setSelectedTemplateIds] = useState<string[]>([]);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const isCode = mode === "CODE";

  const active = useMemo(() => jobs.find((j) => j.id === activeId) ?? null, [jobs, activeId]);

  const primaryResult = useMemo(() => {
    if (!active?.results?.length) return null;
    return active.results.find((r) => r.primary) ?? active.results[0];
  }, [active]);

  async function reload() {
    const list = await fetchMyAiCheckJobs();
    const filtered = list.filter((j) => (mode === "CODE" ? ["TEXT", "CODE_ARCHIVE", "CODE_GIT"].includes(j.sourceType) : ["IMAGE", "DOCUMENT", "TEXT"].includes(j.sourceType)));
    setJobs(filtered);
    if (!activeId && filtered.length > 0) setActiveId(filtered[0].id);
  }

  useEffect(() => {
    void reload().catch((e) => setError(e instanceof ApiError ? e.message : "Ошибка загрузки"));
  }, [mode]);

  const visibleTimeline = useMemo(() => timeline.filter((ev) => !HIDDEN_PROGRESS_PHASES.has(ev.phase)), [timeline]);
  const liveHeadline = useMemo(() => liveProgressFromTimeline(timeline), [timeline]);

  useEffect(() => {
    if (active?.status === "DONE" || active?.status === "FAILED" || active?.status === "CANCELLED") {
      setProgressModalOpen(false);
    }
  }, [active?.status]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      if (detailModal) setDetailModal(null);
      else if (historyModalOpen) setHistoryModalOpen(false);
      else if (progressModalOpen) setProgressModalOpen(false);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [detailModal, historyModalOpen, progressModalOpen]);

  useEffect(() => {
    const timer = window.setInterval(() => void reload().catch(() => {}), 5000);
    return () => window.clearInterval(timer);
  }, [mode]);

  useEffect(() => {
    if (!active?.id) {
      setTimeline([]);
      setProgressStreamError(null);
      return;
    }
    const ac = new AbortController();
    let cancelled = false;
    void (async () => {
      try {
        setProgressStreamError(null);
        const initial = await fetchAiCheckProgressEvents(active.id);
        if (cancelled) return;
        setTimeline(initial);
        if (active.status === "QUEUED" || active.status === "RUNNING") {
          const after = initial.length ? Math.max(...initial.map((x) => x.id)) : 0;
          await streamAiCheckProgress(
            active.id,
            after,
            (ev) => setTimeline((prev) => [...prev, ev].sort((a, b) => a.id - b.id)),
            ac.signal
          );
        }
      } catch (e) {
        if (!cancelled) {
          // Прокси часто рвёт долгий SSE (504) — этапы уже подтягиваются опросом /events каждые 2 с.
          if (e instanceof ApiError && (e.status === 502 || e.status === 503 || e.status === 504)) {
            setProgressStreamError(null);
          } else {
            setProgressStreamError(e instanceof ApiError ? e.message : "Не удалось подключиться к потоку этапов");
          }
        }
      }
    })();
    return () => {
      cancelled = true;
      ac.abort();
    };
  }, [active?.id, active?.status]);

  /** Пока открыта модалка — опрашиваем этапы с сервера (очередь worker ~3с, SSE может не дойти через прокси). */
  useEffect(() => {
    if (!active?.id || !progressModalOpen) return;
    if (active.status !== "QUEUED" && active.status !== "RUNNING") return;

    function mergeById(prev: AiCheckProgressEvent[], incoming: AiCheckProgressEvent[]): AiCheckProgressEvent[] {
      const byId = new Map<number, AiCheckProgressEvent>();
      for (const e of prev) byId.set(e.id, e);
      for (const e of incoming) byId.set(e.id, e);
      return [...byId.values()].sort((a, b) => a.id - b.id);
    }

    const tick = () => {
      void fetchAiCheckProgressEvents(active.id)
        .then((evs) => {
          setTimeline((prev) => mergeById(prev, evs));
        })
        .catch(() => {});
      void fetchAiCheckJob(active.id)
        .then((fresh) => {
          setJobs((prev) => prev.map((j) => (j.id === fresh.id ? fresh : j)));
        })
        .catch(() => {});
    };
    tick();
    const id = window.setInterval(tick, 2000);
    return () => clearInterval(id);
  }, [active?.id, active?.status, progressModalOpen]);

  async function onSubmit() {
    if (!subject.trim()) return setError("Укажите предмет/дисциплину");
    if (sourceType === "CODE_GIT" && !gitUrl.trim()) return setError("Укажите ссылку на репозиторий");
    if (sourceType === "CODE_ARCHIVE" && files.length === 0) return setError("Добавьте ZIP-архив проекта");
    if (sourceType === "TEXT" && !text.trim()) return setError("Добавьте код или описание задачи");
    if (!isCode && sourceType !== "CODE_GIT" && !text.trim() && files.length === 0) return setError("Добавьте текст или файл");

    setBusy(true);
    setError(null);
    try {
      const attachmentFileKeys: string[] = [];
      for (const file of files) {
        const uploaded = await uploadFile(file, "ai-check");
        attachmentFileKeys.push(uploaded.key);
      }
      const job = await createAiCheckJob({
        title: `Проверка: ${subject.trim()}`,
        subject: subject.trim(),
        sourceType,
        studentText: text || null,
        attachmentFileKey: attachmentFileKeys[0] ?? null,
        attachmentFileKeys,
        gitUrl: gitUrl || null,
        customInstructions: instructions || null,
      });
      setJobs((prev) => [job, ...prev]);
      setActiveId(job.id);
      setProgressModalOpen(true);
      void fetchAiCheckJob(job.id)
        .then((fresh) => {
          setJobs((prev) => prev.map((j) => (j.id === fresh.id ? fresh : j)));
        })
        .catch(() => {});
      setText("");
      setGitUrl("");
      setInstructions("");
      setFiles([]);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Не удалось создать проверку");
    } finally {
      setBusy(false);
    }
  }

  function removeFile(idx: number) {
    setFiles((prev) => prev.filter((_, i) => i !== idx));
  }

  function inputHint(): string {
    if (sourceType === "CODE_GIT") return "Вставьте ссылку на публичный или доступный репозиторий.";
    if (sourceType === "CODE_ARCHIVE") return "Лучше загружать ZIP с проектом и тестами.";
    if (sourceType === "IMAGE") return "Добавьте фото/скан и коротко опишите, что проверить.";
    if (sourceType === "DOCUMENT") return "Загрузите документ и добавьте требования в критериях.";
    return isCode
      ? "Можно вставить код напрямую, даже без файлов."
      : "Можно проверить только текст без вложений.";
  }

  const filteredTemplates = useMemo(() => {
    const q = templateQuery.trim().toLowerCase();
    return CRITERIA_TEMPLATES.filter((t) => {
      if (t.mode !== mode) return false;
      if (!q) return true;
      const haystack = `${t.title} ${t.body} ${t.keywords.join(" ")}`.toLowerCase();
      return haystack.includes(q);
    });
  }, [mode, templateQuery]);

  const groupedTemplates = useMemo(() => {
    return DIFFICULTY_ORDER.map((d) => ({
      difficulty: d,
      items: filteredTemplates.filter((t) => t.difficulty === d),
    })).filter((g) => g.items.length > 0);
  }, [filteredTemplates]);

  const autoSuggestedTemplates = useMemo(() => {
    const signal = `${subject} ${text} ${instructions}`.toLowerCase();
    if (!signal.trim()) return [] as CriteriaTemplate[];
    const scored = CRITERIA_TEMPLATES.filter((t) => t.mode === mode)
      .map((tpl) => {
        let score = 0;
        for (const kw of tpl.keywords) {
          if (signal.includes(kw.toLowerCase())) score += 2;
        }
        if (signal.includes(tpl.title.toLowerCase())) score += 1;
        return { tpl, score };
      })
      .filter((x) => x.score > 0)
      .sort((a, b) => b.score - a.score)
      .slice(0, 6)
      .map((x) => x.tpl);
    return scored;
  }, [mode, subject, text, instructions]);

  const selectedTemplates = useMemo(
    () => CRITERIA_TEMPLATES.filter((tpl) => selectedTemplateIds.includes(tpl.id)),
    [selectedTemplateIds]
  );

  function toggleTemplateSelection(templateId: string) {
    setSelectedTemplateIds((prev) =>
      prev.includes(templateId) ? prev.filter((x) => x !== templateId) : [...prev, templateId]
    );
  }

  function applySelectedTemplates() {
    if (selectedTemplates.length === 0) return;
    const combined = selectedTemplates.map((tpl, idx) => `${idx + 1}. ${tpl.body}`).join("\n\n");
    setInstructions(combined);
  }

  const { passed: testsPassed, total: testsTotal } = aggregateRuntimeTestStats(primaryResult);
  const showTestStats = testsTotal > 0;
  const hasMultiFileRuns = (primaryResult?.fileResults?.length ?? 0) > 0;

  return (
    <div>
      <div className="row" style={{ justifyContent: "space-between", alignItems: "flex-start", flexWrap: "wrap", gap: "0.75rem" }}>
        <div>
          <h1 className="page-title" style={{ marginBottom: "0.25rem" }}>
            {mode === "CODE" ? "AI-проверка кода" : "AI-проверка предметов"}
          </h1>
          <p className="muted" style={{ margin: 0 }}>
            <Link to={aiBase}>К выбору режима</Link>
            {" · "}
            <Link to={isCode ? `${aiBase}/general` : `${aiBase}/code`}>
              Перейти в {isCode ? "проверку предметов" : "проверку кода"}
            </Link>
          </p>
        </div>
        <button type="button" className="btn secondary" onClick={() => setHistoryModalOpen(true)}>
          История проверок{jobs.length > 0 ? ` (${jobs.length})` : ""}
        </button>
      </div>
      {error && <div className="flash error">{error}</div>}
      {(active?.status === "RUNNING" || active?.status === "QUEUED") && !progressModalOpen && (
        <p style={{ margin: "0.5rem 0 0" }}>
          <button type="button" className="btn secondary" onClick={() => setProgressModalOpen(true)}>
            Показать ход проверки
          </button>
        </p>
      )}
      <div className="card">
        <h3>Новая проверка</h3>
        <div className="field">
          <label>Предмет / дисциплина</label>
          <input value={subject} onChange={(e) => setSubject(e.target.value)} />
        </div>
        <div className="field">
          <label>Тип проверки</label>
          <select value={sourceType} onChange={(e) => setSourceType(e.target.value as Source)}>
            {allowed.map((s) => (
              <option key={s} value={s}>
                {SOURCE_LABEL[s]}
              </option>
            ))}
          </select>
          <p className="muted" style={{ marginTop: "0.35rem" }}>
            {inputHint()}
          </p>
        </div>
        {sourceType === "CODE_GIT" ? (
          <div className="field">
            <label>Git URL</label>
            <input value={gitUrl} onChange={(e) => setGitUrl(e.target.value)} placeholder="https://github.com/user/repo" />
          </div>
        ) : (isCode ? sourceType === "CODE_ARCHIVE" : true) ? (
          <div className="field">
            <label>{isCode ? "ZIP-архив проекта" : "Файлы (опционально)"}</label>
            <button type="button" className="btn secondary" onClick={() => fileInputRef.current?.click()}>
              {isCode ? "Выбрать ZIP-архив" : "Добавить файлы"}
            </button>
            {isCode && sourceType === "CODE_ARCHIVE" && (
              <p className="muted" style={{ marginTop: "0.35rem", fontSize: "0.9rem" }}>
                Архив сначала появится в списке ниже; на сервер он отправится при нажатии «Запустить проверку» (до ~50 МБ).
              </p>
            )}
            <input
              ref={fileInputRef}
              style={{ display: "none" }}
              type="file"
              multiple={!isCode}
              accept={
                isCode ? ".zip,.ZIP,application/zip,application/x-zip-compressed,application/octet-stream" : undefined
              }
              onChange={(e) => {
                const picked = Array.from(e.target.files ?? []);
                if (picked.length === 0) return;
                if (isCode && sourceType === "CODE_ARCHIVE") {
                  const f = picked[0];
                  const ok = /\.zip$/i.test(f.name);
                  if (!ok) {
                    setError("Нужен файл с расширением .zip — выберите ZIP-архив проекта.");
                    e.currentTarget.value = "";
                    return;
                  }
                  setError(null);
                  setFiles([f]);
                } else {
                  setFiles((prev) => [...prev, ...picked]);
                }
                e.currentTarget.value = "";
              }}
            />
            {files.length > 0 && (
              <div style={{ marginTop: "0.5rem", display: "grid", gap: "0.35rem" }}>
                {files.map((f, idx) => (
                  <div key={`${f.name}-${f.lastModified}-${idx}`} className="row" style={{ justifyContent: "space-between" }}>
                    <span className="muted">{f.name}</span>
                    <button type="button" className="btn secondary" onClick={() => removeFile(idx)}>
                      Удалить
                    </button>
                  </div>
                ))}
              </div>
            )}
          </div>
        ) : null}
        {(sourceType === "TEXT" || !isCode) && (
          <div className="field">
            <label>{mode === "CODE" ? "Код / комментарий" : "Текст / пояснение"}</label>
            <textarea
              value={text}
              onChange={(e) => setText(e.target.value)}
              placeholder={
                isCode
                  ? "Например: вставьте код файла или опишите, где в проекте искать проблему."
                  : "Например: проверь грамотность, полноту ответа, логичность аргументов."
              }
            />
          </div>
        )}
        {isCode && sourceType !== "TEXT" && (
          <div className="field">
            <label>Комментарий / пояснение (опционально)</label>
            <textarea
              value={text}
              onChange={(e) => setText(e.target.value)}
              placeholder="Опишите контекст задачи, что важно проверить, ограничения запуска или особенности структуры."
            />
          </div>
        )}
        <div className="field">
          <label>Критерии (опционально)</label>
          <textarea
            value={instructions}
            onChange={(e) => setInstructions(e.target.value)}
            placeholder={
              isCode
                ? "Пример: акцент на архитектуру, читаемость, безопасность, тесты."
                : "Пример: оценивать по структуре, фактической точности и стилю."
            }
          />
        </div>
        <div className="field">
          <details
            style={{
              borderRadius: "8px",
              border: "1px solid rgba(255,255,255,.12)",
              padding: "0.55rem 0.65rem",
              background: "rgba(0,0,0,.18)",
            }}
          >
            <summary
              style={{
                cursor: "pointer",
                userSelect: "none",
                fontWeight: 600,
                listStylePosition: "outside",
              }}
            >
              Шаблоны критериев
              <span className="muted" style={{ fontWeight: 400, marginLeft: "0.35rem", fontSize: "0.9em" }}>
                — разверните, чтобы выбрать из списка
              </span>
            </summary>
            <div style={{ marginTop: "0.75rem" }}>
              <AiCheckTemplatesBlock
                templateQuery={templateQuery}
                setTemplateQuery={setTemplateQuery}
                autoSuggestedTemplates={autoSuggestedTemplates}
                groupedTemplates={groupedTemplates}
                selectedTemplateIds={selectedTemplateIds}
                toggleTemplateSelection={toggleTemplateSelection}
                selectedTemplates={selectedTemplates}
                applySelectedTemplates={applySelectedTemplates}
                setSelectedTemplateIds={setSelectedTemplateIds}
              />
            </div>
          </details>
        </div>
        <button type="button" className="btn" disabled={busy} onClick={() => void onSubmit()}>
          {busy ? "Создание..." : "Запустить проверку"}
        </button>
      </div>

      {active?.status === "FAILED" && (
        <div className="flash error" style={{ marginTop: "1rem" }}>
          {active.errorMessage ?? "Проверка завершилась с ошибкой."}
        </div>
      )}

      {active?.status === "DONE" && primaryResult && (
        <div className="card premium-card" style={{ marginTop: "1rem" }}>
          <div className="row" style={{ justifyContent: "space-between", gap: "0.75rem", flexWrap: "wrap", alignItems: "baseline" }}>
            <h3 style={{ margin: 0, fontSize: "1.1rem" }}>Результат</h3>
            <span className="muted" style={{ fontSize: "0.85rem" }}>
              {active.title ?? active.subject}
            </span>
          </div>
          <div
            style={{
              marginTop: "0.75rem",
              display: "grid",
              gridTemplateColumns: "repeat(auto-fit, minmax(140px, 1fr))",
              gap: "0.85rem",
            }}
          >
            <div style={{ padding: "0.65rem 0.85rem", borderRadius: "8px", background: "rgba(255,255,255,.04)" }}>
              <div className="muted" style={{ fontSize: "0.75rem", letterSpacing: "0.02em", marginBottom: "0.2rem" }}>
                Мнение ({providerDisplayName(primaryResult.provider)})
              </div>
              <div style={{ fontSize: "1.35rem", fontWeight: 700 }}>{primaryResult.score.toFixed(0)} б.</div>
              <div className="muted" style={{ fontSize: "0.8rem" }}>
                из {primaryResult.maxScore.toFixed(0)}
              </div>
            </div>
            {showTestStats ? (
              <div style={{ padding: "0.65rem 0.85rem", borderRadius: "8px", background: "rgba(255,255,255,.04)" }}>
                <div className="muted" style={{ fontSize: "0.75rem", letterSpacing: "0.02em", marginBottom: "0.2rem" }}>
                  {hasMultiFileRuns ? "Автопрогоны (всего по архиву)" : "Автотесты (пройдено)"}
                </div>
                <div style={{ fontSize: "1.35rem", fontWeight: 700 }}>
                  {testsPassed} / {testsTotal}
                </div>
              </div>
            ) : isCode ? (
              <div style={{ padding: "0.65rem 0.85rem", borderRadius: "8px", background: "rgba(255,255,255,.04)" }}>
                <div className="muted" style={{ fontSize: "0.75rem", marginBottom: "0.2rem" }}>
                  Автотесты
                </div>
                <div style={{ fontSize: "0.95rem" }}>нет данных прогона</div>
              </div>
            ) : null}
          </div>
          <blockquote
            style={{
              margin: "1rem 0 0",
              padding: "0.75rem 1rem",
              borderLeft: "3px solid rgba(120,170,255,0.5)",
              background: "rgba(255,255,255,.03)",
              fontSize: "0.95rem",
              lineHeight: 1.5,
              whiteSpace: "pre-wrap",
            }}
          >
            {stripRuntimeFooter(primaryResult.summary) || "(краткий вывод пуст)"}
          </blockquote>
          {hasAiCheckRichDetail(primaryResult) ? (
            <div className="row" style={{ marginTop: "1rem", flexWrap: "wrap", gap: "0.5rem" }}>
              <button
                type="button"
                className="btn"
                onClick={() => {
                  setDetailScopeTab("PROJECT");
                  setDetailFilePath(null);
                  setDetailModal(primaryResult);
                }}
              >
                Полный разбор{isCode ? ", тесты и вывод прогона" : ""}
              </button>
            </div>
          ) : null}
        </div>
      )}

      {progressModalOpen && active && (active.status === "QUEUED" || active.status === "RUNNING") && (
        <div className="modal-root">
          <button type="button" className="modal-backdrop" aria-label="Закрыть" onClick={() => setProgressModalOpen(false)} />
          <div className="modal-panel" style={{ width: "min(520px, 96vw)" }}>
            <div className="modal-head">
              <h2>Ход проверки</h2>
              <button type="button" className="modal-close" aria-label="Закрыть" onClick={() => setProgressModalOpen(false)}>
                ×
              </button>
            </div>
            <div className="modal-body">
              <p className="muted" style={{ marginTop: 0 }}>
                {active.title ?? active.subject}{" "}
                <span className="pill" style={{ marginLeft: "0.35rem" }}>
                  {active.status}
                </span>
              </p>
              {liveHeadline ? (
                <div
                  style={{
                    marginTop: "0.65rem",
                    padding: "0.6rem 0.75rem",
                    borderRadius: "10px",
                    background: "rgba(80, 140, 255, 0.12)",
                    border: "1px solid rgba(120, 170, 255, 0.35)",
                  }}
                >
                  <div style={{ fontWeight: 700, fontSize: "0.95rem", lineHeight: 1.35 }}>{liveHeadline.line}</div>
                  {liveHeadline.sub ? (
                    <div
                      className="muted"
                      style={{
                        marginTop: "0.35rem",
                        fontSize: "0.86rem",
                        lineHeight: 1.4,
                        wordBreak: "break-word",
                        fontFamily: "ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace",
                      }}
                    >
                      {liveHeadline.sub}
                    </div>
                  ) : null}
                </div>
              ) : null}
              {progressStreamError ? (
                <div className="flash error" style={{ marginTop: "0.5rem", fontSize: "0.88rem" }}>
                  {progressStreamError}
                  <p className="muted" style={{ margin: "0.5rem 0 0", fontSize: "0.82rem" }}>
                    Этапы всё равно подгружаются опросом сервера; при необходимости нажмите «Обновить статус».
                  </p>
                </div>
              ) : null}
              {visibleTimeline.length === 0 ? (
                <div className="muted" style={{ marginTop: progressStreamError ? "0.75rem" : liveHeadline ? "0.65rem" : 0 }}>
                  {timeline.length > 0 ? (
                    <p style={{ margin: 0, fontSize: "0.88rem" }}>
                      Список ниже скрывает только технические шаги Docker; что сейчас происходит — в блоке выше.
                    </p>
                  ) : active.status === "QUEUED" ? (
                    <div style={{ margin: 0 }}>
                      <p style={{ margin: "0 0 0.45rem" }}>Задача в очереди — обычно старт в течение нескольких секунд.</p>
                      {isCode ? (
                        <details style={{ marginTop: "0.35rem", fontSize: "0.82rem" }}>
                          <summary style={{ cursor: "pointer", userSelect: "none" }}>Про краткие контейнеры в Docker</summary>
                          <p className="muted" style={{ margin: "0.45rem 0 0" }}>
                            Сервис <code>aicheck-runner</code> для кода поднимает одноразовые контейнеры (<code>docker run --rm</code>) —
                            это нормально.
                          </p>
                        </details>
                      ) : null}
                    </div>
                  ) : (
                    <p style={{ margin: 0 }}>Подключаемся к этапам и ждём первые события от сервера…</p>
                  )}
                </div>
              ) : (
                <ol style={{ paddingLeft: "1.2rem", margin: liveHeadline ? "0.55rem 0 0" : "0.75rem 0 0" }}>
                  {visibleTimeline.map((ev) => (
                    <motion.li key={ev.id} initial={{ opacity: 0 }} animate={{ opacity: 1 }} style={{ marginBottom: "0.45rem" }}>
                      <span className="muted" style={{ fontSize: "0.82rem" }}>
                        {new Date(ev.createdAt).toLocaleTimeString()}{" "}
                      </span>
                      <strong>{PROGRESS_PHASE_LABEL[ev.phase] ?? ev.phase}</strong>
                      {ev.scope && ev.scope !== "PROJECT" ? (
                        <span className="pill" style={{ marginLeft: "0.35rem" }}>
                          {ev.scope}
                          {ev.filePath ? `: ${ev.filePath}` : ""}
                        </span>
                      ) : null}
                      {ev.detail ? (
                        <div className="muted" style={{ fontSize: "0.82rem", marginTop: "0.15rem", whiteSpace: "pre-wrap" }}>
                          {ev.detail}
                        </div>
                      ) : null}
                    </motion.li>
                  ))}
                </ol>
              )}
            </div>
            <div className="modal-footer">
              <button type="button" className="btn secondary" onClick={() => setProgressModalOpen(false)}>
                Продолжить в фоне
              </button>
              <button
                type="button"
                className="btn secondary"
                onClick={() => void fetchAiCheckJob(active.id).then((fresh) => setJobs((prev) => prev.map((j) => (j.id === fresh.id ? fresh : j))))}
              >
                Обновить статус
              </button>
            </div>
          </div>
        </div>
      )}

      {historyModalOpen && (
        <div className="modal-root">
          <button type="button" className="modal-backdrop" aria-label="Закрыть" onClick={() => setHistoryModalOpen(false)} />
          <div className="modal-panel" style={{ width: "min(560px, 96vw)" }}>
            <div className="modal-head">
              <h2>История проверок</h2>
              <button type="button" className="modal-close" aria-label="Закрыть" onClick={() => setHistoryModalOpen(false)}>
                ×
              </button>
            </div>
            <div className="modal-body" style={{ paddingTop: 0 }}>
              {jobs.length === 0 ? (
                <p className="muted">Пока нет проверок в этом режиме.</p>
              ) : (
                <ul style={{ listStyle: "none", padding: 0, margin: 0, display: "grid", gap: "0.5rem" }}>
                  {jobs.map((j) => {
                    const pr = j.results?.length ? j.results.find((r) => r.primary) ?? j.results[0] : null;
                    const tc = aggregateRuntimeTestStats(pr);
                    return (
                      <li key={j.id}>
                        <button
                          type="button"
                          className={`btn secondary ${activeId === j.id ? "active" : ""}`}
                          style={{ width: "100%", textAlign: "left", display: "block", padding: "0.65rem 0.85rem" }}
                          onClick={() => {
                            setActiveId(j.id);
                            setHistoryModalOpen(false);
                          }}
                        >
                          <div style={{ fontWeight: 600 }}>{j.subject}</div>
                          <div className="muted" style={{ fontSize: "0.82rem", marginTop: "0.2rem" }}>
                            {new Date(j.createdAt).toLocaleString()} · {SOURCE_LABEL[j.sourceType as keyof typeof SOURCE_LABEL] ?? j.sourceType} · {j.status}
                            {j.status === "DONE" && pr ? (
                              <>
                                {" "}
                                · {pr.score.toFixed(0)}/{pr.maxScore.toFixed(0)} б.
                                {tc.total > 0 ? ` · тесты ${tc.passed}/${tc.total}` : ""}
                              </>
                            ) : null}
                          </div>
                        </button>
                      </li>
                    );
                  })}
                </ul>
              )}
            </div>
            <div className="modal-footer">
              <button type="button" className="btn secondary" onClick={() => setHistoryModalOpen(false)}>
                Закрыть
              </button>
            </div>
          </div>
        </div>
      )}

      {detailModal && (
        <div className="modal-root">
          <button type="button" className="modal-backdrop" aria-label="Закрыть окно разбора" onClick={() => setDetailModal(null)} />
          <div className="modal-panel modal-panel--xlarge">
            <div className="modal-head">
              <h2>
                Разбор: {detailModal.provider} · {detailModal.score.toFixed(1)} / {detailModal.maxScore}
              </h2>
              <button type="button" className="modal-close" aria-label="Закрыть" onClick={() => setDetailModal(null)}>
                ×
              </button>
            </div>
            <div className="modal-body">
              {isCode && ((detailModal.fileResults ?? []).length > 0) && (
                <section style={{ marginBottom: "1rem" }}>
                  <div className="row" style={{ gap: "0.45rem", flexWrap: "wrap" }}>
                    <button
                      type="button"
                      className={`btn secondary ${detailScopeTab === "PROJECT" ? "active" : ""}`}
                      onClick={() => {
                        setDetailScopeTab("PROJECT");
                        setDetailFilePath(null);
                      }}
                    >
                      Проект
                    </button>
                    <button
                      type="button"
                      className={`btn secondary ${detailScopeTab === "FILES" ? "active" : ""}`}
                      onClick={() => {
                        setDetailScopeTab("FILES");
                        if (!detailFilePath && (detailModal.fileResults?.length ?? 0) > 0) {
                          setDetailFilePath(detailModal.fileResults?.[0]?.filePath ?? null);
                        }
                      }}
                    >
                      Файлы ({detailModal.fileResults?.length ?? 0})
                    </button>
                  </div>
                </section>
              )}
              {stripRuntimeFooter(detailModal.detailedFeedback) && (
                <section style={{ marginBottom: "1.25rem" }}>
                  <h3 style={{ margin: "0 0 0.5rem", fontSize: "1rem" }}>Развёрнутый комментарий</h3>
                  <div className="card" style={{ margin: 0 }}>
                    <p style={{ margin: 0, whiteSpace: "pre-wrap" }}>{stripRuntimeFooter(detailModal.detailedFeedback)}</p>
                  </div>
                </section>
              )}

              {(detailScopeTab === "PROJECT" || !((detailModal.fileResults ?? []).length > 0)) && detailModal.strengths.length > 0 && (
                <section style={{ marginBottom: "1.25rem" }}>
                  <h3 style={{ margin: "0 0 0.5rem", fontSize: "1rem" }}>Сильные стороны</h3>
                  <ul>
                    {detailModal.strengths.map((s, i) => (
                      <li key={`s-${i}`}>{s}</li>
                    ))}
                  </ul>
                </section>
              )}

              {(detailScopeTab === "PROJECT" || !((detailModal.fileResults ?? []).length > 0)) && detailModal.issues.length > 0 && (
                <section style={{ marginBottom: "1.25rem" }}>
                  <h3 style={{ margin: "0 0 0.5rem", fontSize: "1rem" }}>На что обратить внимание</h3>
                  <ul>
                    {detailModal.issues.map((s, i) => (
                      <li key={`i-${i}`}>{s}</li>
                    ))}
                  </ul>
                </section>
              )}

              {(detailScopeTab === "PROJECT" || !((detailModal.fileResults ?? []).length > 0)) && detailModal.findings.length > 0 && (
                <section style={{ marginBottom: "1.25rem" }}>
                  <h3 style={{ margin: "0 0 0.5rem", fontSize: "1rem" }}>Замечания по коду / файлам</h3>
                  <div style={{ display: "grid", gap: "0.6rem" }}>
                    {detailModal.findings.map((f, idx) => (
                      <div key={`f-${idx}`} className="card" style={{ margin: 0 }}>
                        <div className="row" style={{ justifyContent: "space-between", flexWrap: "wrap", gap: "0.35rem" }}>
                          <strong>{f.title}</strong>
                          <span className={`pill ${f.severity === "critical" ? "warn" : ""}`}>{f.severity}</span>
                        </div>
                        <p className="muted" style={{ margin: "0.35rem 0", fontSize: "0.88rem" }}>
                          {f.filePath}
                          {(f.lineStart != null || f.lineEnd != null) && (
                            <>
                              {" "}
                              ({f.lineStart ?? "?"}:{f.lineEnd ?? "?"})
                            </>
                          )}
                        </p>
                        <p style={{ margin: 0 }}>{f.explanation}</p>
                        {f.snippet && (
                          <pre style={{ marginTop: "0.5rem", fontSize: "0.8rem", overflow: "auto" }}>{f.snippet}</pre>
                        )}
                      </div>
                    ))}
                  </div>
                </section>
              )}

              {isCode && detailScopeTab === "PROJECT" && (() => {
                const fileBlocks = detailModal.fileResults ?? [];
                const gen = detailModal.generatedTests ?? [];
                const ex = detailModal.executedTests ?? [];
                const modelRows =
                  gen.length > 0 ? gen.map((t, ti) => ({ test: t, exec: ex[ti] })) : ex.map((row) => ({ test: undefined, exec: row }));

                if (fileBlocks.length > 0) {
                  let filePassed = 0;
                  let fileTotal = 0;
                  for (const b of fileBlocks) {
                    const c = countExecutedInBlock(b);
                    filePassed += c.passed;
                    fileTotal += c.total;
                  }
                  const rootC = countExecutedInBlock({ executedTests: detailModal.executedTests });
                  return (
                    <section style={{ marginBottom: "1.25rem" }}>
                      <h3 style={{ margin: "0 0 0.5rem", fontSize: "1rem" }}>Сводка по архиву</h3>
                      <div className="card" style={{ margin: "0 0 0.85rem" }}>
                        <p style={{ margin: "0 0 0.5rem" }}>
                          Найдено заданий с точкой входа (<code>main</code>): <strong>{fileBlocks.length}</strong>. Автопрогоны по заданиям:{" "}
                          <strong>
                            {filePassed}/{fileTotal}
                          </strong>
                          {rootC.total > 0 ? (
                            <>
                              {" "}
                              · прогоны из ответа модели на весь проект:{" "}
                              <strong>
                                {rootC.passed}/{rootC.total}
                              </strong>
                            </>
                          ) : null}
                          .
                        </p>
                        <p className="muted" style={{ margin: 0, fontSize: "0.88rem" }}>
                          Подробные логи baseline (компиляция в общий каталог и запуск) по каждому файлу — во вкладке «Файлы». В таблице ниже можно перейти к нужному
                          заданию.
                        </p>
                      </div>
                      <div
                        style={{
                          borderRadius: "8px",
                          border: "1px solid rgba(255,255,255,.1)",
                          overflow: "hidden",
                          marginBottom: modelRows.length > 0 ? "1rem" : 0,
                        }}
                      >
                        <table style={{ width: "100%", borderCollapse: "collapse", fontSize: "0.88rem" }}>
                          <thead>
                            <tr style={{ background: "rgba(255,255,255,.06)", textAlign: "left" }}>
                              <th style={{ padding: "0.45rem 0.65rem" }}>Файл</th>
                              <th style={{ padding: "0.45rem 0.65rem", width: "100px" }}>Статус</th>
                              <th style={{ padding: "0.45rem 0.65rem", width: "110px" }}>Тесты</th>
                            </tr>
                          </thead>
                          <tbody>
                            {fileBlocks.map((b, bi) => {
                              const tc = countExecutedInBlock(b);
                              const short = b.filePath?.split("/").pop() ?? `file_${bi + 1}`;
                              return (
                                <tr key={`sum-${bi}-${b.filePath}`} style={{ borderTop: "1px solid rgba(255,255,255,.07)" }}>
                                  <td style={{ padding: "0.45rem 0.65rem", maxWidth: "1px" }}>
                                    <button
                                      type="button"
                                      onClick={() => {
                                        setDetailScopeTab("FILES");
                                        setDetailFilePath(b.filePath);
                                      }}
                                      style={{
                                        background: "none",
                                        border: "none",
                                        color: "inherit",
                                        cursor: "pointer",
                                        textDecoration: "underline",
                                        padding: 0,
                                        font: "inherit",
                                        textAlign: "left",
                                        maxWidth: "100%",
                                      }}
                                      title={b.filePath}
                                    >
                                      <span style={{ display: "block", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                                        {short}
                                      </span>
                                      <span className="muted" style={{ fontSize: "0.78rem", display: "block", marginTop: "0.15rem" }}>
                                        {b.filePath}
                                      </span>
                                    </button>
                                  </td>
                                  <td style={{ padding: "0.45rem 0.65rem", verticalAlign: "top" }}>
                                    <span className={execStatusClass(b.status)}>{b.status}</span>
                                  </td>
                                  <td style={{ padding: "0.45rem 0.65rem", verticalAlign: "top" }}>
                                    {tc.total > 0 ? (
                                      <>
                                        {tc.passed}/{tc.total}
                                      </>
                                    ) : (
                                      <span className="muted">—</span>
                                    )}
                                  </td>
                                </tr>
                              );
                            })}
                          </tbody>
                        </table>
                      </div>
                      {modelRows.length > 0 ? (
                        <>
                          <h3 style={{ margin: "0 0 0.5rem", fontSize: "1rem" }}>Прогоны из ответа модели (на весь проект)</h3>
                          <p className="muted" style={{ marginBottom: "0.65rem", fontSize: "0.9rem" }}>
                            Команды из поля <code>command</code> выполняются в корне распакованного архива. Для Java со <code>Scanner</code> подставляется{" "}
                            <code>stdin</code> или шаги теста.
                          </p>
                          <div style={{ display: "grid", gap: "0.65rem" }}>
                            {modelRows.map((row, ti) => (
                              <SuggestedTestWithRunCard key={`run-${ti}-${row.exec?.name ?? row.test?.name ?? ti}`} test={row.test} index={ti} exec={row.exec} />
                            ))}
                          </div>
                        </>
                      ) : null}
                    </section>
                  );
                }

                return (
                  <section style={{ marginBottom: "1.25rem" }}>
                    <h3 style={{ margin: "0 0 0.5rem", fontSize: "1rem" }}>Рекомендованные тесты и автопрогон ({modelRows.length})</h3>
                    <p className="muted" style={{ marginBottom: "0.65rem", fontSize: "0.9rem" }}>
                      Команды из поля <code>command</code> выполняются автоматически. Для программ на Java со <code>Scanner</code> платформа подставляет ввод из поля{" "}
                      <code>stdin</code> модели или собирает его из текстовых шагов (<code>eggs</code>, <code>flour</code> и т.д.). Если <code>command</code> пустой — для
                      пункта из ответа модели запускается общий языковой прогон. Если модель не вернула <code>generated_tests</code>, всё равно выполняется базовый прогон
                      (сборка/типовой шаг для языка проекта); результаты ниже.
                    </p>
                    {modelRows.length === 0 ? (
                      <p className="muted">Прогоны не выполнялись (нет ответа runner или материал не подошёл для runtime).</p>
                    ) : (
                      <div style={{ display: "grid", gap: "0.65rem" }}>
                        {modelRows.map((row, ti) => (
                          <SuggestedTestWithRunCard key={`run-${ti}-${row.exec?.name ?? row.test?.name ?? ti}`} test={row.test} index={ti} exec={row.exec} />
                        ))}
                      </div>
                    )}
                  </section>
                );
              })()}

              {isCode && detailScopeTab === "FILES" && (
                <section style={{ marginBottom: "1.25rem" }}>
                  <h3 style={{ margin: "0 0 0.65rem", fontSize: "1rem" }}>Пофайловая проверка</h3>
                  {(detailModal.fileResults ?? []).length === 0 ? (
                    <p className="muted">Пофайловые результаты отсутствуют.</p>
                  ) : (
                    <div
                      style={{
                        display: "grid",
                        gridTemplateColumns: "repeat(auto-fit, minmax(220px, 1fr))",
                        gap: "1rem",
                        alignItems: "stretch",
                      }}
                    >
                      <nav
                        style={{
                          borderRadius: "10px",
                          border: "1px solid rgba(255,255,255,.1)",
                          background: "rgba(0,0,0,.2)",
                          overflow: "hidden",
                          display: "flex",
                          flexDirection: "column",
                          minHeight: "200px",
                        }}
                      >
                        <div
                          style={{
                            padding: "0.5rem 0.65rem",
                            fontWeight: 600,
                            fontSize: "0.82rem",
                            letterSpacing: "0.02em",
                            borderBottom: "1px solid rgba(255,255,255,.08)",
                            background: "rgba(255,255,255,.04)",
                          }}
                        >
                          Задания · {(detailModal.fileResults ?? []).length}
                        </div>
                        <ul
                          style={{
                            listStyle: "none",
                            margin: 0,
                            padding: "0.35rem",
                            overflowY: "auto",
                            maxHeight: "min(52vh, 420px)",
                            display: "flex",
                            flexDirection: "column",
                            gap: "0.3rem",
                          }}
                        >
                          {(detailModal.fileResults ?? []).map((b, bi) => {
                            const tc = countExecutedInBlock(b);
                            const base = b.filePath?.split("/").pop() ?? `file_${bi + 1}`;
                            const active = detailFilePath === b.filePath || (!detailFilePath && bi === 0);
                            return (
                              <li key={`fb-${bi}-${b.filePath}`}>
                                <button
                                  type="button"
                                  onClick={() => setDetailFilePath(b.filePath)}
                                  title={b.filePath}
                                  style={{
                                    width: "100%",
                                    textAlign: "left",
                                    cursor: "pointer",
                                    borderRadius: "8px",
                                    border: active ? "1px solid rgba(120,170,255,0.45)" : "1px solid rgba(255,255,255,.08)",
                                    background: active ? "rgba(120,170,255,0.12)" : "rgba(255,255,255,.03)",
                                    color: "inherit",
                                    padding: "0.45rem 0.5rem",
                                    display: "grid",
                                    gap: "0.2rem",
                                  }}
                                >
                                  <span
                                    style={{
                                      fontWeight: 600,
                                      fontSize: "0.86rem",
                                      overflow: "hidden",
                                      textOverflow: "ellipsis",
                                      whiteSpace: "nowrap",
                                    }}
                                  >
                                    {base}
                                  </span>
                                  <span className="muted" style={{ fontSize: "0.72rem", lineHeight: 1.25, wordBreak: "break-all" }}>
                                    {b.filePath}
                                  </span>
                                  <span className="row" style={{ gap: "0.35rem", flexWrap: "wrap", marginTop: "0.1rem" }}>
                                    <span className={execStatusClass(b.status)} style={{ fontSize: "0.72rem" }}>
                                      {b.status}
                                    </span>
                                    {tc.total > 0 ? (
                                      <span className="muted" style={{ fontSize: "0.72rem" }}>
                                        {tc.passed}/{tc.total}
                                      </span>
                                    ) : null}
                                  </span>
                                </button>
                              </li>
                            );
                          })}
                        </ul>
                      </nav>
                      <div style={{ minWidth: 0 }}>
                        {(() => {
                          const blocks = detailModal.fileResults ?? [];
                          const selected = blocks.find((b) => b.filePath === detailFilePath) ?? blocks[0];
                          if (!selected) return null;
                          const gen = selected.generatedTests ?? [];
                          const ex = selected.executedTests ?? [];
                          const rows =
                            gen.length > 0 ? gen.map((t, ti) => ({ test: t, exec: ex[ti] })) : ex.map((row) => ({ test: undefined, exec: row }));
                          const passed = ex.filter((x) => (x.status ?? "").toUpperCase() === "PASSED").length;
                          const fileStrengths: string[] = [];
                          if (ex.length > 0) {
                            fileStrengths.push(`Пройдено тестов: ${passed}/${ex.length}.`);
                          }
                          if ((selected.findings ?? []).length === 0) {
                            fileStrengths.push("Для этого файла не найдено явных замечаний по коду.");
                          }
                          return (
                            <div style={{ display: "grid", gap: "0.65rem" }}>
                              <p className="muted" style={{ margin: 0 }}>
                                {selected.summary || "Результаты по файлу"}
                              </p>
                              {fileStrengths.length > 0 && (
                                <div className="card" style={{ margin: 0 }}>
                                  <h4 style={{ margin: "0 0 0.4rem", fontSize: "0.95rem" }}>Сильные стороны файла</h4>
                                  <ul style={{ margin: 0 }}>
                                    {fileStrengths.map((s, i) => (
                                      <li key={`file-strength-${i}`}>{s}</li>
                                    ))}
                                  </ul>
                                </div>
                              )}
                              <div className="card" style={{ margin: 0 }}>
                                <h4 style={{ margin: "0 0 0.4rem", fontSize: "0.95rem" }}>Замечания по файлу</h4>
                                {(selected.findings ?? []).length === 0 ? (
                                  <p className="muted" style={{ margin: 0 }}>
                                    Замечаний не найдено.
                                  </p>
                                ) : (
                                  <div style={{ display: "grid", gap: "0.55rem" }}>
                                    {(selected.findings ?? []).map((f, idx) => (
                                      <div
                                        key={`file-finding-${idx}`}
                                        style={{
                                          borderTop: idx === 0 ? "none" : "1px solid rgba(255,255,255,.08)",
                                          paddingTop: idx === 0 ? 0 : "0.45rem",
                                        }}
                                      >
                                        <div className="row" style={{ justifyContent: "space-between", flexWrap: "wrap", gap: "0.35rem" }}>
                                          <strong>{f.title || "Замечание"}</strong>
                                          <span className={`pill ${f.severity === "critical" ? "warn" : ""}`}>{f.severity}</span>
                                        </div>
                                        <p style={{ margin: "0.3rem 0 0" }}>{f.explanation}</p>
                                      </div>
                                    ))}
                                  </div>
                                )}
                              </div>
                              {rows.map((row, ti) => (
                                <SuggestedTestWithRunCard
                                  key={`f-row-${ti}-${row.exec?.name ?? row.test?.name ?? ti}`}
                                  test={row.test}
                                  index={ti}
                                  exec={row.exec}
                                />
                              ))}
                            </div>
                          );
                        })()}
                      </div>
                    </div>
                  )}
                </section>
              )}

              {detailModal.recommendations.length > 0 && (
                <section>
                  <h3 style={{ margin: "0 0 0.5rem", fontSize: "1rem" }}>Материалы для улучшения</h3>
                  <div style={{ display: "grid", gap: "0.5rem" }}>
                    {detailModal.recommendations.map((rec, ri) => (
                      <div key={`r-${ri}`} className="card" style={{ margin: 0 }}>
                        <strong>{rec.title}</strong>
                        <p style={{ margin: "0.35rem 0" }}>{rec.description}</p>
                        {rec.url && (
                          <a href={rec.url} target="_blank" rel="noreferrer">
                            {rec.url}
                          </a>
                        )}
                      </div>
                    ))}
                  </div>
                </section>
              )}
            </div>
            <div className="modal-footer">
              <div className="modal-actions">
                <button type="button" className="btn secondary" onClick={() => setDetailModal(null)}>
                  Закрыть
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function SuggestedTestWithRunCard({
  test,
  index,
  exec,
}: {
  test?: AiCheckGeneratedTest;
  index: number;
  exec?: AiCheckExecutedTest;
}) {
  const cmdLine = (test?.command ?? "").trim();
  const docker = (test?.dockerImage ?? "").trim();
  const out = exec ? truncateOut(exec.output, 12_000) : "";
  if (!test) {
    return (
      <div className="card" style={{ margin: 0 }}>
        <div className="row" style={{ justifyContent: "space-between", gap: "0.5rem", flexWrap: "wrap" }}>
          <strong>
            {index + 1}. {exec?.name || "Базовый прогон"}
          </strong>
          {exec && <span className={execStatusClass(exec.status)}>{execStatusLabel(exec.status)}</span>}
        </div>
        <p className="muted" style={{ margin: "0.35rem 0 0", fontSize: "0.88rem" }}>
          Сценарий от модели не задан — это системный прогон по типу проекта (сборка / проверка языка).
        </p>
        {exec ? (
          <div style={{ borderTop: "1px solid rgba(255,255,255,.08)", marginTop: "0.65rem", paddingTop: "0.65rem" }}>
            <p className="muted" style={{ margin: "0 0 0.35rem", fontSize: "0.85rem" }}>
              Прогон: {exec.language || "—"} · код выхода {exec.exitCode}
            </p>
            {exec.command ? (
              <code style={{ display: "block", fontSize: "0.78rem", marginBottom: "0.35rem", wordBreak: "break-all" }}>{exec.command}</code>
            ) : null}
            <details>
              <summary style={{ cursor: "pointer", fontWeight: 600 }}>Вывод выполнения</summary>
              <pre style={{ marginTop: "0.5rem", fontSize: "0.75rem", overflow: "auto", maxHeight: "min(320px, 42vh)", whiteSpace: "pre-wrap" }}>
                {out || "(нет вывода)"}
              </pre>
            </details>
          </div>
        ) : (
          <p className="muted" style={{ marginTop: "0.65rem", fontSize: "0.85rem", marginBottom: 0 }}>
            Результат прогона недоступен.
          </p>
        )}
      </div>
    );
  }
  return (
    <div className="card" style={{ margin: 0 }}>
      <div className="row" style={{ justifyContent: "space-between", gap: "0.5rem", flexWrap: "wrap" }}>
        <strong>
          {index + 1}. {test.name || "Без названия"}
        </strong>
        <div className="row" style={{ gap: "0.35rem", flexWrap: "wrap", alignItems: "center" }}>
          <span className="pill">{test.kind}</span>
          {exec && <span className={execStatusClass(exec.status)}>{execStatusLabel(exec.status)}</span>}
        </div>
      </div>
      <p className="muted" style={{ margin: "0.35rem 0", fontSize: "0.88rem" }}>
        Цель: {test.purpose}
      </p>
      <p className="muted" style={{ margin: "0 0 0.35rem", fontSize: "0.88rem" }}>
        Объект: <code>{test.target}</code>
      </p>
      <p style={{ margin: "0 0 0.35rem" }}>
        <span className="muted">Ожидается:</span> {test.expectedResult}
      </p>
      {(docker || cmdLine) && (
        <p className="muted" style={{ margin: "0.35rem 0", fontSize: "0.82rem" }}>
          {docker && (
            <>
              Образ Docker: <code>{docker}</code>
              {cmdLine ? " · " : ""}
            </>
          )}
          {cmdLine && (
            <>
              Команда: <code style={{ wordBreak: "break-all" }}>{cmdLine}</code>
            </>
          )}
        </p>
      )}
      {(test.stdin ?? "").trim().length > 0 && (
        <p className="muted" style={{ margin: "0.25rem 0", fontSize: "0.8rem" }}>
          Stdin (задан моделью):{" "}
          <code style={{ wordBreak: "break-all", whiteSpace: "pre-wrap" }}>{(test.stdin ?? "").trim()}</code>
        </p>
      )}
      {(test.steps ?? []).length > 0 && (
        <>
          <div className="muted" style={{ fontSize: "0.85rem", marginBottom: "0.25rem" }}>
            Шаги (ручная проверка)
          </div>
          <ol style={{ margin: "0 0 0.5rem", paddingLeft: "1.25rem" }}>
            {(test.steps ?? []).map((st, si) => (
              <li key={`st-${si}`}>{st}</li>
            ))}
          </ol>
        </>
      )}

      {exec ? (
        <div style={{ borderTop: "1px solid rgba(255,255,255,.08)", marginTop: "0.65rem", paddingTop: "0.65rem" }}>
          <p className="muted" style={{ margin: "0 0 0.35rem", fontSize: "0.85rem" }}>
            Прогон: {exec.language || "—"} · код выхода {exec.exitCode}
          </p>
          {exec.command ? (
            <code style={{ display: "block", fontSize: "0.78rem", marginBottom: "0.35rem", wordBreak: "break-all" }}>{exec.command}</code>
          ) : null}
          <details>
            <summary style={{ cursor: "pointer", fontWeight: 600 }}>Вывод выполнения</summary>
            <pre style={{ marginTop: "0.5rem", fontSize: "0.75rem", overflow: "auto", maxHeight: "min(320px, 42vh)", whiteSpace: "pre-wrap" }}>
              {out || "(нет вывода)"}
            </pre>
          </details>
        </div>
      ) : (
        <p className="muted" style={{ marginTop: "0.65rem", fontSize: "0.85rem", marginBottom: 0 }}>
          Результат прогона недоступен (запись проверки из предыдущей версии или прогон не сохранился).
        </p>
      )}
    </div>
  );
}
