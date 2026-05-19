import { useEffect, useRef, useState } from "react";
import { searchUsers } from "../api";
import type { UserProfile, UserRole } from "../types";

export function UserSearchInput({
  roleFilter,
  placeholder,
  onPick,
}: {
  roleFilter?: UserRole;
  placeholder?: string;
  onPick: (u: UserProfile) => void;
}) {
  const [q, setQ] = useState("");
  const [results, setResults] = useState<UserProfile[]>([]);
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const rootRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (timer.current) clearTimeout(timer.current);
    if (q.trim().length < 2) {
      setResults([]);
      setOpen(false);
      return;
    }
    timer.current = setTimeout(() => {
      setLoading(true);
      void searchUsers(q.trim(), roleFilter)
        .then((list) => {
          setResults(list);
          setOpen(list.length > 0);
        })
        .catch(() => setResults([]))
        .finally(() => setLoading(false));
    }, 280);
    return () => {
      if (timer.current) clearTimeout(timer.current);
    };
  }, [q, roleFilter]);

  useEffect(() => {
    function onDocClick(e: MouseEvent) {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    }
    document.addEventListener("click", onDocClick);
    return () => document.removeEventListener("click", onDocClick);
  }, []);

  return (
    <div className="user-search" ref={rootRef}>
      <input
        type="search"
        value={q}
        onChange={(e) => setQ(e.target.value)}
        onFocus={() => q.trim().length >= 2 && results.length > 0 && setOpen(true)}
        placeholder={placeholder ?? "Начните вводить имя или фамилию…"}
        autoComplete="off"
        aria-autocomplete="list"
        aria-busy={loading}
      />
      {open && results.length > 0 && (
        <ul className="user-search-dropdown" role="listbox">
          {results.map((u) => (
            <li key={u.id} role="option">
              <button
                type="button"
                className="user-search-item"
                onClick={() => {
                  onPick(u);
                  setQ("");
                  setResults([]);
                  setOpen(false);
                }}
              >
                <span className="user-search-name">
                  {u.firstName} {u.lastName}
                </span>
                <span className="muted user-search-email">{u.email}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
