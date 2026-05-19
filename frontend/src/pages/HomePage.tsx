import { Link } from "react-router-dom";
import { motion } from "framer-motion";
import { useAuth } from "../context/AuthContext";

const easeApple = [0.22, 1, 0.36, 1] as const;

export function HomePage() {
  const { user, loading } = useAuth();

  if (loading) {
    return (
      <div className="home-loading">
        <motion.div
          className="home-loading-dot"
          animate={{ opacity: [0.35, 1, 0.35] }}
          transition={{ duration: 1.2, repeat: Infinity, ease: "easeInOut" }}
        />
        <p className="muted">Загрузка…</p>
      </div>
    );
  }

  const loggedInCards =
    user?.role === "ROLE_STUDENT"
      ? [
          { title: "Личный кабинет", text: "Прогресс, дедлайны и статистика обучения.", to: "/student/cabinet" },
          { title: "Мои группы", text: "Курсы, материалы и активные задания.", to: "/student/groups" },
          { title: "AI-проверка", text: "Быстрая проверка работ и рекомендации.", to: "/student/ai-check" },
        ]
      : user?.role === "ROLE_TEACHER"
        ? [
            { title: "Кабинет преподавателя", text: "Сводка по группам и учебной нагрузке.", to: "/teacher/cabinet" },
            { title: "Мои группы", text: "Управление составом, заданиями и материалами.", to: "/teacher" },
            { title: "Чат", text: "Коммуникация со студентами и учебными командами.", to: "/chat" },
          ]
        : user?.role === "ROLE_ADMIN"
          ? [
              { title: "Пользователи", text: "Управление ролями, доступом и профилями.", to: "/admin/users" },
              { title: "Коды приглашения", text: "Генерация одноразовых кодов для сотрудников.", to: "/admin/invite-keys" },
              { title: "AI-провайдеры", text: "Настройка провайдеров и предметных политик.", to: "/admin/ai-providers" },
            ]
          : [];

  return (
    <div className="home-landing apple-page">
      <div className="home-orbit" aria-hidden>
        <motion.div
          className="home-orb home-orb--a"
          animate={{ scale: [1, 1.08, 1], opacity: [0.45, 0.65, 0.45] }}
          transition={{ duration: 10, repeat: Infinity, ease: "easeInOut" }}
        />
        <motion.div
          className="home-orb home-orb--b"
          animate={{ scale: [1.05, 1, 1.05], opacity: [0.35, 0.55, 0.35] }}
          transition={{ duration: 12, repeat: Infinity, ease: "easeInOut" }}
        />
      </div>

      <motion.section
        className="home-hero-inner"
        initial={{ opacity: 0, y: 28 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.75, ease: easeApple }}
      >
        <p className="hero-kicker">МИИТ Portal</p>
        <h1 className="home-headline">
          {user
            ? `Добро пожаловать, ${user.firstName}. Выберите, с чем хотите поработать сегодня.`
            : "Единая цифровая среда для студентов и преподавателей."}
        </h1>
        <div className="home-cta">
          {user ? (
            <>
              <Link to="/profile" className="btn glow-btn home-cta-primary">
                Профиль
              </Link>
              <Link to="/chat" className="btn secondary home-cta-secondary">
                Чат
              </Link>
            </>
          ) : (
            <>
              <Link to="/login" className="btn glow-btn home-cta-primary">
                Войти
              </Link>
              <Link to="/register" className="btn secondary home-cta-secondary">
                Создать аккаунт
              </Link>
            </>
          )}
        </div>
      </motion.section>

      <motion.div
        className="home-grid"
        initial="hidden"
        animate="show"
        variants={{
          hidden: {},
          show: {
            transition: { staggerChildren: 0.1 },
          },
        }}
      >
        {(user
          ? loggedInCards.map((card) => ({ ...card, isLink: true as const }))
          : [
              { title: "Ясность", text: "Понятный учебный поток и прозрачные требования.", to: "/login", isLink: false as const },
              { title: "Скорость", text: "Быстрый доступ к материалам, заданиям и проверкам.", to: "/register", isLink: false as const },
              { title: "Доверие", text: "Единая платформа для студентов, преподавателей и админов.", to: "/login", isLink: false as const },
            ]
        ).map((item) => (
          <motion.article
            key={item.title}
            className="glass-panel home-feature"
            variants={{
              hidden: { opacity: 0, y: 16 },
              show: { opacity: 1, y: 0 },
            }}
            transition={{ duration: 0.5, ease: easeApple }}
          >
            <h2 className="home-feature-title">{item.title}</h2>
            {"text" in item ? <p className="home-feature-text muted">{item.text}</p> : null}
            {"to" in item && item.isLink ? (
              <Link to={item.to} className="btn secondary" style={{ marginTop: "0.65rem" }}>
                Перейти
              </Link>
            ) : null}
          </motion.article>
        ))}
      </motion.div>
    </div>
  );
}
