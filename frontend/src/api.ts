import type {
  Assignment,
  AssignmentMaterial,
  AssignmentTest,
  AssignmentTestQuestion,
  AuthTokens,
  CalendarTask,
  ChatPresence,
  ChatReadReceipt,
  ChatAttachment,
  ChatMessage,
  ChatRoom,
  Course,
  CourseProgress,
  GeneratedAssignmentDraft,
  GroupMaterial,
  GroupPost,
  AiCheckJob,
  AiCheckProgressEvent,
  AiCheckSettings,
  GradebookCourse,
  AssignmentSubmissionRosterRow,
  LabWorkDetail,
  LabWorkQueueItem,
  LabWorkSummary,
  UserProfile,
  RegistrationInviteSecrets,
  UserRole,
} from "./types";

const STORAGE_ACCESS = "platform_access";
const STORAGE_REFRESH = "platform_refresh";

export function getAccessToken(): string | null {
  return localStorage.getItem(STORAGE_ACCESS);
}

export function setTokens(tokens: AuthTokens): void {
  localStorage.setItem(STORAGE_ACCESS, tokens.accessToken);
  localStorage.setItem(STORAGE_REFRESH, tokens.refreshToken);
}

export function clearTokens(): void {
  localStorage.removeItem(STORAGE_ACCESS);
  localStorage.removeItem(STORAGE_REFRESH);
}

export function getRefreshToken(): string | null {
  return localStorage.getItem(STORAGE_REFRESH);
}

async function parseJson<T>(res: Response): Promise<T> {
  const text = await res.text();
  if (!text) {
    return undefined as T;
  }
  return JSON.parse(text) as T;
}

export class ApiError extends Error {
  status: number;
  body: unknown;

  constructor(message: string, status: number, body: unknown) {
    super(message);
    this.status = status;
    this.body = body;
  }
}

export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  if (!headers.has("Content-Type") && init.body && !(init.body instanceof FormData)) {
    headers.set("Content-Type", "application/json");
  }
  const token = getAccessToken();
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }
  const res = await fetch(path, { ...init, headers });
  if (res.status === 401 && getRefreshToken() && !path.includes("/auth/refresh")) {
    try {
      await refreshSession();
      const retryHeaders = new Headers(init.headers);
      if (!retryHeaders.has("Content-Type") && init.body && !(init.body instanceof FormData)) {
        retryHeaders.set("Content-Type", "application/json");
      }
      const t2 = getAccessToken();
      if (t2) {
        retryHeaders.set("Authorization", `Bearer ${t2}`);
      }
      const retry = await fetch(path, { ...init, headers: retryHeaders });
      return handleResponse<T>(retry);
    } catch {
      clearTokens();
    }
  }
  return handleResponse<T>(res);
}

export async function handleResponse<T>(res: Response): Promise<T> {
  const data = await parseJson<unknown>(res).catch(() => ({}));
  if (!res.ok) {
    let msg = res.statusText;
    if (typeof data === "object" && data !== null) {
      const d = data as { error?: string; message?: string; detail?: string };
      if (res.status === 500 && d.detail) {
        msg = String(d.detail);
      } else if (d.error) {
        msg = String(d.error);
      } else if (d.message) {
        msg = String(d.message);
      }
    }
    throw new ApiError(msg || "Request failed", res.status, data);
  }
  return data as T;
}

export async function refreshSession(): Promise<AuthTokens> {
  const refresh = getRefreshToken();
  if (!refresh) {
    throw new Error("No refresh token");
  }
  const res = await fetch("/api/v1/auth/refresh", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refreshToken: refresh }),
  });
  const tokens = await handleResponse<AuthTokens>(res);
  setTokens(tokens);
  return tokens;
}

export async function login(email: string, password: string): Promise<AuthTokens> {
  const res = await fetch("/api/v1/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  const tokens = await handleResponse<AuthTokens>(res);
  setTokens(tokens);
  return tokens;
}

export async function register(payload: {
  firstName: string;
  lastName: string;
  middleName?: string;
  email: string;
  password: string;
  role: string;
  /** Обязателен для ROLE_TEACHER и ROLE_ADMIN — секрет из настроек platform.registration.* */
  staffRegistrationSecret?: string | null;
}): Promise<UserProfile> {
  const res = await fetch("/api/v1/users/register", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  return handleResponse<UserProfile>(res);
}

/** Отдаёт файл текущего пользователя без слешей в URL (стабильно за nginx-proxy). */
export const MY_AVATAR_URL_PATH = "/api/v1/users/me/avatar";

function normalizeUserProfile(u: UserProfile): UserProfile {
  const r = u as unknown as Record<string, unknown>;
  const key = u.avatarFileKey ?? r.avatar_file_key;
  const bioRaw = u.bio ?? r.bio;
  return {
    ...u,
    avatarFileKey: typeof key === "string" ? key : key != null ? String(key) : null,
    bio: typeof bioRaw === "string" ? bioRaw : bioRaw != null ? String(bioRaw) : null,
  };
}

export async function fetchMe(): Promise<UserProfile> {
  const u = await apiFetch<UserProfile>("/api/v1/users/me");
  return normalizeUserProfile(u);
}

/** Загрузка файла по ключу (со слешами): только через query, иначе 400 после resolve и проблемы у прокси. */
export function fileKeyToUrlPath(fileKey: string): string {
  return `/api/v1/files/download?key=${encodeURIComponent(fileKey)}`;
}

/** Скачивание с JWT (нельзя открывать file URL в новой вкладке без заголовка Authorization). */
export async function fetchAuthenticatedFileBlob(fileKey: string): Promise<Blob> {
  const token = getAccessToken();
  const headers = new Headers();
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }
  const res = await fetch(fileKeyToUrlPath(fileKey), { headers });
  if (!res.ok) {
    throw new ApiError("Не удалось загрузить файл", res.status, await res.text());
  }
  return res.blob();
}

export async function updateProfile(body: {
  firstName?: string;
  lastName?: string;
  middleName?: string | null;
  bio?: string | null;
}): Promise<UserProfile> {
  const u = await apiFetch<UserProfile>("/api/v1/users/me", {
    method: "PATCH",
    body: JSON.stringify(body),
  });
  return normalizeUserProfile(u);
}

export async function uploadAvatar(file: File): Promise<UserProfile> {
  const form = new FormData();
  form.append("file", file);
  const u = await apiFetch<UserProfile>("/api/v1/users/me/avatar", { method: "POST", body: form });
  return normalizeUserProfile(u);
}

export async function fetchCourses(): Promise<Course[]> {
  return apiFetch<Course[]>("/api/v1/courses");
}

export async function fetchMyCourses(): Promise<Course[]> {
  return apiFetch<Course[]>("/api/v1/courses/mine");
}

export async function fetchCourse(id: string): Promise<Course> {
  return apiFetch<Course>(`/api/v1/courses/${id}`);
}

export async function updateGroupProfile(
  courseId: string,
  body: { title?: string; description?: string | null },
): Promise<Course> {
  return apiFetch<Course>(`/api/v1/courses/${courseId}/profile`, { method: "PATCH", body: JSON.stringify(body) });
}

export async function uploadGroupAvatar(courseId: string, file: File): Promise<Course> {
  const form = new FormData();
  form.append("file", file);
  return apiFetch<Course>(`/api/v1/courses/${courseId}/avatar`, { method: "POST", body: form });
}

export async function createCourse(title: string): Promise<Course> {
  return apiFetch<Course>("/api/v1/courses", { method: "POST", body: JSON.stringify({ title }) });
}

export async function searchUsers(q: string, role?: UserRole, limit = 20): Promise<UserProfile[]> {
  const params = new URLSearchParams({ q, limit: String(limit) });
  if (role) {
    params.set("role", role);
  }
  return apiFetch<UserProfile[]>(`/api/v1/users/search?${params.toString()}`);
}

export async function fetchGroupMembers(courseId: string): Promise<UserProfile[]> {
  return apiFetch<UserProfile[]>(`/api/v1/courses/${courseId}/members`);
}

/** Преподаватель назначает студента в группу (нет самозаписи). */
export async function assignStudentToGroup(courseId: string, studentId: string): Promise<Course> {
  return apiFetch<Course>(`/api/v1/courses/${courseId}/enroll/${studentId}`, { method: "POST" });
}

export async function fetchAssignments(courseId: string): Promise<Assignment[]> {
  return apiFetch<Assignment[]>(`/api/v1/courses/${courseId}/assignments`);
}

export async function fetchGroupPosts(courseId: string): Promise<GroupPost[]> {
  return apiFetch<GroupPost[]>(`/api/v1/courses/${courseId}/posts`);
}

export async function createGroupPost(
  courseId: string,
  body: { title?: string | null; body: string; pinned?: boolean; attachments?: Array<{ fileKey: string; originalName: string; contentType?: string | null; sizeBytes?: number | null }> },
): Promise<GroupPost> {
  return apiFetch<GroupPost>(`/api/v1/courses/${courseId}/posts`, { method: "POST", body: JSON.stringify(body) });
}

export async function deleteGroupPost(courseId: string, postId: string): Promise<void> {
  await apiFetch(`/api/v1/courses/${courseId}/posts/${postId}`, { method: "DELETE" });
}

export async function fetchGroupMaterials(courseId: string): Promise<GroupMaterial[]> {
  return apiFetch<GroupMaterial[]>(`/api/v1/courses/${courseId}/materials`);
}

export async function uploadGroupMaterial(
  courseId: string,
  file: File,
  title?: string,
  description?: string,
): Promise<GroupMaterial> {
  const form = new FormData();
  form.append("file", file);
  if (title?.trim()) form.append("title", title.trim());
  if (description?.trim()) form.append("description", description.trim());
  return apiFetch<GroupMaterial>(`/api/v1/courses/${courseId}/materials/upload`, { method: "POST", body: form });
}

export async function deleteGroupMaterial(courseId: string, materialId: string): Promise<void> {
  await apiFetch(`/api/v1/courses/${courseId}/materials/${materialId}`, { method: "DELETE" });
}

export async function createAssignment(
  courseId: string,
  body: {
    topicTitle: string;
    topicDescription?: string;
    subjectArea?: string;
    dueDate: string;
    allowLateSubmission: boolean;
    latePenaltyPercent: number;
    maxScore: number;
  },
): Promise<Assignment> {
  return apiFetch<Assignment>(`/api/v1/courses/${courseId}/assignments`, {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export async function deleteAssignment(courseId: string, assignmentId: string): Promise<void> {
  await apiFetch(`/api/v1/courses/${courseId}/assignments/${assignmentId}`, { method: "DELETE" });
}

export async function generateAssignmentDraft(
  courseId: string,
  body: { subjectArea: string; difficulty: string; learningGoals: string },
): Promise<GeneratedAssignmentDraft> {
  return apiFetch<GeneratedAssignmentDraft>(`/api/v1/courses/${courseId}/assignments/generate`, {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export async function submitLab(
  assignmentId: string,
  body: { textContent?: string | null; attachmentKey?: string | null; language?: string | null },
): Promise<{ id: string }> {
  return apiFetch(`/api/v1/assignments/${assignmentId}/submit`, {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export async function uploadFile(file: File, scope: string): Promise<{ key: string; originalName: string; contentType: string | null }> {
  const form = new FormData();
  form.append("file", file);
  form.append("scope", scope);
  let raw: Record<string, unknown>;
  try {
    raw = await apiFetch<Record<string, unknown>>("/api/v1/files/upload", { method: "POST", body: form });
  } catch (e) {
    if (e instanceof ApiError && e.status === 413) {
      throw new ApiError(
        "Файл слишком большой для загрузки (лимит сервера). Попробуйте архив поменьше или попросите администратора увеличить лимит.",
        413,
        e.body,
      );
    }
    throw e;
  }
  const key =
    typeof raw.key === "string"
      ? raw.key
      : typeof (raw as { fileKey?: string }).fileKey === "string"
        ? (raw as { fileKey: string }).fileKey
        : "";
  if (!key) {
    throw new ApiError("Сервер не вернул ключ файла", 500, raw);
  }
  const originalName =
    typeof raw.originalName === "string"
      ? raw.originalName
      : typeof (raw as { original_name?: string }).original_name === "string"
        ? (raw as { original_name: string }).original_name
        : file.name;
  const contentType =
    raw.contentType != null
      ? String(raw.contentType)
      : (raw as { content_type?: unknown }).content_type != null
        ? String((raw as { content_type: unknown }).content_type)
        : file.type || null;
  return { key, originalName, contentType: contentType || null };
}

export async function fetchMyLabs(): Promise<LabWorkSummary[]> {
  return apiFetch<LabWorkSummary[]>("/api/v1/labworks/my");
}

export async function fetchLabDetail(id: string): Promise<LabWorkDetail> {
  return apiFetch<LabWorkDetail>(`/api/v1/labworks/${id}`);
}

export async function submitTest(labWorkId: string, answers: Record<string, string>): Promise<unknown> {
  return apiFetch(`/api/v1/labworks/${labWorkId}/test`, {
    method: "POST",
    body: JSON.stringify(answers),
  });
}

export async function triggerReview(labWorkId: string): Promise<unknown> {
  return apiFetch(`/api/v1/assignments/labworks/${labWorkId}/review`, { method: "POST" });
}

/** Полная ИИ-проверка сдачи: текст, файл или оба (что есть). */
export async function reviewLabWorkWithAi(labWorkId: string): Promise<AiCheckJob> {
  return apiFetch<AiCheckJob>(`/api/v1/labworks/${labWorkId}/ai-review`, { method: "POST" });
}

export async function fetchLatestLabAiReview(labWorkId: string): Promise<AiCheckJob | null> {
  const token = getAccessToken();
  const headers = new Headers();
  if (token) headers.set("Authorization", `Bearer ${token}`);
  const res = await fetch(`/api/v1/labworks/${labWorkId}/ai-review/latest`, { headers });
  if (res.status === 204) return null;
  if (!res.ok) {
    const text = await res.text();
    throw new ApiError(text || "Не удалось загрузить последнюю проверку", res.status, text);
  }
  return res.json() as Promise<AiCheckJob>;
}

export async function deleteLabWork(labWorkId: string): Promise<void> {
  await apiFetch(`/api/v1/assignments/labworks/${labWorkId}`, { method: "DELETE" });
}

export async function createAiCheckJob(body: {
  title?: string | null;
  subject: string;
  sourceType: string;
  studentText?: string | null;
  attachmentFileKey?: string | null;
  attachmentFileKeys?: string[] | null;
  gitUrl?: string | null;
  customInstructions?: string | null;
  preferredProvider?: string | null;
  labWorkId?: string | null;
}): Promise<AiCheckJob> {
  return apiFetch<AiCheckJob>("/api/v1/ai-check/jobs", { method: "POST", body: JSON.stringify(body) });
}

export async function fetchMyAiCheckJobs(): Promise<AiCheckJob[]> {
  return apiFetch<AiCheckJob[]>("/api/v1/ai-check/jobs/mine");
}

export async function fetchAiCheckJob(jobId: string): Promise<AiCheckJob> {
  return apiFetch<AiCheckJob>(`/api/v1/ai-check/jobs/${jobId}`);
}

export async function fetchAiCheckProgressEvents(jobId: string): Promise<AiCheckProgressEvent[]> {
  return apiFetch<AiCheckProgressEvent[]>(`/api/v1/ai-check/jobs/${jobId}/events`);
}

function parseAiCheckSseDataBlocks(buffer: string): { emitted: AiCheckProgressEvent[]; remainder: string } {
  const blocks = buffer.split("\n\n");
  const remainder = blocks.pop() ?? "";
  const emitted: AiCheckProgressEvent[] = [];
  for (const block of blocks) {
    const dataLine = block
      .split("\n")
      .map((ln) => ln.trimEnd())
      .find((ln) => ln.startsWith("data:"));
    if (!dataLine) continue;
    const raw = dataLine.slice(5).trim();
    if (!raw) continue;
    try {
      emitted.push(JSON.parse(raw) as AiCheckProgressEvent);
    } catch {
      // ignore malformed chunk
    }
  }
  return { emitted, remainder };
}

/** SSE с Bearer; браузерный EventSource токен не шлёт, поэтому fetch + ReadableStream. */
export async function streamAiCheckProgress(
  jobId: string,
  afterId: number,
  onEvent: (ev: AiCheckProgressEvent) => void,
  signal: AbortSignal
): Promise<void> {
  const headers = new Headers();
  const token = getAccessToken();
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }
  const res = await fetch(`/api/v1/ai-check/jobs/${jobId}/stream?afterId=${encodeURIComponent(String(afterId))}`, {
    headers,
    signal,
  });
  if (!res.ok) {
    const text = await res.text();
    throw new ApiError(text || "Ошибка подключения к потоку прогресса", res.status, text);
  }
  const reader = res.body?.getReader();
  if (!reader) return;
  const dec = new TextDecoder();
  let buf = "";
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buf += dec.decode(value, { stream: true });
    const parsed = parseAiCheckSseDataBlocks(buf);
    buf = parsed.remainder;
    for (const ev of parsed.emitted) {
      onEvent(ev);
    }
  }
}

export async function fetchAiCheckSettings(): Promise<AiCheckSettings> {
  return apiFetch<AiCheckSettings>("/api/v1/ai-check/settings");
}

export async function updateAiCheckSettings(body: AiCheckSettings): Promise<AiCheckSettings> {
  return apiFetch<AiCheckSettings>("/api/v1/ai-check/settings", { method: "PATCH", body: JSON.stringify(body) });
}

export async function fetchAssignment(id: string): Promise<Assignment> {
  return apiFetch<Assignment>(`/api/v1/assignments/${id}`);
}

export async function fetchSubmissions(assignmentId: string): Promise<AssignmentSubmissionRosterRow[]> {
  return apiFetch<AssignmentSubmissionRosterRow[]>(`/api/v1/assignments/${assignmentId}/submissions`);
}

export async function uploadAssignmentMaterial(assignmentId: string, file: File): Promise<AssignmentMaterial> {
  const form = new FormData();
  form.append("file", file);
  return apiFetch<AssignmentMaterial>(`/api/v1/assignments/${assignmentId}/materials/upload`, { method: "POST", body: form });
}

export async function fetchAssignmentMaterials(assignmentId: string): Promise<AssignmentMaterial[]> {
  return apiFetch<AssignmentMaterial[]>(`/api/v1/assignments/${assignmentId}/materials`);
}

export async function generateAssignmentTestPreview(
  assignmentId: string,
  body: { questionCount: number; notes?: string | null },
): Promise<AssignmentTest> {
  return apiFetch<AssignmentTest>(`/api/v1/assignments/${assignmentId}/test/generate-preview`, {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export async function generateAssignmentTestPreviewFromDraft(body: {
  topicTitle: string;
  topicDescription?: string | null;
  subjectArea?: string | null;
  questionCount: number;
  notes?: string | null;
}): Promise<AssignmentTest> {
  return apiFetch<AssignmentTest>("/api/v1/assignments/test/generate-preview-from-draft", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export async function upsertAssignmentTest(
  assignmentId: string,
  body: { title: string; maxScore: number; questions: AssignmentTestQuestion[] },
): Promise<AssignmentTest> {
  return apiFetch<AssignmentTest>(`/api/v1/assignments/${assignmentId}/test`, { method: "POST", body: JSON.stringify(body) });
}

export async function fetchAssignmentTest(assignmentId: string): Promise<AssignmentTest> {
  return apiFetch<AssignmentTest>(`/api/v1/assignments/${assignmentId}/test`);
}

export async function submitAssignmentTest(
  assignmentId: string,
  answers: Record<string, string>,
): Promise<{ correct: number; total: number; score: number }> {
  return apiFetch(`/api/v1/assignments/${assignmentId}/test/submit`, { method: "POST", body: JSON.stringify(answers) });
}

export async function fetchMyAssignmentTestStatus(
  assignmentId: string,
): Promise<{ attempted: boolean; score: number | null }> {
  return apiFetch(`/api/v1/assignments/${assignmentId}/test/my-status`);
}

const MIME_TO_EXT: Record<string, string> = {
  "application/pdf": ".pdf",
  "application/msword": ".doc",
  "application/vnd.openxmlformats-officedocument.wordprocessingml.document": ".docx",
  "application/vnd.ms-excel": ".xls",
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": ".xlsx",
  "application/vnd.ms-powerpoint": ".ppt",
  "application/vnd.openxmlformats-officedocument.presentationml.presentation": ".pptx",
  "text/plain": ".txt",
  "text/csv": ".csv",
  "application/zip": ".zip",
  "application/x-zip-compressed": ".zip",
  "application/json": ".json",
  "image/jpeg": ".jpg",
  "image/png": ".png",
  "image/gif": ".gif",
  "image/webp": ".webp",
  "image/svg+xml": ".svg",
};

function extensionFromMime(mime: string | null | undefined): string | null {
  if (!mime) {
    return null;
  }
  const m = mime.split(";")[0].trim().toLowerCase();
  return MIME_TO_EXT[m] ?? null;
}

/** RFC 5987 filename* и обычный filename= из Content-Disposition */
function parseContentDispositionFilename(header: string | null): string | null {
  if (!header) {
    return null;
  }
  const utf = header.match(/filename\*=(?:UTF-8|utf-8)''([^;]+)/i);
  if (utf?.[1]) {
    try {
      return decodeURIComponent(utf[1].trim());
    } catch {
      /* ignore */
    }
  }
  const quoted = header.match(/filename\s*=\s*"((?:\\.|[^"\\])*)"/);
  if (quoted?.[1]) {
    return quoted[1].replace(/\\"/g, '"');
  }
  const plain = header.match(/filename\s*=\s*([^;]+)/i);
  if (plain?.[1]) {
    return plain[1].trim().replace(/^["']|["']$/g, "");
  }
  return null;
}

function sanitizeDownloadBasename(name: string): string {
  const s = name.replace(/[/\\?*:|"<>]/g, "_").replace(/\s+/g, " ").trim();
  const cut = s.slice(0, 220);
  return cut || "file";
}

function basenameLooksLikeFileWithExtension(name: string): boolean {
  const base = name.split(/[/\\]/).pop() ?? name;
  const dot = base.lastIndexOf(".");
  if (dot <= 0 || dot >= base.length - 1) {
    return false;
  }
  const ext = base.slice(dot + 1);
  return /^[a-z0-9]{1,8}$/i.test(ext);
}

function ensureDownloadFilename(
  base: string,
  blobType: string,
  responseContentType: string | null,
  hintContentType: string | null,
): string {
  const sanitized = sanitizeDownloadBasename(base);
  if (basenameLooksLikeFileWithExtension(sanitized)) {
    return sanitized;
  }
  const ext =
    extensionFromMime(blobType) ??
    extensionFromMime(responseContentType) ??
    extensionFromMime(hintContentType);
  return ext ? `${sanitized}${ext}` : sanitized;
}

function basenameFromFileKey(fileKey: string): string | null {
  const norm = fileKey.replace(/\\/g, "/").replace(/^\/+/, "");
  const seg = norm.split("/").filter(Boolean).at(-1);
  return seg ?? null;
}

/** Сохранение blob-URL на диск с заданным именем (в отличие от window.open). */
export function triggerBlobDownload(blobUrl: string, filename: string): void {
  const a = document.createElement("a");
  a.href = blobUrl;
  a.download = filename;
  a.rel = "noopener";
  document.body.appendChild(a);
  a.click();
  a.remove();
}

export async function downloadFileByKey(
  fileKey: string,
  options?: { suggestedFilename?: string | null; contentType?: string | null },
): Promise<{ url: string; filename: string }> {
  const token = getAccessToken();
  const headers = new Headers();
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }
  const res = await fetch(fileKeyToUrlPath(fileKey), { headers });
  if (!res.ok) {
    throw new ApiError("Не удалось скачать файл", res.status, await res.text());
  }
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const headerCt = res.headers.get("content-type")?.split(";")[0].trim() ?? null;
  const fromCd = parseContentDispositionFilename(res.headers.get("content-disposition"));
  const fromKey = basenameFromFileKey(fileKey);
  const suggested = options?.suggestedFilename?.trim();
  const rawBase =
    (suggested && sanitizeDownloadBasename(suggested)) ||
    (fromCd && sanitizeDownloadBasename(fromCd)) ||
    (fromKey && sanitizeDownloadBasename(fromKey)) ||
    "file";
  const filename = ensureDownloadFilename(rawBase, blob.type, headerCt, options?.contentType ?? null);
  return { url, filename };
}

export async function fetchAdminUsers(): Promise<UserProfile[]> {
  return apiFetch<UserProfile[]>("/api/v1/admin/users");
}

export async function fetchAdminRegistrationInvites(): Promise<RegistrationInviteSecrets> {
  return apiFetch<RegistrationInviteSecrets>("/api/v1/admin/registration-invites");
}

export async function generateAdminRegistrationInvites(
  scope: "teacher" | "admin" | "both",
  count: number,
): Promise<RegistrationInviteSecrets> {
  return apiFetch<RegistrationInviteSecrets>("/api/v1/admin/registration-invites/generate", {
    method: "POST",
    body: JSON.stringify({ scope, count }),
  });
}

export async function fetchStudentCalendar(): Promise<CalendarTask[]> {
  return apiFetch<CalendarTask[]>("/api/v1/calendar/mine");
}

export async function fetchTeacherCalendar(): Promise<CalendarTask[]> {
  return apiFetch<CalendarTask[]>("/api/v1/calendar/teacher");
}

export async function fetchTeacherProgress(): Promise<CourseProgress[]> {
  return apiFetch<CourseProgress[]>("/api/v1/progress/teacher");
}

export async function fetchMyRooms(): Promise<ChatRoom[]> {
  return apiFetch<ChatRoom[]>("/api/v1/chat/rooms");
}

export async function createDirectRoom(secondUserId: string): Promise<ChatRoom> {
  return apiFetch<ChatRoom>("/api/v1/chat/rooms/direct", {
    method: "POST",
    body: JSON.stringify({ secondUserId }),
  });
}

export async function createGroupRoom(title: string, participantIds: string[]): Promise<ChatRoom> {
  return apiFetch<ChatRoom>("/api/v1/chat/rooms/group", {
    method: "POST",
    body: JSON.stringify({ title, participantIds }),
  });
}

export async function createCourseRoom(courseId: string): Promise<ChatRoom> {
  return apiFetch<ChatRoom>("/api/v1/chat/rooms/course", {
    method: "POST",
    body: JSON.stringify({ courseId }),
  });
}

export async function deleteChatRoom(roomId: string): Promise<void> {
  await apiFetch(`/api/v1/chat/rooms/${roomId}`, { method: "DELETE" });
}

/** Приводим ответ API / WS к единому виду с массивом вложений. */
export function normalizeChatMessage(raw: Partial<ChatMessage> & Record<string, unknown>): ChatMessage {
  let atts: ChatAttachment[] = [];
  const rawAtts = raw.attachments;
  if (Array.isArray(rawAtts)) {
    atts = rawAtts
      .map((a) => {
        const x = a as Record<string, unknown>;
        return {
          fileKey: String(x.fileKey ?? x.file_key ?? ""),
          originalName:
            x.originalName != null
              ? String(x.originalName)
              : x.original_name != null
                ? String(x.original_name)
                : null,
          contentType:
            x.contentType != null ? String(x.contentType) : x.content_type != null ? String(x.content_type) : null,
        };
      })
      .filter((x) => x.fileKey.length > 0);
  }
  return {
    id: String(raw.id),
    roomId: String(raw.roomId),
    senderId: String(raw.senderId),
    senderName: String(raw.senderName ?? ""),
    content: String(raw.content ?? ""),
    createdAt: String(raw.createdAt ?? ""),
    attachments: atts,
  };
}

export async function fetchRoomMessages(roomId: string): Promise<ChatMessage[]> {
  const list = await apiFetch<ChatMessage[]>(`/api/v1/chat/rooms/${roomId}/messages`);
  return list.map(normalizeChatMessage);
}

export async function sendRoomMessage(
  roomId: string,
  body: {
    content: string;
    attachments?: ChatAttachment[];
  },
): Promise<ChatMessage> {
  const raw = await apiFetch<Record<string, unknown>>(`/api/v1/chat/rooms/${roomId}/messages`, {
    method: "POST",
    body: JSON.stringify({
      content: body.content ?? "",
      attachments: (body.attachments ?? []).map((a) => ({
        fileKey: a.fileKey,
        originalName: a.originalName ?? null,
        contentType: a.contentType ?? null,
      })),
    }),
  });
  if (!raw || typeof raw.id !== "string") {
    throw new ApiError("Сервер вернул некорректный ответ при отправке сообщения", 500, raw);
  }
  return normalizeChatMessage(raw as Partial<ChatMessage> & Record<string, unknown>);
}

export async function markRead(roomId: string, messageId: string): Promise<ChatReadReceipt> {
  return apiFetch<ChatReadReceipt>(`/api/v1/chat/rooms/${roomId}/read`, {
    method: "POST",
    body: JSON.stringify({ messageId }),
  });
}

export async function fetchReadReceipts(roomId: string): Promise<ChatReadReceipt[]> {
  return apiFetch<ChatReadReceipt[]>(`/api/v1/chat/rooms/${roomId}/receipts`);
}

export async function fetchRoomPresence(roomId: string): Promise<ChatPresence> {
  return apiFetch<ChatPresence>(`/api/v1/chat/rooms/${roomId}/presence`);
}

export async function fetchRoomParticipants(roomId: string): Promise<UserProfile[]> {
  return apiFetch<UserProfile[]>(`/api/v1/chat/rooms/${roomId}/participants`);
}

export async function fetchUsers(): Promise<UserProfile[]> {
  return apiFetch<UserProfile[]>("/api/v1/users");
}

export async function fetchGradebook(courseId: string): Promise<GradebookCourse> {
  return apiFetch<GradebookCourse>(`/api/v1/gradebook/courses/${courseId}`);
}

export async function setManualGrade(labWorkId: string, gradeValue: number) {
  return apiFetch(`/api/v1/gradebook/labworks/${labWorkId}/grade`, {
    method: "PATCH",
    body: JSON.stringify({ gradeValue }),
  });
}
