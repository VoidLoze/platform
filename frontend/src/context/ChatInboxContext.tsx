import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { useLocation } from "react-router-dom";
import { fetchMyRooms } from "../api";
import { useAuth } from "./AuthContext";
import type { ChatRoom } from "../types";

const POLL_MS = 15000;

export type ChatInboxContextValue = {
  rooms: ChatRoom[];
  totalUnread: number;
  refresh: () => Promise<void>;
};

const ChatInboxContext = createContext<ChatInboxContextValue | null>(null);

export function ChatInboxProvider({ children }: { children: React.ReactNode }) {
  const { user } = useAuth();
  const location = useLocation();
  const locationRef = useRef(location);
  locationRef.current = location;

  const [rooms, setRooms] = useState<ChatRoom[]>([]);
  const prevUnreadRef = useRef<Map<string, number>>(new Map());
  const seenFirstFetchRef = useRef(false);

  const load = useCallback(async () => {
    if (!user) {
      return;
    }
    try {
      const list = await fetchMyRooms();
      setRooms(list);

      const path = locationRef.current.pathname;
      const activeMatch = path.match(/^\/chat\/([^/]+)/);
      const activeRoomId = activeMatch?.[1] ?? null;
      const tabHidden = typeof document !== "undefined" && document.hidden;

      if (seenFirstFetchRef.current) {
        for (const room of list) {
          const prev = prevUnreadRef.current.get(room.id) ?? 0;
          const next = room.unreadCount ?? 0;
          if (next <= prev) {
            continue;
          }
          const delta = next - prev;
          if (room.id === activeRoomId && !tabHidden) {
            continue;
          }
          if (typeof Notification === "undefined" || Notification.permission !== "granted") {
            continue;
          }
          const label = room.title ?? "Чат";
          new Notification("Новое сообщение", {
            body: delta === 1 ? label : `${label}: ${delta} новых`,
            tag: `chat-room-${room.id}`,
          });
        }
      }

      prevUnreadRef.current = new Map(list.map((r) => [r.id, r.unreadCount ?? 0]));
      seenFirstFetchRef.current = true;
    } catch {
      /* polling errors ignored */
    }
  }, [user]);

  useEffect(() => {
    if (!user) {
      setRooms([]);
      prevUnreadRef.current = new Map();
      seenFirstFetchRef.current = false;
      return;
    }
    void load();
    const id = window.setInterval(() => void load(), POLL_MS);
    return () => window.clearInterval(id);
  }, [user, load]);

  useEffect(() => {
    if (!user || typeof Notification === "undefined") {
      return;
    }
    if (Notification.permission === "default") {
      void Notification.requestPermission();
    }
  }, [user]);

  const totalUnread = useMemo(
    () => rooms.reduce((sum, r) => sum + (r.unreadCount ?? 0), 0),
    [rooms],
  );

  const value = useMemo(
    () => ({
      rooms,
      totalUnread,
      refresh: load,
    }),
    [rooms, totalUnread, load],
  );

  return <ChatInboxContext.Provider value={value}>{children}</ChatInboxContext.Provider>;
}

export function useChatInbox(): ChatInboxContextValue {
  const ctx = useContext(ChatInboxContext);
  if (!ctx) {
    throw new Error("useChatInbox requires ChatInboxProvider");
  }
  return ctx;
}
