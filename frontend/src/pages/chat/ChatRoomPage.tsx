import { Client } from "@stomp/stompjs";
import { useEffect, useMemo, useRef, useState } from "react";
import { Link, Navigate, useNavigate, useParams } from "react-router-dom";
import {
  ApiError,
  fetchMyRooms,
  fetchRoomParticipants,
  fetchReadReceipts,
  fetchRoomMessages,
  fetchRoomPresence,
  getAccessToken,
  markRead,
  normalizeChatMessage,
  sendRoomMessage,
  uploadFile,
} from "../../api";
import { useAuth } from "../../context/AuthContext";
import { useChatInbox } from "../../context/ChatInboxContext";
import type { ChatMessage, ChatReadReceipt, ChatRoom, UserProfile } from "../../types";
import { ChatMessageAttachments } from "./ChatMessageAttachments";
import { shouldShowReadReceipt } from "./chatReadLogic";
import { Modal } from "../../components/Modal";
import { GroupAvatar } from "../../components/GroupAvatar";

type PendingAttachment = { id: string; file: File; previewUrl: string | null };

const MAX_CHAT_ATTACHMENTS = 3;

function formatLastSeen(rawIso: string): string {
  const d = new Date(rawIso);
  if (Number.isNaN(d.getTime())) {
    return "не в сети";
  }
  const now = new Date();
  const diffMs = now.getTime() - d.getTime();
  if (diffMs <= 5 * 60 * 1000) {
    return "был(а) недавно";
  }

  const sameDay =
    d.getFullYear() === now.getFullYear() &&
    d.getMonth() === now.getMonth() &&
    d.getDate() === now.getDate();
  if (sameDay) {
    return `был(а) сегодня в ${d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}`;
  }

  const y = new Date(now);
  y.setDate(now.getDate() - 1);
  const yesterday =
    d.getFullYear() === y.getFullYear() &&
    d.getMonth() === y.getMonth() &&
    d.getDate() === y.getDate();
  if (yesterday) {
    return `был(а) вчера в ${d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}`;
  }

  return `был(а) ${d.toLocaleDateString([], { day: "2-digit", month: "2-digit" })} в ${d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}`;
}

export function ChatRoomPage() {
  const { roomId } = useParams<{ roomId: string }>();
  const navigate = useNavigate();
  const { user: me } = useAuth();
  const { refresh: refreshInbox } = useChatInbox();
  const [roomTitle, setRoomTitle] = useState<string>("Чат");
  const [roomAvatarKey, setRoomAvatarKey] = useState<string | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [receipts, setReceipts] = useState<ChatReadReceipt[]>([]);
  const [onlineIds, setOnlineIds] = useState<string[]>([]);
  const [lastSeenByUser, setLastSeenByUser] = useState<Record<string, string>>({});
  const [directPeerId, setDirectPeerId] = useState<string | null>(null);
  const [input, setInput] = useState("");
  const [pendingAttachments, setPendingAttachments] = useState<PendingAttachment[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  const [roomKind, setRoomKind] = useState<ChatRoom["roomType"]>("DIRECT");
  const [participantsOpen, setParticipantsOpen] = useState(false);
  const [participants, setParticipants] = useState<UserProfile[]>([]);
  const clientRef = useRef<Client | null>(null);
  const bottomRef = useRef<HTMLDivElement | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  /** Актуальный список черновых вложений (React state может отставать от клика «Отправить»). */
  const pendingRef = useRef<PendingAttachment[]>([]);
  const prevRoomIdRef = useRef<string | null>(null);
  const refreshInboxRef = useRef(refreshInbox);
  refreshInboxRef.current = refreshInbox;

  const selectedRoomId = roomId ?? null;

  useEffect(() => {
    if (!selectedRoomId) {
      return;
    }
    if (prevRoomIdRef.current === selectedRoomId) {
      return;
    }
    prevRoomIdRef.current = selectedRoomId;
    for (const p of pendingRef.current) {
      if (p.previewUrl) {
        URL.revokeObjectURL(p.previewUrl);
      }
    }
    pendingRef.current = [];
    setPendingAttachments([]);
    setInput("");
  }, [selectedRoomId]);

  useEffect(() => {
    if (!selectedRoomId) {
      return;
    }
    void fetchMyRooms()
      .then((list: ChatRoom[]) => {
        const r = list.find((x) => x.id === selectedRoomId);
        if (r) {
          setRoomTitle(r.title ?? r.roomType);
          setRoomKind(r.roomType);
          setRoomAvatarKey(r.counterpartAvatarFileKey ?? null);
          if (r.roomType === "DIRECT") {
            void fetchRoomParticipants(selectedRoomId)
              .then((list) => {
                const peer = list.find((u) => u.id !== (me?.id ?? ""));
                setDirectPeerId(peer?.id ?? null);
              })
              .catch(() => setDirectPeerId(null));
          } else {
            setDirectPeerId(null);
          }
        }
      })
      .catch(() => undefined);
  }, [selectedRoomId, me?.id]);

  useEffect(() => {
    if (!selectedRoomId) {
      return;
    }
    void Promise.all([fetchRoomMessages(selectedRoomId), fetchReadReceipts(selectedRoomId), fetchRoomPresence(selectedRoomId)])
      .then(([chatMessages, readReceipts, presence]) => {
        setMessages(chatMessages);
        setReceipts(readReceipts);
        setOnlineIds(presence.onlineUserIds);
        setLastSeenByUser(presence.lastSeenByUser ?? {});
        const last = chatMessages.at(-1);
        if (last) {
          void markRead(selectedRoomId, last.id)
            .then(() => refreshInboxRef.current())
            .catch(() => undefined);
        }
      })
      .catch((e) => setError(e instanceof ApiError ? e.message : "Не удалось загрузить историю"));
  }, [selectedRoomId]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages.length]);

  useEffect(() => {
    const token = getAccessToken();
    if (!token || !selectedRoomId) {
      return;
    }

    const protocol = window.location.protocol === "https:" ? "wss" : "ws";
    const client = new Client({
      brokerURL: `${protocol}://${window.location.host}/ws/chat?token=${encodeURIComponent(token)}`,
      reconnectDelay: 2000,
      onConnect: () => {
        client.subscribe(`/topic/rooms/${selectedRoomId}`, (frame) => {
          const raw = JSON.parse(frame.body) as Record<string, unknown>;
          const payload = normalizeChatMessage(raw as Partial<ChatMessage> & Record<string, unknown>);
          setMessages((prev) => (prev.some((x) => x.id === payload.id) ? prev : [...prev, payload]));
          void markRead(selectedRoomId, payload.id)
            .then(() => refreshInboxRef.current())
            .catch(() => undefined);
        });
        client.subscribe(`/topic/rooms/${selectedRoomId}/reads`, (frame) => {
          const payload = JSON.parse(frame.body) as ChatReadReceipt;
          setReceipts((prev) => [...prev.filter((r) => r.userId !== payload.userId), payload]);
        });
        client.subscribe(`/topic/rooms/${selectedRoomId}/presence`, (frame) => {
          const payload = JSON.parse(frame.body) as { roomId: string; onlineUserIds: string[] };
          setOnlineIds(payload.onlineUserIds);
        });
      },
    });
    client.activate();
    clientRef.current = client;
    return () => {
      void client.deactivate();
      clientRef.current = null;
    };
  }, [selectedRoomId]);

  function appendPendingFiles(fileList: FileList | null) {
    if (!fileList?.length) {
      return;
    }
    // Снимок файлов сразу: FileList «живой» — после e.target.value = "" в onChange он пустеет,
    // а React может вызвать этот updater позже, чем очистка инпута.
    const picked = Array.from(fileList);
    setPendingAttachments((prev) => {
      const next = [...prev];
      for (const file of picked) {
        if (next.length >= MAX_CHAT_ATTACHMENTS) {
          break;
        }
        const previewUrl = file.type.startsWith("image/") ? URL.createObjectURL(file) : null;
        const id =
          typeof crypto !== "undefined" && typeof crypto.randomUUID === "function"
            ? crypto.randomUUID()
            : `p-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`;
        next.push({ id, file, previewUrl });
      }
      pendingRef.current = next;
      return next;
    });
  }

  function removePendingAttachment(id: string) {
    setPendingAttachments((prev) => {
      const found = prev.find((p) => p.id === id);
      if (found?.previewUrl) {
        URL.revokeObjectURL(found.previewUrl);
      }
      const next = prev.filter((p) => p.id !== id);
      pendingRef.current = next;
      return next;
    });
  }

  async function submitMessage() {
    if (!selectedRoomId) {
      return;
    }
    const snapshot = [...pendingRef.current];
    const text = input.trim();
    if (!text && snapshot.length === 0) {
      return;
    }
    setError(null);
    setUploading(true);
    try {
      const uploaded: { fileKey: string; originalName: string; contentType: string | null }[] = [];
      for (const p of snapshot) {
        const up = await uploadFile(p.file, "chat");
        uploaded.push({
          fileKey: up.key,
          originalName: up.originalName,
          contentType: up.contentType,
        });
      }
      const sent = await sendRoomMessage(selectedRoomId, {
        content: text,
        attachments: uploaded,
      });
      setMessages((prev) => (prev.some((x) => x.id === sent.id) ? prev : [...prev, sent]));
      for (const p of snapshot) {
        if (p.previewUrl) {
          URL.revokeObjectURL(p.previewUrl);
        }
      }
      pendingRef.current = [];
      setPendingAttachments([]);
      setInput("");
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Не удалось отправить сообщение");
    } finally {
      setUploading(false);
      if (fileInputRef.current) {
        fileInputRef.current.value = "";
      }
    }
  }

  const myId = me?.id ?? "";

  const readFlags = useMemo(() => {
    if (!myId) {
      return new Map<string, boolean>();
    }
    const map = new Map<string, boolean>();
    for (const m of messages) {
      map.set(m.id, shouldShowReadReceipt(m, messages, receipts, myId));
    }
    return map;
  }, [messages, receipts, myId]);

  const directStatusText = useMemo(() => {
    if (roomKind !== "DIRECT") return null;
    if (!directPeerId) return "не в сети";
    if (onlineIds.includes(directPeerId)) return "в сети";
    const raw = lastSeenByUser[directPeerId];
    if (!raw) return "не в сети";
    return formatLastSeen(raw);
  }, [roomKind, directPeerId, onlineIds, lastSeenByUser]);

  if (!roomId) {
    return <Navigate to="/chat" replace />;
  }

  return (
    <div className="chat-room-page apple-page">
      <div className="chat-room-toolbar glass-panel">
        <button type="button" className="btn secondary chat-room-back" onClick={() => navigate("/chat")}>
          ← Все чаты
        </button>
        <GroupAvatar title={roomTitle} avatarFileKey={roomAvatarKey} size={40} className="chat-room-avatar-wrap" />
        <div className="chat-room-title-block">
          <h1 className="chat-room-title">{roomTitle}</h1>
          <p className="muted chat-room-meta">
            {roomKind === "DIRECT"
              ? directStatusText ?? "Личный чат"
              : `В сети: ${onlineIds.length} · ${roomKind === "GROUP" ? "Групповой чат" : "Канал курса"}`}
          </p>
        </div>
        {(roomKind === "GROUP" || roomKind === "COURSE") && (
          <button
            type="button"
            className="btn secondary"
            onClick={() => {
              if (!selectedRoomId) return;
              void fetchRoomParticipants(selectedRoomId)
                .then((list) => {
                  setParticipants(list);
                  setParticipantsOpen(true);
                })
                .catch(() => setError("Не удалось загрузить участников"));
            }}
          >
            Участники
          </button>
        )}
      </div>

      {error && <div className="flash error">{error}</div>}

      <div className="chat-room-panel glass-panel">
        <div className="chat-thread">
          {messages.map((m) => {
            const mine = myId && m.senderId === myId;
            return (
              <div key={m.id} className={`chat-row ${mine ? "chat-row--mine" : "chat-row--other"}`}>
                <div className={`chat-bubble ${mine ? "chat-bubble--mine" : "chat-bubble--other"}`}>
                  {!mine && (
                    <Link to={`/users/${m.senderId}`} className="chat-sender-link">
                      {m.senderName}
                    </Link>
                  )}
                  {m.content ? <p className="chat-bubble-text">{m.content}</p> : null}
                  <ChatMessageAttachments attachments={m.attachments} />
                  <div className="chat-bubble-meta">
                    <time className="chat-time">{new Date(m.createdAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}</time>
                    {mine && <span className={`chat-read ${readFlags.get(m.id) ? "chat-read--seen" : ""}`}>✓✓</span>}
                  </div>
                </div>
              </div>
            );
          })}
          <div ref={bottomRef} />
        </div>

        <div className="chat-compose-stack">
          {pendingAttachments.length > 0 && (
            <div className="chat-pending-strip">
              {pendingAttachments.map((p) => (
                <div key={p.id} className="chat-pending-tile">
                  {p.previewUrl ? (
                    <img src={p.previewUrl} alt="" className="chat-pending-thumb" />
                  ) : (
                    <span className="chat-pending-name">{p.file.name}</span>
                  )}
                  <button
                    type="button"
                    className="chat-pending-remove"
                    aria-label="Убрать вложение"
                    onClick={() => removePendingAttachment(p.id)}
                  >
                    ×
                  </button>
                </div>
              ))}
              <span className="muted chat-pending-hint">{pendingAttachments.length}/{MAX_CHAT_ATTACHMENTS} вложений</span>
            </div>
          )}
          <div className="chat-compose row">
            <input
              ref={fileInputRef}
              type="file"
              multiple
              className="chat-file-input"
              onChange={(e) => {
                appendPendingFiles(e.target.files);
                e.target.value = "";
              }}
            />
            <button
              type="button"
              className="btn secondary chat-file-btn"
              disabled={
                uploading || !selectedRoomId || pendingAttachments.length >= MAX_CHAT_ATTACHMENTS
              }
              onClick={() => fileInputRef.current?.click()}
            >
              Вложить
            </button>
            <input
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter" && !e.shiftKey) {
                  e.preventDefault();
                  void submitMessage();
                }
              }}
              placeholder="Текст сообщения…"
              className="chat-compose-input"
            />
            <button
              type="button"
              className="btn glow-btn"
              onClick={() => void submitMessage()}
              disabled={uploading || (!input.trim() && pendingAttachments.length === 0)}
            >
              {uploading ? "…" : "Отправить"}
            </button>
          </div>
        </div>
      </div>
      <Modal open={participantsOpen} title="Участники чата" onClose={() => setParticipantsOpen(false)}>
        {participants.map((p) => (
          <div key={p.id} className="chat-participant-row">
            <Link to={`/users/${p.id}`} className="chat-participant-link" onClick={() => setParticipantsOpen(false)}>
              {p.firstName} {p.lastName}
            </Link>
            <span className="muted">{p.email}</span>
          </div>
        ))}
        {participants.length === 0 && <p className="muted">Нет данных об участниках.</p>}
      </Modal>
    </div>
  );
}
