import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";

const STORAGE_KEY = "platform_ui_prefs_v1";

export type UiTheme = "light" | "dark" | "system";

type Prefs = {
  theme: UiTheme;
  reduceMotion: boolean;
};

function defaultPrefs(): Prefs {
  return { theme: "system", reduceMotion: false };
}

function loadPrefs(): Prefs {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) {
      return { ...defaultPrefs(), ...JSON.parse(raw) };
    }
  } catch {
    /* ignore */
  }
  return defaultPrefs();
}

function resolveTheme(theme: UiTheme): "light" | "dark" {
  if (theme === "system") {
    return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
  }
  return theme;
}

type ThemeState = {
  theme: UiTheme;
  effectiveTheme: "light" | "dark";
  reduceMotion: boolean;
  setTheme: (t: UiTheme) => void;
  setReduceMotion: (v: boolean) => void;
};

const ThemeContext = createContext<ThemeState | null>(null);

function applyDom(theme: UiTheme, reduceMotion: boolean) {
  const resolved = resolveTheme(theme);
  document.documentElement.dataset.theme = resolved;
  document.documentElement.classList.toggle("reduce-motion", reduceMotion);
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [prefs, setPrefs] = useState<Prefs>(() => {
    if (typeof window === "undefined") {
      return defaultPrefs();
    }
    const p = loadPrefs();
    applyDom(p.theme, p.reduceMotion);
    return p;
  });

  const effectiveTheme = useMemo(() => resolveTheme(prefs.theme), [prefs.theme]);

  useEffect(() => {
    applyDom(prefs.theme, prefs.reduceMotion);
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(prefs));
    } catch {
      /* ignore */
    }
  }, [prefs]);

  useEffect(() => {
    if (prefs.theme !== "system") {
      return;
    }
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    const onChange = () => applyDom("system", prefs.reduceMotion);
    mq.addEventListener("change", onChange);
    return () => mq.removeEventListener("change", onChange);
  }, [prefs.theme, prefs.reduceMotion]);

  const setTheme = useCallback((theme: UiTheme) => {
    setPrefs((p) => ({ ...p, theme }));
  }, []);

  const setReduceMotion = useCallback((reduceMotion: boolean) => {
    setPrefs((p) => ({ ...p, reduceMotion }));
  }, []);

  const value = useMemo(
    () => ({
      theme: prefs.theme,
      effectiveTheme,
      reduceMotion: prefs.reduceMotion,
      setTheme,
      setReduceMotion,
    }),
    [prefs.theme, prefs.reduceMotion, effectiveTheme, setTheme, setReduceMotion],
  );

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export function useThemeSettings(): ThemeState {
  const ctx = useContext(ThemeContext);
  if (!ctx) {
    throw new Error("useThemeSettings must be used within ThemeProvider");
  }
  return ctx;
}
