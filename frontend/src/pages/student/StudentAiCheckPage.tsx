import { motion } from "framer-motion";
import { Link } from "react-router-dom";
import { useAuth } from "../../context/AuthContext";

const easeApple = [0.22, 1, 0.36, 1] as const;
const MotionLink = motion(Link);

export function StudentAiCheckPage() {
  const { user } = useAuth();
  const base = user?.role === "ROLE_TEACHER" ? "/teacher" : "/student";
  return (
    <div>
      <h1 className="page-title">AI-проверка</h1>
      <p className="muted">Выберите формат проверки и сразу переходите к нужному сценарию.</p>
      <motion.div
        className="card"
        initial={{ opacity: 0, y: 12 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.3, ease: easeApple }}
        style={{
          background:
            "linear-gradient(155deg, rgba(46,80,255,0.08) 0%, rgba(126,87,194,0.08) 45%, rgba(0,188,212,0.06) 100%)",
          border: "1px solid rgba(114, 137, 255, 0.22)",
        }}
      >
        <div className="row" style={{ justifyContent: "space-between", alignItems: "flex-start", marginBottom: "0.5rem" }}>
          <div>
            <h3 style={{ marginBottom: "0.35rem" }}>Выберите тип проверки</h3>
            <p className="muted" style={{ margin: 0 }}>
              Можно переключаться между режимами в любое время.
            </p>
          </div>
          <span className="pill">2 режима</span>
        </div>
        <div className="group-grid">
          <MotionLink
            to={`${base}/ai-check/code`}
            className="group-card"
            whileHover={{ y: -4, scale: 1.01 }}
            whileTap={{ scale: 0.995 }}
            transition={{ duration: 0.18, ease: easeApple }}
            style={{
              border: "1px solid rgba(73, 111, 255, 0.28)",
              background: "linear-gradient(180deg, rgba(73,111,255,0.12), rgba(73,111,255,0.04))",
              boxShadow: "0 8px 20px rgba(73,111,255,0.14)",
            }}
          >
            <span className="group-card-avatar" style={{ boxShadow: "0 0 0 6px rgba(73,111,255,0.12)" }}>
              {"</>"}
            </span>
            <div>
              <div className="group-card-title">Проверка программного кода</div>
              <div className="row" style={{ gap: "0.35rem", margin: "0.25rem 0 0.1rem" }}>
                <span className="pill">Git</span>
                <span className="pill">Архив</span>
                <span className="pill">Snippet</span>
              </div>
              <p className="muted" style={{ margin: "0.2rem 0 0" }}>
                Найдёт баги, подскажет улучшения архитектуры и проверит результаты runtime-тестов.
              </p>
              <motion.div
                className="muted"
                initial={{ x: 0, opacity: 0.85 }}
                whileHover={{ x: 4, opacity: 1 }}
                transition={{ duration: 0.16, ease: easeApple }}
                style={{ marginTop: "0.55rem", fontWeight: 700 }}
              >
                Перейти →
              </motion.div>
            </div>
          </MotionLink>
          <MotionLink
            to={`${base}/ai-check/general`}
            className="group-card"
            whileHover={{ y: -4, scale: 1.01 }}
            whileTap={{ scale: 0.995 }}
            transition={{ duration: 0.18, ease: easeApple }}
            style={{
              border: "1px solid rgba(0, 163, 181, 0.28)",
              background: "linear-gradient(180deg, rgba(0,163,181,0.12), rgba(0,163,181,0.04))",
              boxShadow: "0 8px 20px rgba(0,163,181,0.14)",
            }}
          >
            <span className="group-card-avatar" style={{ boxShadow: "0 0 0 6px rgba(0,163,181,0.12)" }}>
              Aa
            </span>
            <div>
              <div className="group-card-title">Проверка остальных предметов</div>
              <div className="row" style={{ gap: "0.35rem", margin: "0.25rem 0 0.1rem" }}>
                <span className="pill">Текст</span>
                <span className="pill">Документ</span>
                <span className="pill">Изображение</span>
              </div>
              <p className="muted" style={{ margin: "0.2rem 0 0" }}>
                Подходит для гуманитарных и технических дисциплин: от сочинений до лабораторных отчётов.
              </p>
              <motion.div
                className="muted"
                initial={{ x: 0, opacity: 0.85 }}
                whileHover={{ x: 4, opacity: 1 }}
                transition={{ duration: 0.16, ease: easeApple }}
                style={{ marginTop: "0.55rem", fontWeight: 700 }}
              >
                Перейти →
              </motion.div>
            </div>
          </MotionLink>
        </div>
      </motion.div>
    </div>
  );
}
