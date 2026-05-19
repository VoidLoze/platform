import { useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { ApiError, createCourse, updateGroupProfile, uploadGroupAvatar } from "../../api";
import { buildGroupDescription } from "../../utils/groupSubject";

export function TeacherCreateGroupPage() {
  const navigate = useNavigate();
  const [title, setTitle] = useState("");
  const [subject, setSubject] = useState("");
  const [description, setDescription] = useState("");
  const [avatarFile, setAvatarFile] = useState<File | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  async function onCreate(e: FormEvent) {
    e.preventDefault();
    if (!title.trim() || !subject.trim()) return;
    setPending(true);
    setError(null);
    try {
      const created = await createCourse(title.trim());
      await updateGroupProfile(created.id, { description: buildGroupDescription(subject, description) });
      if (avatarFile) {
        await uploadGroupAvatar(created.id, avatarFile);
      }
      navigate(`/teacher/groups/${created.id}`, { replace: true });
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Не удалось создать группу");
    } finally {
      setPending(false);
    }
  }

  return (
    <div>
      <p className="muted">
        <Link to="/teacher">← К панели преподавателя</Link>
      </p>
      <h1 className="page-title">Создание группы</h1>
      {error && <div className="flash error">{error}</div>}
      <div className="card premium-card">
        <form onSubmit={onCreate}>
          <div className="field">
            <label htmlFor="ctitle">Название</label>
            <input id="ctitle" value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Например: Операционные системы" />
          </div>
          <div className="field">
            <label htmlFor="csubject">Предмет группы</label>
            <input
              id="csubject"
              value={subject}
              onChange={(e) => setSubject(e.target.value)}
              placeholder="Например: Программирование на Java"
              required
            />
          </div>
          <div className="field">
            <label htmlFor="cdesc">Описание</label>
            <textarea
              id="cdesc"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="Кратко опишите группу, формат занятий и ожидания по курсу"
            />
          </div>
          <div className="field">
            <label htmlFor="cavatar">Аватар группы (опционально)</label>
            <input id="cavatar" type="file" accept="image/*" onChange={(e) => setAvatarFile(e.target.files?.[0] ?? null)} />
          </div>
          <button type="submit" className="btn" disabled={pending}>
            {pending ? "Создание…" : "Создать группу"}
          </button>
        </form>
      </div>
    </div>
  );
}
