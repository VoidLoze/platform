import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { GroupAvatar } from "../../components/GroupAvatar";
import { Modal } from "../../components/Modal";
import { UserSearchInput } from "../../components/UserSearchInput";
import {
  ApiError,
  assignStudentToGroup,
  createGroupPost,
  createAssignment,
  deleteAssignment,
  deleteGroupMaterial,
  deleteGroupPost,
  fetchAssignments,
  fetchCourse,
  fetchGroupMembers,
  fetchGroupMaterials,
  fetchGroupPosts,
  uploadAssignmentMaterial,
  updateGroupProfile,
  uploadGroupAvatar,
  uploadGroupMaterial,
} from "../../api";
import type { Assignment, Course, GroupMaterial, GroupPost, UserProfile } from "../../types";
import { buildGroupDescription, parseGroupMeta } from "../../utils/groupSubject";
import { isAssignmentPastDue } from "../../utils/assignmentDue";

type AssignmentDueFilter = "all" | "active" | "pastDue";

function defaultDue(): string {
  const d = new Date();
  d.setDate(d.getDate() + 7);
  return d.toISOString().slice(0, 16);
}

export function TeacherCoursePage() {
  const navigate = useNavigate();
  const { courseId } = useParams<{ courseId: string }>();
  const [searchParams] = useSearchParams();
  const [course, setCourse] = useState<Course | null>(null);
  const [assignments, setAssignments] = useState<Assignment[]>([]);
  const [members, setMembers] = useState<UserProfile[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [topicTitle, setTopicTitle] = useState("Лабораторная работа");
  const [topicDescription, setTopicDescription] = useState("");
  const [dueDate, setDueDate] = useState(defaultDue);
  const [maxScore, setMaxScore] = useState(100);
  const [latePenaltyPercent, setLatePenaltyPercent] = useState(10);
  const [pending, setPending] = useState(false);
  const [draftPending, setDraftPending] = useState(false);
  const [assignPending, setAssignPending] = useState(false);
  const [posts, setPosts] = useState<GroupPost[]>([]);
  const [materials, setMaterials] = useState<GroupMaterial[]>([]);
  const [postTitle, setPostTitle] = useState("");
  const [postBody, setPostBody] = useState("");
  const [materialFile, setMaterialFile] = useState<File | null>(null);
  const [activeTab, setActiveTab] = useState<"members" | "posts" | "materials" | "assignments" | "profile">("assignments");
  const [groupTitle, setGroupTitle] = useState("");
  const [groupSubject, setGroupSubject] = useState("");
  const [groupDescription, setGroupDescription] = useState("");
  const [avatarUploading, setAvatarUploading] = useState(false);
  const [generatorKind, setGeneratorKind] = useState<"assignment" | "test">("assignment");
  const [assignmentFile, setAssignmentFile] = useState<File | null>(null);
  const [testQuestionCount, setTestQuestionCount] = useState(8);
  const [genSuccess, setGenSuccess] = useState<string | null>(null);
  const [deleteModalOpen, setDeleteModalOpen] = useState(false);
  const [selectedAssignmentIds, setSelectedAssignmentIds] = useState<string[]>([]);
  const [deletingAssignments, setDeletingAssignments] = useState(false);
  const [assignmentDueFilter, setAssignmentDueFilter] = useState<AssignmentDueFilter>("all");
  const avatarInputRef = useRef<HTMLInputElement | null>(null);

  const pastDueCount = useMemo(() => assignments.filter((a) => isAssignmentPastDue(a.dueDate)).length, [assignments]);
  const filteredAssignments = useMemo(() => {
    return assignments.filter((a) => {
      const past = isAssignmentPastDue(a.dueDate);
      if (assignmentDueFilter === "pastDue") return past;
      if (assignmentDueFilter === "active") return !past;
      return true;
    });
  }, [assignments, assignmentDueFilter]);

  useEffect(() => {
    if (searchParams.get("due") === "past") {
      setAssignmentDueFilter("pastDue");
      setActiveTab("assignments");
    }
  }, [searchParams]);

  useEffect(() => {
    if (!courseId) {
      return;
    }
    void Promise.all([fetchCourse(courseId), fetchAssignments(courseId), fetchGroupMembers(courseId), fetchGroupPosts(courseId), fetchGroupMaterials(courseId)])
      .then(([c, a, m, p, gm]) => {
        setCourse(c);
        setAssignments(a);
        setMembers(m);
        setPosts(p);
        setMaterials(gm);
        const meta = parseGroupMeta(c.description);
        setGroupTitle(c.title);
        setGroupSubject(meta.subject);
        setGroupDescription(meta.description);
      })
      .catch((e) => setError(e instanceof ApiError ? e.message : "Ошибка загрузки"));
  }, [courseId]);

  async function onAddStudent(u: UserProfile) {
    if (!courseId) return;
    setAssignPending(true);
    setError(null);
    try {
      await assignStudentToGroup(courseId, u.id);
      setMembers(await fetchGroupMembers(courseId));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Не удалось добавить участника");
    } finally {
      setAssignPending(false);
    }
  }

  async function onPublishPost() {
    if (!courseId || !postBody.trim()) return;
    try {
      await createGroupPost(courseId, { title: postTitle || null, body: postBody.trim() });
      setPostTitle("");
      setPostBody("");
      setPosts(await fetchGroupPosts(courseId));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Не удалось опубликовать новость");
    }
  }

  async function onUploadGroupMaterial() {
    if (!courseId || !materialFile) return;
    try {
      await uploadGroupMaterial(courseId, materialFile);
      setMaterialFile(null);
      setMaterials(await fetchGroupMaterials(courseId));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Не удалось загрузить материал");
    }
  }

  async function onSaveProfile() {
    if (!courseId) return;
    try {
      const updated = await updateGroupProfile(courseId, {
        title: groupTitle,
        description: buildGroupDescription(groupSubject, groupDescription),
      });
      setCourse(updated);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Не удалось обновить группу");
    }
  }

  async function onUploadAvatar(file: File) {
    if (!courseId) return;
    setAvatarUploading(true);
    try {
      const updated = await uploadGroupAvatar(courseId, file);
      setCourse(updated);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Не удалось загрузить аватар");
    } finally {
      setAvatarUploading(false);
    }
  }

  async function onCreateAssignment(e: FormEvent) {
    e.preventDefault();
    if (!courseId) {
      return;
    }
    setPending(true);
    setError(null);
    try {
      const iso = new Date(dueDate).toISOString();
      const created = await createAssignment(courseId, {
        topicTitle,
        topicDescription: topicDescription || undefined,
        subjectArea: groupSubject.trim() || undefined,
        dueDate: iso,
        allowLateSubmission: true,
        latePenaltyPercent,
        maxScore,
      });
      if (assignmentFile) {
        await uploadAssignmentMaterial(created.id, assignmentFile);
      }
      const a = await fetchAssignments(courseId);
      setAssignments(a);
      setAssignmentFile(null);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Не удалось создать задание");
    } finally {
      setPending(false);
    }
  }

  async function onGenerateDraft() {
    if (!courseId) return;
    if (!topicTitle.trim()) {
      setError("Для генерации теста укажите тему занятия.");
      return;
    }
    setDraftPending(true);
    setError(null);
    setGenSuccess(null);
    try {
      const questionCount = Math.max(3, Math.min(30, Math.trunc(testQuestionCount)));
      sessionStorage.setItem(
        "assignment-test-draft:new",
        JSON.stringify({
          courseId,
          topicTitle: topicTitle.trim(),
          topicDescription: topicDescription.trim() || null,
          subjectArea: groupSubject.trim(),
          dueDate: new Date(dueDate).toISOString(),
          allowLateSubmission: true,
          latePenaltyPercent,
          maxScore,
          title: `Тест: ${topicTitle.trim()}`,
          questionCount,
          questions: [],
        }),
      );
      setGenSuccess("Открылся редактор теста. Задание будет создано только после нажатия «Сохранить тест».");
      navigate(`/teacher/assignments/new?questionCount=${questionCount}`);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Не удалось подготовить тест");
    } finally {
      setDraftPending(false);
    }
  }

  async function onDeleteSelectedAssignments() {
    if (!courseId || selectedAssignmentIds.length === 0) return;
    setDeletingAssignments(true);
    setError(null);
    try {
      await Promise.all(selectedAssignmentIds.map((assignmentId) => deleteAssignment(courseId, assignmentId)));
      setAssignments(await fetchAssignments(courseId));
      setSelectedAssignmentIds([]);
      setDeleteModalOpen(false);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Не удалось удалить выбранные работы");
    } finally {
      setDeletingAssignments(false);
    }
  }

  if (!courseId) {
    return null;
  }

  return (
    <div className="page-hero">
      <p className="muted">
        <Link to="/teacher">← К панели</Link>
      </p>
      <div className="hero-inner">
        <p className="hero-kicker">Команда</p>
        <div style={{ display: "flex", alignItems: "center", gap: "0.75rem" }}>
          <GroupAvatar title={course?.title ?? "Группа"} avatarFileKey={course?.avatarFileKey} size={54} />
          <h1 className="hero-title">{course?.title ?? "Группа"}</h1>
        </div>
      </div>
      <div className="row" style={{ marginBottom: "0.75rem" }}>
        <Link to={`/teacher/groups/${courseId}/gradebook`} className="btn secondary">
          Журнал
        </Link>
      </div>
      {error && <div className="flash error">{error}</div>}
      {genSuccess && <div className="flash ok">{genSuccess}</div>}

      <div className="group-tab-row">
        <button type="button" className={`btn secondary ${activeTab === "assignments" ? "active" : ""}`} onClick={() => setActiveTab("assignments")}>
          Задания
        </button>
        <button type="button" className={`btn secondary ${activeTab === "posts" ? "active" : ""}`} onClick={() => setActiveTab("posts")}>
          Новости
        </button>
        <button type="button" className={`btn secondary ${activeTab === "materials" ? "active" : ""}`} onClick={() => setActiveTab("materials")}>
          Материалы
        </button>
        <button type="button" className={`btn secondary ${activeTab === "members" ? "active" : ""}`} onClick={() => setActiveTab("members")}>
          Ученики
        </button>
        <button type="button" className={`btn secondary ${activeTab === "profile" ? "active" : ""}`} onClick={() => setActiveTab("profile")}>
          Профиль группы
        </button>
      </div>

      {activeTab === "members" && <div className="card premium-card">
        <h3>Участники группы</h3>
        <div className="field">
          <label>Добавить студента</label>
          <UserSearchInput roleFilter="ROLE_STUDENT" placeholder="Поиск студента…" onPick={(u) => void onAddStudent(u)} />
          {assignPending && <span className="muted small-print">Добавление…</span>}
        </div>
        <table className="table">
          <thead>
            <tr>
              <th>Имя</th>
              <th>Email</th>
            </tr>
          </thead>
          <tbody>
            {members.map((u) => (
              <tr key={u.id}>
                <td>
                  {u.firstName} {u.lastName}
                </td>
                <td className="muted">{u.email}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {members.length === 0 && <p className="muted">Пока никого не назначили — найдите студентов выше.</p>}
      </div>}

      {activeTab === "posts" && <div className="card premium-card">
        <h3>Лента новостей</h3>
        <div className="field">
          <label>Заголовок</label>
          <input value={postTitle} onChange={(e) => setPostTitle(e.target.value)} placeholder="Короткий заголовок" />
        </div>
        <div className="field">
          <label>Текст новости</label>
          <textarea value={postBody} onChange={(e) => setPostBody(e.target.value)} placeholder="Что изменилось в группе..." />
        </div>
        <button type="button" className="btn secondary" onClick={() => void onPublishPost()}>
          Опубликовать
        </button>
        {posts.map((p) => (
          <div key={p.id} className="card" style={{ marginTop: "0.75rem" }}>
            <strong>{p.title || "Новость"}</strong>
            <p>{p.body}</p>
            <p className="muted">
              {p.authorName} · {new Date(p.createdAt).toLocaleString()}
            </p>
            <button type="button" className="btn secondary" onClick={() => courseId && void deleteGroupPost(courseId, p.id).then(() => fetchGroupPosts(courseId).then(setPosts))}>
              Удалить
            </button>
          </div>
        ))}
      </div>}

      {activeTab === "materials" && <div className="card premium-card">
        <h3>Учебные материалы группы</h3>
        <div className="field">
          <label>Файл</label>
          <input type="file" onChange={(e) => setMaterialFile(e.target.files?.[0] ?? null)} />
        </div>
        <button type="button" className="btn secondary" onClick={() => void onUploadGroupMaterial()}>
          Загрузить материал
        </button>
        {materials.map((m) => (
          <div key={m.id} className="row" style={{ marginTop: "0.65rem", justifyContent: "space-between" }}>
            <span>{m.title || m.originalName}</span>
            <button type="button" className="btn secondary" onClick={() => courseId && void deleteGroupMaterial(courseId, m.id).then(() => fetchGroupMaterials(courseId).then(setMaterials))}>
              Удалить
            </button>
          </div>
        ))}
      </div>}

      {activeTab === "assignments" && <div className="card premium-card">
        <h3>Новое задание</h3>
        <form onSubmit={onCreateAssignment}>
          <p className="muted">Предмет группы: {groupSubject || "не задан (укажите во вкладке «Профиль группы»)"}</p>
          <div className="segmented" role="group" aria-label="Тип генерации">
            <button
              type="button"
              className={`segmented-item ${generatorKind === "assignment" ? "is-active" : ""}`}
              onClick={() => setGeneratorKind("assignment")}
            >
              Задание
            </button>
            <button
              type="button"
              className={`segmented-item ${generatorKind === "test" ? "is-active" : ""}`}
              onClick={() => setGeneratorKind("test")}
            >
              Тест
            </button>
          </div>
          <div className="field">
            <label>Тема</label>
            <input value={topicTitle} onChange={(e) => setTopicTitle(e.target.value)} placeholder="Название задания" required />
          </div>
          <div className="field">
            <label>Описание</label>
            <textarea value={topicDescription} onChange={(e) => setTopicDescription(e.target.value)} placeholder="Формулировка задания для студентов" />
          </div>
          {generatorKind === "assignment" && (
            <div className="field">
              <label>Файл к заданию (необязательно)</label>
              <input type="file" onChange={(e) => setAssignmentFile(e.target.files?.[0] ?? null)} />
            </div>
          )}
          {generatorKind === "test" && (
            <div className="field">
              <label>Количество вопросов в тесте</label>
              <input type="number" min={3} max={30} value={testQuestionCount} onChange={(e) => setTestQuestionCount(Number(e.target.value))} />
            </div>
          )}
          <div className="field">
            <label>Дедлайн</label>
            <input type="datetime-local" value={dueDate} onChange={(e) => setDueDate(e.target.value)} required />
          </div>
          <div className="field">
            <label>Макс. балл</label>
            <input type="number" value={maxScore} onChange={(e) => setMaxScore(Number(e.target.value))} min={1} />
          </div>
          <div className="field">
            <label>Штраф за просрочку (%)</label>
            <input
              type="number"
              value={latePenaltyPercent}
              onChange={(e) => setLatePenaltyPercent(Number(e.target.value))}
              min={0}
              max={100}
            />
          </div>
          {generatorKind === "assignment" && (
            <button type="submit" className="btn" disabled={pending}>
              {pending ? "Создание…" : "Добавить задание"}
            </button>
          )}
          {generatorKind === "test" && (
            <button type="button" className="btn secondary" disabled={draftPending} onClick={() => void onGenerateDraft()}>
              {draftPending ? "Подготовка…" : "Подготовить тест"}
            </button>
          )}
        </form>
      </div>}

      {activeTab === "assignments" && <div className="card premium-card">
        <div className="row" style={{ justifyContent: "space-between", alignItems: "center" }}>
          <h3 style={{ margin: 0 }}>Задания команды</h3>
          <button type="button" className="btn danger" onClick={() => setDeleteModalOpen(true)} disabled={assignments.length === 0}>
            Удалить работу
          </button>
        </div>
        <div className="segmented" role="group" aria-label="Фильтр по дедлайну" style={{ margin: "0.75rem 0" }}>
          <button
            type="button"
            className={`segmented-item ${assignmentDueFilter === "all" ? "is-active" : ""}`}
            onClick={() => setAssignmentDueFilter("all")}
          >
            Все ({assignments.length})
          </button>
          <button
            type="button"
            className={`segmented-item ${assignmentDueFilter === "pastDue" ? "is-active" : ""}`}
            onClick={() => setAssignmentDueFilter("pastDue")}
          >
            Дедлайн истёк ({pastDueCount})
          </button>
          <button
            type="button"
            className={`segmented-item ${assignmentDueFilter === "active" ? "is-active" : ""}`}
            onClick={() => setAssignmentDueFilter("active")}
          >
            Ещё открыты
          </button>
        </div>
        {assignmentDueFilter === "pastDue" && (
          <p className="muted" style={{ marginTop: 0 }}>
            По этим заданиям можно просматривать и проверять сдачи учеников.
          </p>
        )}
        <table className="table">
          <thead>
            <tr>
              <th>Тема</th>
              <th>Дедлайн</th>
              <th>Предмет</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {filteredAssignments.map((a) => {
              const past = isAssignmentPastDue(a.dueDate);
              return (
                <tr key={a.id} className={past ? "table-row-overdue" : undefined}>
                  <td>{a.topicTitle}</td>
                  <td>
                    {past && <span className="pill warn">истёк</span>}
                    <div className={past ? "muted" : undefined} style={{ fontSize: past ? "0.85rem" : undefined }}>
                      {new Date(a.dueDate).toLocaleString()}
                    </div>
                  </td>
                  <td>{a.subjectArea ?? "—"}</td>
                  <td>
                    <Link to={`/teacher/assignments/${a.id}`} className="btn secondary">
                      {past ? "Сдачи" : "Просмотр работ"}
                    </Link>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
        {filteredAssignments.length === 0 && (
          <p className="muted">
            {assignmentDueFilter === "pastDue"
              ? "В этой группе пока нет заданий с истёкшим дедлайном."
              : "Нет заданий по выбранному фильтру."}
          </p>
        )}
      </div>}

      <Modal
        open={deleteModalOpen}
        title="Удаление работ"
        onClose={() => {
          if (!deletingAssignments) setDeleteModalOpen(false);
        }}
        footer={
          <div className="modal-actions">
            <button type="button" className="btn secondary" disabled={deletingAssignments} onClick={() => setDeleteModalOpen(false)}>
              Отмена
            </button>
            <button
              type="button"
              className="btn danger"
              disabled={deletingAssignments || selectedAssignmentIds.length === 0}
              onClick={() => void onDeleteSelectedAssignments()}
            >
              {deletingAssignments ? "Удаление…" : "Удалить выбранные"}
            </button>
          </div>
        }
      >
        <p className="muted" style={{ marginTop: 0 }}>
          Выберите работы, которые нужно удалить из заданий команды.
        </p>
        {assignments.length === 0 && <p className="muted">Нет заданий для удаления.</p>}
        {assignments.map((a) => (
          <label key={a.id} className="checkbox-row">
            <input
              type="checkbox"
              checked={selectedAssignmentIds.includes(a.id)}
              onChange={(e) =>
                setSelectedAssignmentIds((prev) => (e.target.checked ? [...prev, a.id] : prev.filter((id) => id !== a.id)))
              }
            />
            <span>
              {a.topicTitle} · {new Date(a.dueDate).toLocaleString()}
            </span>
          </label>
        ))}
      </Modal>

      {activeTab === "profile" && <div className="card premium-card">
        <h3>Профиль группы</h3>
        <div className="field">
          <label>Название</label>
          <input value={groupTitle} onChange={(e) => setGroupTitle(e.target.value)} />
        </div>
        <div className="field">
          <label>Предмет группы</label>
          <input
            value={groupSubject}
            onChange={(e) => setGroupSubject(e.target.value)}
            placeholder="Например: Сети и телекоммуникации"
          />
        </div>
        <div className="field">
          <label>Текст описания</label>
          <textarea
            value={groupDescription}
            onChange={(e) => setGroupDescription(e.target.value)}
            placeholder="Опишите цели группы, формат и требования к работе студентов"
          />
        </div>
        <div className="field">
          <label>Аватар группы</label>
          <input
            ref={avatarInputRef}
            type="file"
            accept="image/*"
            className="chat-file-input"
            onChange={(e) => {
              const file = e.target.files?.[0];
              if (file) {
                void onUploadAvatar(file);
              }
              e.currentTarget.value = "";
            }}
          />
          <button
            type="button"
            className="btn secondary"
            disabled={avatarUploading}
            onClick={() => avatarInputRef.current?.click()}
          >
            {avatarUploading ? "Загрузка…" : "Выбрать и загрузить фото"}
          </button>
        </div>
        <button type="button" className="btn secondary" onClick={() => void onSaveProfile()}>
          Сохранить профиль
        </button>
      </div>}
    </div>
  );
}
