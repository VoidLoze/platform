import { motion, useReducedMotion } from "framer-motion";
import type { UserRole } from "../types";

type RoleOption = {
  value: UserRole;
  title: string;
  description: string;
  accent: "student" | "teacher" | "admin";
  icon: "student" | "teacher" | "admin";
};

const OPTIONS: RoleOption[] = [
  {
    value: "ROLE_STUDENT",
    title: "Студент",
    description: "Группы, задания и календарь",
    accent: "student",
    icon: "student",
  },
  {
    value: "ROLE_TEACHER",
    title: "Преподаватель",
    description: "Группы, материалы и оценки",
    accent: "teacher",
    icon: "teacher",
  },
  {
    value: "ROLE_ADMIN",
    title: "Администратор",
    description: "Управление пользователями",
    accent: "admin",
    icon: "admin",
  },
];

function RoleIcon({ kind }: { kind: RoleOption["icon"] }) {
  const common = { width: 28, height: 28, viewBox: "0 0 24 24", fill: "none", stroke: "currentColor", strokeWidth: 1.6, strokeLinecap: "round" as const, strokeLinejoin: "round" as const };
  if (kind === "student") {
    return (
      <svg {...common} aria-hidden>
        <path d="M12 3l9 4.5v5c0 5-3.5 8.5-9 9.5-5.5-1-9-4.5-9-9.5v-5L12 3z" />
        <path d="M8.5 12.5h7M12 9v7" />
      </svg>
    );
  }
  if (kind === "teacher") {
    return (
      <svg {...common} aria-hidden>
        <rect x="3" y="4" width="18" height="12" rx="2" />
        <path d="M7 8h10M7 12h6" />
        <path d="M12 16v3M9 21h6" />
      </svg>
    );
  }
  return (
    <svg {...common} aria-hidden>
      <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
      <path d="M9 12l2 2 4-4" />
    </svg>
  );
}

type Props = {
  value: UserRole;
  onChange: (role: UserRole) => void;
  disabled?: boolean;
  /** id элемента-заголовка поля (лучше, чем дублировать aria-label) */
  labelledBy?: string;
};

export function RolePicker({ value, onChange, disabled, labelledBy }: Props) {
  const reduceMotion = useReducedMotion();

  return (
    <div
      className="role-picker"
      role="radiogroup"
      aria-labelledby={labelledBy}
      aria-label={labelledBy ? undefined : "Роль в системе"}
    >
      {OPTIONS.map((opt, index) => {
        const selected = value === opt.value;
        return (
          <motion.button
            key={opt.value}
            type="button"
            role="radio"
            aria-checked={selected}
            disabled={disabled}
            className={`role-tile role-tile--${opt.accent} ${selected ? "role-tile--selected" : ""}`}
            onClick={() => onChange(opt.value)}
            whileTap={reduceMotion || disabled ? undefined : { scale: 0.98 }}
            transition={{
              type: "spring",
              stiffness: 420,
              damping: 28,
              delay: reduceMotion || disabled ? 0 : index * 0.04,
            }}
            initial={reduceMotion ? false : { opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
          >
            <span className="role-tile-icon" aria-hidden>
              <RoleIcon kind={opt.icon} />
            </span>
            <span className="role-tile-text">
              <span className="role-tile-title">{opt.title}</span>
              <span className="role-tile-desc">{opt.description}</span>
            </span>
            <span className="role-tile-check" aria-hidden>
              {selected ? "✓" : ""}
            </span>
          </motion.button>
        );
      })}
    </div>
  );
}
