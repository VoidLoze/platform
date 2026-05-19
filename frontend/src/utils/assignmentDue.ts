/** Дедлайн задания уже наступил (сравнение по локальному времени браузера). */
export function isAssignmentPastDue(dueDate: string): boolean {
  const t = new Date(dueDate).getTime();
  return Number.isFinite(t) && t < Date.now();
}
