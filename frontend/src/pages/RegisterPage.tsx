import { useEffect, useState, type FormEvent } from "react";
import { Link, Navigate } from "react-router-dom";
import { AnimatePresence, motion, useReducedMotion } from "framer-motion";
import { useAuth } from "../context/AuthContext";
import { ApiError, register } from "../api";
import { RolePicker } from "../components/RolePicker";
import type { UserRole } from "../types";

export function RegisterPage() {
  const { user } = useAuth();
  const reduceMotion = useReducedMotion();
  const [firstName, setFirstName] = useState("");
  const [lastName, setLastName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [role, setRole] = useState<UserRole>("ROLE_STUDENT");
  const [staffSecret, setStaffSecret] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const [pending, setPending] = useState(false);

  if (user) {
    return <Navigate to="/" replace />;
  }

  useEffect(() => {
    if (role === "ROLE_STUDENT") {
      setStaffSecret("");
    }
  }, [role]);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (role !== "ROLE_STUDENT" && !staffSecret.trim()) {
      setError("Укажите код приглашения.");
      return;
    }
    setPending(true);
    try {
      await register({
        firstName,
        lastName,
        email,
        password,
        role,
        ...(role !== "ROLE_STUDENT" ? { staffRegistrationSecret: staffSecret.trim() } : {}),
      });
      setDone(true);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Ошибка регистрации");
    } finally {
      setPending(false);
    }
  }

  const motionProps = reduceMotion
    ? {}
    : {
        initial: { opacity: 0, y: 16 },
        animate: { opacity: 1, y: 0 },
        transition: { duration: 0.35, ease: [0.22, 1, 0.36, 1] as const },
      };

  if (done) {
    return (
      <div className="auth-page">
        <div className="auth-ambient" aria-hidden>
          <span className="auth-orb auth-orb--1" />
          <span className="auth-orb auth-orb--2" />
        </div>
        <motion.div className="auth-center auth-center--wide" {...motionProps}>
          <div className="auth-card auth-glass auth-card--success">
            <motion.div
              className="auth-success-icon"
              initial={reduceMotion ? false : { scale: 0.6, opacity: 0 }}
              animate={{ scale: 1, opacity: 1 }}
              transition={{ type: "spring", stiffness: 260, damping: 18 }}
              aria-hidden
            >
              ✓
            </motion.div>
            <h2 className="auth-success-title">Аккаунт создан</h2>
            <p className="auth-lead auth-lead--tight">Теперь можно войти с указанным email и паролем.</p>
            <Link to="/login" className="auth-submit btn glow-btn">
              На страницу входа
            </Link>
          </div>
        </motion.div>
      </div>
    );
  }

  return (
    <div className="auth-page">
      <div className="auth-ambient" aria-hidden>
        <span className="auth-orb auth-orb--1" />
        <span className="auth-orb auth-orb--2" />
        <span className="auth-orb auth-orb--3" />
      </div>

      <motion.div className="auth-center auth-center--wide" {...motionProps}>
        <header className="auth-header">
          <p className="auth-kicker">МИИТ Portal</p>
          <h1 className="auth-title">Новый аккаунт</h1>
          <p className="auth-lead">Заполните данные и выберите роль</p>
        </header>

        <div className="auth-card auth-glass auth-card--wide">
          <AnimatePresence initial={false}>
            {error ? (
              <motion.div
                key={error}
                className="auth-flash auth-flash--error"
                role="alert"
                initial={reduceMotion ? false : { opacity: 0, y: -6 }}
                animate={{ opacity: 1, y: 0 }}
                exit={reduceMotion ? undefined : { opacity: 0, y: -4 }}
                transition={{ duration: 0.2 }}
              >
                {error}
              </motion.div>
            ) : null}
          </AnimatePresence>

          <form className="auth-form" onSubmit={onSubmit}>
            <div className="auth-field-row">
              <div className="auth-field">
                <label htmlFor="reg-fn">Имя</label>
                <input
                  id="reg-fn"
                  value={firstName}
                  onChange={(e) => setFirstName(e.target.value)}
                  required
                  autoComplete="given-name"
                  className="auth-input"
                  placeholder="Иван"
                />
              </div>
              <div className="auth-field">
                <label htmlFor="reg-ln">Фамилия</label>
                <input
                  id="reg-ln"
                  value={lastName}
                  onChange={(e) => setLastName(e.target.value)}
                  required
                  autoComplete="family-name"
                  className="auth-input"
                />
              </div>
            </div>
            <div className="auth-field">
              <label htmlFor="reg-em">Email</label>
              <input
                id="reg-em"
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                required
                autoComplete="email"
                className="auth-input"
              />
            </div>
            <div className="auth-field">
              <label htmlFor="reg-pw">Пароль</label>
              <input
                id="reg-pw"
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
                minLength={6}
                autoComplete="new-password"
                className="auth-input"
              />
            </div>

            <div className="auth-field auth-field--roles">
              <p className="auth-field-label" id="reg-role-label">
                Роль в системе
              </p>
              <RolePicker value={role} onChange={setRole} disabled={pending} labelledBy="reg-role-label" />
            </div>

            <AnimatePresence initial={false}>
              {role !== "ROLE_STUDENT" ? (
                <motion.div
                  key="invite"
                  className="auth-invite-panel"
                  initial={reduceMotion ? false : { opacity: 0, y: -6 }}
                  animate={{ opacity: 1, y: 0 }}
                  exit={reduceMotion ? undefined : { opacity: 0, y: -4 }}
                  transition={{ duration: 0.2 }}
                >
                  <label htmlFor="reg-invite" className="auth-invite-label">
                    Код приглашения
                  </label>
                  <input
                    id="reg-invite"
                    type="password"
                    autoComplete="off"
                    value={staffSecret}
                    onChange={(e) => setStaffSecret(e.target.value)}
                    className="auth-input"
                    required
                    disabled={pending}
                  />
                </motion.div>
              ) : null}
            </AnimatePresence>

            <motion.button
              type="submit"
              className="auth-submit btn glow-btn"
              disabled={pending}
              whileTap={reduceMotion || pending ? undefined : { scale: 0.985 }}
            >
              {pending ? "Создание…" : "Зарегистрироваться"}
            </motion.button>
          </form>

          <p className="auth-footer-text">
            Уже есть аккаунт?{" "}
            <Link to="/login" className="auth-link">
              Войти
            </Link>
          </p>
        </div>
      </motion.div>
    </div>
  );
}
