import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { fetchAssignments, fetchCourses } from "../api";
import { useAuth } from "./AuthContext";

export type AssignmentAlertItem = {
  assignmentId: string;
  courseId: string;
  courseTitle: string;
  assignmentTitle: string;
  dueDate: string;
};

export type AssignmentToast = {
  id: string;
  courseId: string;
  assignmentId: string;
  title: string;
  subtitle: string;
};

type AssignmentAlertsState = {
  unreadCount: number;
  unreadItems: AssignmentAlertItem[];
  toast: AssignmentToast | null;
  dismissToast: () => void;
  markAssignmentSeen: (courseId: string, assignmentId: string) => void;
  markAllSeen: () => void;
};

const AssignmentAlertsContext = createContext<AssignmentAlertsState | null>(null);

const STORAGE_KEY = "student_assignment_alerts_v1";

type Snapshot = {
  knownByCourse: Record<string, string[]>;
  unreadByCourse: Record<string, string[]>;
};

function loadSnapshot(): Snapshot {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return { knownByCourse: {}, unreadByCourse: {} };
    const parsed = JSON.parse(raw) as Snapshot;
    return {
      knownByCourse: parsed.knownByCourse ?? {},
      unreadByCourse: parsed.unreadByCourse ?? {},
    };
  } catch {
    return { knownByCourse: {}, unreadByCourse: {} };
  }
}

function saveSnapshot(snapshot: Snapshot): void {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(snapshot));
}

export function AssignmentAlertsProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  const [snapshot, setSnapshot] = useState<Snapshot>(() => loadSnapshot());
  const [unreadItems, setUnreadItems] = useState<AssignmentAlertItem[]>([]);
  const [toast, setToast] = useState<AssignmentToast | null>(null);
  const initDoneRef = useRef(false);
  const toastTimerRef = useRef<number | null>(null);

  const poll = useCallback(async () => {
    if (!user || user.role !== "ROLE_STUDENT") {
      return;
    }
    const courses = await fetchCourses();
    const courseTitleMap = new Map(courses.map((c) => [c.id, c.title] as const));
    const assignmentByCourse: Record<string, { id: string; topicTitle: string; dueDate: string }[]> = {};
    const nextKnown = { ...snapshot.knownByCourse };
    const nextUnread = { ...snapshot.unreadByCourse };
    for (const course of courses) {
      const list = await fetchAssignments(course.id);
      const ids = list.map((a) => a.id);
      assignmentByCourse[course.id] = list.map((a) => ({ id: a.id, topicTitle: a.topicTitle, dueDate: a.dueDate }));
      const known = new Set(nextKnown[course.id] ?? []);
      if (!initDoneRef.current) {
        // Первая инициализация: не создаем "ложные" новые уведомления на старых данных.
        nextKnown[course.id] = ids;
        nextUnread[course.id] = nextUnread[course.id] ?? [];
        continue;
      }
      const unread = new Set(nextUnread[course.id] ?? []);
      const newlyAddedForCourse: string[] = [];
      for (const id of ids) {
        if (!known.has(id)) {
          unread.add(id);
          newlyAddedForCourse.push(id);
        }
      }
      nextKnown[course.id] = ids;
      nextUnread[course.id] = [...unread];

      if (initDoneRef.current && newlyAddedForCourse.length > 0) {
        const newest = list.find((a) => a.id === newlyAddedForCourse[newlyAddedForCourse.length - 1]);
        if (newest) {
          if (toastTimerRef.current) {
            window.clearTimeout(toastTimerRef.current);
          }
          setToast({
            id: newest.id,
            courseId: course.id,
            assignmentId: newest.id,
            title: "Новое задание",
            subtitle: `${course.title}: ${newest.topicTitle}`,
          });
          toastTimerRef.current = window.setTimeout(() => setToast(null), 5000);
        }
      }
    }
    const next = { knownByCourse: nextKnown, unreadByCourse: nextUnread };
    setSnapshot(next);
    const nextItems: AssignmentAlertItem[] = [];
    for (const [courseId, ids] of Object.entries(nextUnread)) {
      if (ids.length === 0) continue;
      const byId = new Map((assignmentByCourse[courseId] ?? []).map((x) => [x.id, x] as const));
      for (const id of ids) {
        const a = byId.get(id);
        if (!a) continue;
        nextItems.push({
          assignmentId: id,
          courseId,
          courseTitle: courseTitleMap.get(courseId) ?? "Группа",
          assignmentTitle: a.topicTitle,
          dueDate: a.dueDate,
        });
      }
    }
    nextItems.sort((a, b) => new Date(b.dueDate).getTime() - new Date(a.dueDate).getTime());
    setUnreadItems(nextItems);
    saveSnapshot(next);
    initDoneRef.current = true;
  }, [snapshot.knownByCourse, snapshot.unreadByCourse, user]);

  useEffect(() => {
    if (!user || user.role !== "ROLE_STUDENT") {
      return;
    }
    initDoneRef.current = false;
    void poll();
    const timer = window.setInterval(() => {
      void poll();
    }, 30000);
    return () => {
      window.clearInterval(timer);
      if (toastTimerRef.current) {
        window.clearTimeout(toastTimerRef.current);
      }
    };
  }, [poll, user]);

  const markAssignmentSeen = useCallback((courseId: string, assignmentId: string) => {
    setSnapshot((prev) => {
      const knownSet = new Set(prev.knownByCourse[courseId] ?? []);
      knownSet.add(assignmentId);
      const nextKnown = { ...prev.knownByCourse, [courseId]: [...knownSet] };
      const nextUnread = {
        ...prev.unreadByCourse,
        [courseId]: (prev.unreadByCourse[courseId] ?? []).filter((id) => id !== assignmentId),
      };
      const next = { knownByCourse: nextKnown, unreadByCourse: nextUnread };
      saveSnapshot(next);
      return next;
    });
    setUnreadItems((prev) => prev.filter((item) => !(item.courseId === courseId && item.assignmentId === assignmentId)));
  }, []);

  const markAllSeen = useCallback(() => {
    setSnapshot((prev) => {
      const nextUnread: Record<string, string[]> = {};
      for (const courseId of Object.keys(prev.unreadByCourse)) {
        nextUnread[courseId] = [];
      }
      const next = { knownByCourse: prev.knownByCourse, unreadByCourse: nextUnread };
      saveSnapshot(next);
      return next;
    });
    setUnreadItems([]);
  }, []);

  const dismissToast = useCallback(() => {
    if (toastTimerRef.current) {
      window.clearTimeout(toastTimerRef.current);
      toastTimerRef.current = null;
    }
    setToast(null);
  }, []);

  const unreadCount = useMemo(
    () => Object.values(snapshot.unreadByCourse).reduce((acc, ids) => acc + ids.length, 0),
    [snapshot.unreadByCourse],
  );

  const value = useMemo(
    () => ({ unreadCount, unreadItems, toast, dismissToast, markAssignmentSeen, markAllSeen }),
    [unreadCount, unreadItems, toast, dismissToast, markAssignmentSeen, markAllSeen],
  );
  return <AssignmentAlertsContext.Provider value={value}>{children}</AssignmentAlertsContext.Provider>;
}

export function useAssignmentAlerts(): AssignmentAlertsState {
  const ctx = useContext(AssignmentAlertsContext);
  if (!ctx) {
    throw new Error("useAssignmentAlerts must be used within AssignmentAlertsProvider");
  }
  return ctx;
}
