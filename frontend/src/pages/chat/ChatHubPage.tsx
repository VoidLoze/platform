import { useEffect, useMemo, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { ApiError, createDirectRoom, createGroupRoom, deleteChatRoom, fetchRoomMessages } from "../../api";
import { GroupAvatar } from "../../components/GroupAvatar";
import { Modal } from "../../components/Modal";
import { UserSearchInput } from "../../components/UserSearchInput";
import { useChatInbox } from "../../context/ChatInboxContext";
import type { UserProfile } from "../../types";

type RoomPreview = { text: string; senderName: string | null };

function roomTypeLabel(kind: string): string {
  if (kind === "DIRECT") return "Личный чат";
  if (kind === "GROUP") return "Группа";
  if (kind === "COURSE") return "Курс";
  return kind;
}

export function ChatHubPage() {
  const navigate = useNavigate();
  const { rooms, refresh } = useChatInbox();
  const [error, setError] = useState<string | null>(null);
  const [findOpen, setFindOpen] = useState(false);
  const [groupOpen, setGroupOpen] = useState(false);
  const [groupTitle, setGroupTitle] = useState("");
  const [groupPicks, setGroupPicks] = useState<UserProfile[]>([]);
  const [previews, setPreviews] = useState<Record<string, RoomPreview>>({});

  const isGroupRoom = useMemo(
    () =>
      Object.fromEntries(
        rooms.map((room) => [room.id, room.roomType === "GROUP" || room.roomType === "COURSE"]),
      ) as Record<string, boolean>,
    [rooms],
  );

  useEffect(() => {
    let cancelled = false;
    async function loadPreviews() {
      const entries = await Promise.all(
        rooms.map(async (room) => {
          try {
            const history = await fetchRoomMessages(room.id);
            const last = history.at(-1);
            if (!last) return [room.id, { text: "Нет сообщений", senderName: null }] as const;
            return [room.id, { text: last.content || "Вложение", senderName: last.senderName }] as const;
          } catch {
            return [room.id, { text: "—", senderName: null }] as const;
          }
        }),
      );
      if (!cancelled) {
        setPreviews(Object.fromEntries(entries));
      }
    }
    if (rooms.length > 0) {
      void loadPreviews();
    } else {
      setPreviews({});
    }
    return () => {
      cancelled = true;
    };
  }, [rooms]);

  async function onPickDirect(u: UserProfile) {
    setError(null);
    try {
      const room = await createDirectRoom(u.id);
      await refresh();
      setFindOpen(false);
      navigate(`/chat/${room.id}`);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Не удалось создать чат");
    }
  }

  function addToGroupPick(u: UserProfile) {
    setGroupPicks((prev) => (prev.some((x) => x.id === u.id) ? prev : [...prev, u]));
  }

  async function onDeleteRoom(roomId: string, title: string) {
    if (!window.confirm(`Удалить чат «${title}»? История будет удалена для всех участников.`)) {
      return;
    }
    setError(null);
    try {
      await deleteChatRoom(roomId);
      await refresh();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Не удалось удалить чат");
    }
  }

  async function onCreateGroup() {
    if (!groupTitle.trim() || groupPicks.length === 0) {
      return;
    }
    setError(null);
    try {
      const room = await createGroupRoom(
        groupTitle.trim(),
        groupPicks.map((p) => p.id),
      );
      await refresh();
      setGroupTitle("");
      setGroupPicks([]);
      setGroupOpen(false);
      navigate(`/chat/${room.id}`);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Не удалось создать группу");
    }
  }

  return (
    <div className="chat-hub apple-page">
      <header className="chat-hub-head glass-panel">
        <div>
          <p className="hero-kicker">Связь</p>
          <h1 className="hero-title">Чаты</h1>
        </div>
        <div className="chat-hub-actions">
          <button type="button" className="btn glow-btn" onClick={() => setFindOpen(true)}>
            Новый личный чат
          </button>
          <button type="button" className="btn secondary" onClick={() => setGroupOpen(true)}>
            Создать группу
          </button>
        </div>
      </header>

      {error && <div className="flash error">{error}</div>}

      <div className="chat-hub-list glass-panel">
        {rooms.length === 0 ? (
          <p className="muted chat-hub-empty">Пока нет диалогов. Создайте чат с помощью кнопок выше.</p>
        ) : (
          <ul className="chat-hub-rooms">
            {rooms.map((room) => (
              <li key={room.id} className="chat-hub-room-row">
                <Link to={`/chat/${room.id}`} className="chat-hub-room-link">
                  <GroupAvatar
                    title={room.title ?? room.roomType}
                    avatarFileKey={room.counterpartAvatarFileKey}
                    size={38}
                    className="chat-hub-room-avatar-imgwrap"
                  />
                  <span className="chat-hub-room-main">
                    <span className="chat-hub-room-title">{room.title ?? room.roomType}</span>
                      <span className="muted chat-hub-room-preview">
                        {isGroupRoom[room.id] && previews[room.id]?.senderName ? `${previews[room.id].senderName}: ` : ""}
                        {previews[room.id]?.text ?? "Нет сообщений"}
                      </span>
                      <span className="muted chat-hub-room-meta">{roomTypeLabel(room.roomType)}</span>
                  </span>
                  <span className="chat-hub-room-right">
                    {room.unreadCount > 0 ? (
                      <span className="chat-room-unread-badge" aria-label={`Непрочитано: ${room.unreadCount}`}>
                        {room.unreadCount > 99 ? "99+" : room.unreadCount}
                      </span>
                    ) : (
                      <span className="chat-hub-room-check" aria-hidden="true">
                        ✓
                      </span>
                    )}
                  </span>
                </Link>
                <button
                  type="button"
                  className="btn secondary chat-hub-delete"
                  aria-label="Удалить чат"
                  onClick={() => void onDeleteRoom(room.id, room.title ?? room.roomType)}
                >
                  Удалить
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      <Modal open={findOpen} title="Новый диалог" size="xlarge" onClose={() => setFindOpen(false)}>
        <div className="field">
          <label>Поиск по имени или фамилии</label>
          <UserSearchInput placeholder="Например: Иван…" onPick={(u) => void onPickDirect(u)} />
        </div>
      </Modal>

      <Modal
        open={groupOpen}
        title="Новая группа"
        size="xlarge"
        onClose={() => setGroupOpen(false)}
        footer={
          <div className="modal-actions">
            <button type="button" className="btn secondary" onClick={() => setGroupOpen(false)}>
              Отмена
            </button>
            <button type="button" className="btn glow-btn" onClick={() => void onCreateGroup()} disabled={!groupTitle.trim() || groupPicks.length === 0}>
              Создать и открыть
            </button>
          </div>
        }
      >
        <div className="field">
          <label>Название группы</label>
          <input value={groupTitle} onChange={(e) => setGroupTitle(e.target.value)} placeholder="Например: Проект А" />
        </div>
        <div className="field">
          <label>Участники</label>
          <UserSearchInput placeholder="Добавить по имени…" onPick={addToGroupPick} />
          <div className="chip-row">
            {groupPicks.map((u) => (
              <span key={u.id} className="chip">
                {u.firstName} {u.lastName}
                <button
                  type="button"
                  className="chip-remove"
                  aria-label="Убрать"
                  onClick={() => setGroupPicks((prev) => prev.filter((x) => x.id !== u.id))}
                >
                  ×
                </button>
              </span>
            ))}
          </div>
        </div>
      </Modal>
    </div>
  );
}
