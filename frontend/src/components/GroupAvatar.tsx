import { useEffect, useMemo, useRef, useState } from "react";
import { fileKeyToUrlPath, getAccessToken } from "../api";

type Props = {
  title: string;
  avatarFileKey?: string | null;
  size?: number;
  className?: string;
};

export function GroupAvatar({ title, avatarFileKey, size = 44, className }: Props) {
  const [src, setSrc] = useState<string | null>(null);
  const blobUrlRef = useRef<string | null>(null);

  const fetchPath = useMemo(() => {
    if (!avatarFileKey) return null;
    return fileKeyToUrlPath(avatarFileKey);
  }, [avatarFileKey]);

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
        if (token) headers.set("Authorization", `Bearer ${token}`);
        const res = await fetch(fetchPath, { headers });
        if (!res.ok || cancelled) return;
        const blob = await res.blob();
        if (cancelled) return;
        const url = URL.createObjectURL(blob);
        blobUrlRef.current = url;
        setSrc(url);
      } catch {
        if (!cancelled) setSrc(null);
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

  const initials = title
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((x) => x[0]?.toUpperCase() ?? "")
    .join("") || "?";

  return (
    <div className={`group-card-avatar ${className ?? ""}`} style={{ width: size, height: size, fontSize: Math.max(12, size * 0.35) }}>
      {src ? <img src={src} alt="" width={size} height={size} className="group-card-avatar-img" /> : initials}
    </div>
  );
}
