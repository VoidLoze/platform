import { useEffect, useMemo, useRef, useState } from "react";
import { fileKeyToUrlPath, getAccessToken, MY_AVATAR_URL_PATH } from "../api";
import { useAuth } from "../context/AuthContext";
import type { UserProfile } from "../types";

type Props = {
  user: Pick<UserProfile, "id" | "firstName" | "lastName" | "avatarFileKey">;
  size?: number;
  className?: string;
};

export function UserAvatar({ user, size = 36, className }: Props) {
  const { user: self } = useAuth();
  const [src, setSrc] = useState<string | null>(null);
  const blobUrlRef = useRef<string | null>(null);

  const fetchPath = useMemo(() => {
    if (!user.avatarFileKey) {
      return null;
    }
    if (self?.id === user.id) {
      return MY_AVATAR_URL_PATH;
    }
    return fileKeyToUrlPath(user.avatarFileKey);
  }, [user.avatarFileKey, user.id, self?.id]);

  useEffect(() => {
    if (!fetchPath) {
      setSrc(null);
      return;
    }
    let cancelled = false;
    if (blobUrlRef.current) {
      URL.revokeObjectURL(blobUrlRef.current);
      blobUrlRef.current = null;
    }

    void (async () => {
      try {
        const token = getAccessToken();
        const headers = new Headers();
        if (token) {
          headers.set("Authorization", `Bearer ${token}`);
        }
        const res = await fetch(fetchPath, { headers });
        if (!res.ok || cancelled) {
          return;
        }
        const blob = await res.blob();
        if (cancelled) {
          return;
        }
        const url = URL.createObjectURL(blob);
        blobUrlRef.current = url;
        setSrc(url);
      } catch {
        if (!cancelled) {
          setSrc(null);
        }
      }
    })();

    return () => {
      cancelled = true;
      setSrc(null);
      if (blobUrlRef.current) {
        URL.revokeObjectURL(blobUrlRef.current);
        blobUrlRef.current = null;
      }
    };
  }, [fetchPath]);

  const initials =
    `${user.firstName?.[0] ?? ""}${user.lastName?.[0] ?? ""}`.toUpperCase() || "?";

  return (
    <div
      className={`user-avatar ${className ?? ""}`}
      style={{ width: size, height: size, fontSize: Math.max(11, size * 0.34) }}
    >
      {src ? (
        <img src={src} alt="" width={size} height={size} className="user-avatar-img" />
      ) : (
        <span className="user-avatar-initials">{initials}</span>
      )}
    </div>
  );
}
