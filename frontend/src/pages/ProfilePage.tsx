import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { motion } from "framer-motion";
import { ApiError, updateProfile, uploadAvatar } from "../api";
import { useAuth } from "../context/AuthContext";
import { useThemeSettings, type UiTheme } from "../context/ThemeContext";
import { UserAvatar } from "../components/UserAvatar";

export function ProfilePage() {
  const { user, refreshUser } = useAuth();
  const { theme, setTheme, reduceMotion, setReduceMotion } = useThemeSettings();
  const [firstName, setFirstName] = useState(user?.firstName ?? "");
  const [lastName, setLastName] = useState(user?.lastName ?? "");
  const [middleName, setMiddleName] = useState(user?.middleName ?? "");
  const [bio, setBio] = useState(user?.bio ?? "");
  const [saving, setSaving] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [message, setMessage] = useState<{ type: "ok" | "error"; text: string } | null>(null);

  useEffect(() => {
    if (!user) {
      return;
    }
    setFirstName(user.firstName);
    setLastName(user.lastName);
    setMiddleName(user.middleName ?? "");
    setBio(user.bio ?? "");
  }, [user]);

  if (!user) {
    return null;
  }

  async function onSaveProfile(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setMessage(null);
    try {
      await updateProfile({
        firstName,
        lastName,
        middleName: middleName.trim() === "" ? null : middleName.trim(),
        bio: bio.trim() === "" ? null : bio.trim(),
      });
      await refreshUser();
      setMessage({ type: "ok", text: "Профиль сохранён." });
    } catch (err) {
      setMessage({
        type: "error",
        text: err instanceof ApiError ? err.message : "Не удалось сохранить.",
      });
    } finally {
      setSaving(false);
    }
  }

  async function onAvatarChange(file: File | null) {
    if (!file) {
      return;
    }
    setUploading(true);
    setMessage(null);
    try {
      await uploadAvatar(file);
      await refreshUser();
      setMessage({ type: "ok", text: "Фото профиля обновлено." });
    } catch (err) {
      setMessage({
        type: "error",
        text: err instanceof ApiError ? err.message : "Не удалось загрузить изображение.",
      });
    } finally {
      setUploading(false);
    }
  }

  const themeOptions: { id: UiTheme; label: string }[] = [
    { id: "system", label: "Как в системе" },
    { id: "light", label: "Светлая" },
    { id: "dark", label: "Тёмная" },
  ];

  return (
    <div className="profile-page apple-page">
      <motion.header
        className="profile-hero"
        initial={{ opacity: 0, y: 16 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.55, ease: [0.22, 1, 0.36, 1] }}
      >
        <p className="hero-kicker">Настройки</p>
        <h1 className="hero-title">Профиль</h1>
      </motion.header>

      {message && <div className={`flash ${message.type === "ok" ? "ok" : "error"}`}>{message.text}</div>}

      <div className="profile-grid">
        <motion.section
          className="glass-panel profile-card"
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.08, duration: 0.5, ease: [0.22, 1, 0.36, 1] }}
        >
          <div className="profile-avatar-block">
            <UserAvatar user={user} size={112} className="profile-avatar-large" />
            <label className="btn secondary avatar-upload-btn">
              {uploading ? "Загрузка…" : "Изменить фото"}
              <input
                type="file"
                accept="image/*"
                className="sr-only"
                disabled={uploading}
                onChange={(ev) => void onAvatarChange(ev.target.files?.[0] ?? null)}
              />
            </label>
          </div>
          <div className="profile-email-block">
            <p className="profile-email">{user.email}</p>
          </div>
        </motion.section>

        <motion.section
          className="glass-panel"
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.14, duration: 0.5, ease: [0.22, 1, 0.36, 1] }}
        >
          <h2 className="panel-title">Личные данные</h2>
          <form onSubmit={(e) => void onSaveProfile(e)}>
            <div className="field-grid">
              <div className="field">
                <label htmlFor="fn">Имя</label>
                <input id="fn" value={firstName} onChange={(e) => setFirstName(e.target.value)} autoComplete="given-name" placeholder="Имя" />
              </div>
              <div className="field">
                <label htmlFor="ln">Фамилия</label>
                <input id="ln" value={lastName} onChange={(e) => setLastName(e.target.value)} autoComplete="family-name" placeholder="Фамилия" />
              </div>
              <div className="field field--full">
                <label htmlFor="mn">Отчество</label>
                <input id="mn" value={middleName} onChange={(e) => setMiddleName(e.target.value)} autoComplete="additional-name" placeholder="Отчество" />
              </div>
              <div className="field field--full">
                <label htmlFor="bio">О себе</label>
                <textarea
                  id="bio"
                  value={bio}
                  onChange={(e) => setBio(e.target.value)}
                  placeholder="Кратко о себе — для карточки в кабинете."
                  rows={4}
                />
              </div>
            </div>
            <div className="profile-actions">
              <button type="submit" className="btn glow-btn" disabled={saving}>
                {saving ? "Сохранение…" : "Сохранить"}
              </button>
              <Link to="/" className="btn secondary">
                На главную
              </Link>
            </div>
          </form>
        </motion.section>

        <motion.section
          className="glass-panel"
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.2, duration: 0.5, ease: [0.22, 1, 0.36, 1] }}
        >
          <h2 className="panel-title">Интерфейс</h2>
          <div className="segmented" role="group" aria-label="Тема оформления">
            {themeOptions.map((opt) => (
              <button
                key={opt.id}
                type="button"
                className={`segmented-item ${theme === opt.id ? "is-active" : ""}`}
                onClick={() => setTheme(opt.id)}
              >
                {opt.label}
              </button>
            ))}
          </div>
          <label className="toggle-row">
            <input type="checkbox" checked={reduceMotion} onChange={(e) => setReduceMotion(e.target.checked)} />
            <span>Меньше анимации</span>
          </label>
        </motion.section>
      </div>
    </div>
  );
}
