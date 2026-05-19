import { useState, type FormEvent } from "react";
import { Link, Navigate, useNavigate } from "react-router-dom";
import { AnimatePresence, motion, useReducedMotion } from "framer-motion";
import { useAuth } from "../context/AuthContext";
import { ApiError } from "../api";

export function LoginPage() {
  const { user, login } = useAuth();
  const navigate = useNavigate();
  const reduceMotion = useReducedMotion();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  if (user) {
    return <Navigate to="/" replace />;
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setPending(true);
    try {
      await login(email, password);
      navigate("/", { replace: true });
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Ошибка входа");
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

  return (
    <div className="auth-page">
      <div className="auth-ambient" aria-hidden>
        <span className="auth-orb auth-orb--1" />
        <span className="auth-orb auth-orb--2" />
        <span className="auth-orb auth-orb--3" />
      </div>

      <motion.div className="auth-center" {...motionProps}>
        <header className="auth-header">
          <p className="auth-kicker">МИИТ Portal</p>
          <h1 className="auth-title">С возвращением</h1>
          <p className="auth-lead">Войдите, чтобы продолжить обучение</p>
        </header>

        <div className="auth-card auth-glass">
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
            <div className="auth-field">
              <label htmlFor="login-email">Email</label>
              <input
                id="login-email"
                type="email"
                autoComplete="username"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="you@university.edu"
                required
                className="auth-input"
              />
            </div>
            <div className="auth-field">
              <label htmlFor="login-password">Пароль</label>
              <input
                id="login-password"
                type="password"
                autoComplete="current-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="••••••••"
                required
                className="auth-input"
              />
            </div>
            <motion.button
              type="submit"
              className="auth-submit btn glow-btn"
              disabled={pending}
              whileTap={reduceMotion || pending ? undefined : { scale: 0.985 }}
            >
              {pending ? "Вход…" : "Войти"}
            </motion.button>
          </form>

          <p className="auth-footer-text">
            Нет аккаунта?{" "}
            <Link to="/register" className="auth-link">
              Зарегистрироваться
            </Link>
          </p>
        </div>
      </motion.div>
    </div>
  );
}
