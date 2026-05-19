export type UserRole = "ROLE_STUDENT" | "ROLE_TEACHER" | "ROLE_ADMIN";

export interface UserProfile {
  id: string;
  firstName: string;
  lastName: string;
  middleName: string | null;
  email: string;
  role: UserRole;
  avatarFileKey: string | null;
  bio: string | null;
}

/** Ответ GET /api/v1/admin/registration-invites */
export interface RegistrationInviteSecrets {
  source: string;
  teacherCodes: string[];
  adminCodes: string[];
  teacherCount: number;
  adminCount: number;
  persistedFile: string;
  persistedAbsolutePath: string;
}

export interface AuthTokens {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  userId: string;
  email: string;
  role: UserRole;
}

export interface Course {
  id: string;
  title: string;
  ownerId: string | null;
  enrolledCount: number;
  avatarFileKey?: string | null;
  description?: string | null;
}

export interface GroupPostAttachment {
  id: string;
  fileKey: string;
  originalName: string;
  contentType: string | null;
  sizeBytes: number | null;
}

export interface GroupPost {
  id: string;
  courseId: string;
  authorId: string | null;
  authorName: string;
  title: string | null;
  body: string;
  pinned: boolean;
  createdAt: string;
  updatedAt: string;
  attachments: GroupPostAttachment[];
}

export interface GroupMaterial {
  id: string;
  courseId: string;
  uploaderId: string;
  uploaderName: string;
  fileKey: string;
  originalName: string;
  contentType: string | null;
  sizeBytes: number | null;
  title: string | null;
  description: string | null;
  uploadedAt: string;
}

export interface Assignment {
  id: string;
  courseId: string;
  topicTitle: string;
  topicDescription: string | null;
  subjectArea: string | null;
  dueDate: string;
  allowLateSubmission: boolean;
  latePenaltyPercent: number;
  maxScore: number;
}

export interface AssignmentMaterial {
  id: string;
  fileKey: string;
  originalName: string;
  contentType: string | null;
  uploadedAt: string;
}

export interface GeneratedAssignmentDraft {
  topicTitle: string;
  topicDescription: string;
  subjectArea: string;
  recommendedMaxScore: number;
  recommendedLatePenaltyPercent: number;
}

export interface AssignmentTestQuestion {
  id: string;
  questionText: string;
  questionType: string;
  options: string[];
  correctAnswer: string | null;
  points: number;
}

export interface AssignmentTest {
  id: string | null;
  title: string;
  maxScore: number;
  questions: AssignmentTestQuestion[];
}

export type LabWorkStatus =
  | "DRAFT"
  | "SUBMITTED"
  | "IN_REVIEW"
  | "REVIEW_READY"
  | "TEST_REQUIRED"
  | "COMPLETED"
  | "FAILED";

export interface LabWorkSummary {
  id: string;
  assignmentId: string;
  assignmentTitle: string;
  status: LabWorkStatus;
  submissionTime: string | null;
  finalGradeValue: number | null;
  finalGradeLetter: string | null;
}

export interface AiCheckRecommendation {
  title: string;
  description: string;
  url: string;
  resourceType: string;
}

export interface AiCheckFinding {
  filePath: string;
  lineStart: number | null;
  lineEnd: number | null;
  severity: "critical" | "major" | "minor" | "info" | string;
  title: string;
  explanation: string;
  snippet: string;
}

export interface AiCheckGeneratedTest {
  testId?: string;
  name: string;
  kind: "unit" | "integration" | "e2e" | "manual" | string;
  purpose: string;
  target: string;
  scope?: "PROJECT" | "FILE" | string;
  filePath?: string;
  steps: string[];
  expectedResult: string;
  /** Команда для автозапуска в workspace (если модель задала). */
  command?: string;
  dockerImage?: string;
  /** Явный stdin или автособранный платформой из шагов (если есть). */
  stdin?: string;
}

export interface AiCheckExecutedTest {
  testId?: string;
  name: string;
  scope?: "PROJECT" | "FILE" | string;
  filePath?: string;
  language: string;
  command: string;
  status: "PASSED" | "FAILED" | "TIMEOUT" | string;
  exitCode: number;
  output: string;
}

export interface AiCheckScopedBlock {
  scope: "PROJECT" | "FILE" | string;
  filePath: string;
  summary: string;
  status: string;
  findings: AiCheckFinding[];
  generatedTests: AiCheckGeneratedTest[];
  executedTests: AiCheckExecutedTest[];
}

export interface AiCheckResult {
  id: string;
  provider: string;
  score: number;
  maxScore: number;
  summary: string;
  detailedFeedback: string;
  strengths: string[];
  issues: string[];
  findings: AiCheckFinding[];
  generatedTests: AiCheckGeneratedTest[];
  executedTests: AiCheckExecutedTest[];
  projectResult?: AiCheckScopedBlock | null;
  fileResults?: AiCheckScopedBlock[];
  recommendations: AiCheckRecommendation[];
  latencyMs: number;
  primary: boolean;
  createdAt: string;
}

export interface AiCheckJob {
  id: string;
  requesterId: string;
  labWorkId: string | null;
  subject: string;
  sourceType: string;
  title: string | null;
  selectedProvider: string | null;
  fallbackChain: string | null;
  status: "QUEUED" | "RUNNING" | "DONE" | "FAILED" | "CANCELLED";
  errorMessage: string | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  results: AiCheckResult[];
}

/** GET /api/v1/ai-check/jobs/:id/events — этапы прогресса (также SSE). */
export interface AiCheckProgressEvent {
  id: number;
  phase: string;
  detail: string | null;
  scope?: "PROJECT" | "FILE" | string;
  filePath?: string | null;
  testId?: string | null;
  createdAt: string;
}

export interface AiCheckSettings {
  defaultProvider: string;
  fallbackChain: string[];
  subjectPolicy: Record<string, string>;
}

export interface QuestionDto {
  id: string;
  text: string;
  difficulty: string | null;
  correctAnswer: string | null;
}

export interface AIReview {
  id: string;
  scoreValue: number;
  maxScore: number;
  summary: string | null;
  detailedFeedback: string | null;
  recommendations: string | null;
}

export interface GeneratedTest {
  id: string;
  maxScore: number;
  questions: QuestionDto[];
}

export interface LabWorkDetail {
  id: string;
  assignmentId: string;
  assignmentTitle: string;
  courseId: string;
  courseTitle: string;
  status: LabWorkStatus;
  textContent: string | null;
  attachmentKey: string | null;
  language: string | null;
  submissionTime: string | null;
  finalGradeValue: number | null;
  finalGradeLetter: string | null;
  aiReview: AIReview | null;
  generatedTest: GeneratedTest | null;
  /** Для преподавателя — ФИО студента */
  studentDisplayName?: string | null;
  assignmentMaxScore?: number;
  assignmentDescription?: string | null;
}

/** Журнал сдач по заданию: все ученики группы + последняя работа и тест */
export interface AssignmentSubmissionRosterRow {
  studentId: string;
  studentName: string;
  studentEmail: string;
  labWorkId: string | null;
  labStatus: string;
  labSubmittedAt: string | null;
  labFinalGrade: number | null;
  testScore: number | null;
  testTaken: boolean;
  labSubmissionCount: number;
  assignmentHasTest: boolean;
}

export interface LabWorkQueueItem {
  id: string;
  studentId: string;
  studentName: string;
  studentEmail: string;
  status: LabWorkStatus;
  submissionTime: string | null;
  finalGradeValue: number | null;
}

export type CalendarTaskStatus = "OPEN" | "SUBMITTED" | "OVERDUE";

export interface CalendarTask {
  id: string;
  assignmentId: string;
  courseId: string;
  courseTitle?: string | null;
  courseAvatarFileKey?: string | null;
  title: string;
  dueDate: string;
  status: CalendarTaskStatus;
}

export interface StudentProgress {
  studentId: string;
  studentName: string;
  totalSubmitted: number;
  gradedCount: number;
  averageGrade: number;
}

export interface CourseProgress {
  courseId: string;
  courseTitle: string;
  assignmentsCount: number;
  submissionsCount: number;
  averageGrade: number;
  students: StudentProgress[];
}

export type ChatRoomType = "DIRECT" | "GROUP" | "COURSE";

export interface ChatRoom {
  id: string;
  roomType: ChatRoomType;
  title: string | null;
  counterpartAvatarFileKey: string | null;
  courseId: string | null;
  createdAt: string;
  /** Сообщений от других участников, не учтённых как прочитанные */
  unreadCount: number;
}

export interface ChatAttachment {
  fileKey: string;
  originalName: string | null;
  contentType: string | null;
}

export interface ChatMessage {
  id: string;
  roomId: string;
  senderId: string;
  senderName: string;
  content: string;
  createdAt: string;
  attachments: ChatAttachment[];
}

export interface ChatReadReceipt {
  userId: string;
  userName: string;
  messageId: string;
  readAt: string;
}

export interface ChatPresence {
  roomId: string;
  onlineUserIds: string[];
  lastSeenByUser?: Record<string, string>;
}

export interface GradebookEntry {
  labWorkId: string;
  assignmentId: string;
  assignmentTitle: string;
  studentId: string;
  studentName: string;
  studentEmail: string;
  status: LabWorkStatus;
  submissionTime: string | null;
  finalGradeValue: number | null;
  finalGradeLetter: string | null;
}

export interface GradebookCourse {
  courseId: string;
  courseTitle: string;
  entries: GradebookEntry[];
}
