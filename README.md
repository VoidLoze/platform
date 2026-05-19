# AI Lab Check Platform

Дипломный проект: веб-сервис для автоматизации проверки лабораторных работ с ИИ-проверкой через GigaChat.

## Что реализовано
- DDD-контексты: `identity`, `learning`, `evaluation`, `application`, `ui`, `infrastructure`.
- Роли: студент, преподаватель, админ (`ROLE_STUDENT`, `ROLE_TEACHER`, `ROLE_ADMIN`).
- Поток: создание курса/заданий -> отправка лабораторной -> AI review + генерация теста -> финальная оценка.
- События: `AssignmentSubmitted`, `ReviewGenerated`, `TestCompleted` через outbox + Redis pub/sub.
- Антиплагиат MVP: Jaccard-схожесть по внутреннему корпусу работ.
- Инфраструктура через `docker-compose`: PostgreSQL, Redis, MinIO, MailHog, Backend, Worker, Frontend.
- **Frontend**: React 19 + TypeScript + Vite; роли студент / преподаватель / админ; JWT (`Bearer`) для API.
- В Docker UI проксирует `/api` на backend (один origin, без ручной настройки CORS в браузере).

## Запуск
```bash
docker compose up --build
```

Сервисы:
- Backend: `http://localhost:8080`
- Frontend: `http://localhost:3000`
- MailHog UI: `http://localhost:8025`
- MinIO Console: `http://localhost:9001` (`minio` / `minio123`)

Профиль `docker`: создаются демо-пользователи (email / пароль `password`):
- `teacher@demo.local` — преподаватель
- `student@demo.local` — студент
- `admin@demo.local` — админ

## Локальная разработка UI
```bash
cd frontend && npm install && npm run dev
```
Vite: `http://localhost:5173`, прокси `/api` → `http://localhost:8080`. Backend: `./mvnw spring-boot:run`.

## Базовые API
- `POST /api/v1/auth/login`, `POST /api/v1/auth/refresh` — JWT.
- `POST /api/v1/users/register` - регистрация.
- `POST /api/v1/courses` - создание курса.
- `POST /api/v1/courses/{courseId}/assignments` - создание задания.
- `POST /api/v1/assignments/{assignmentId}/submit` - отправка лабораторной.
- `POST /api/v1/assignments/labworks/{labWorkId}/review` - генерация AI-review.

## Переменные для JWT
- `PLATFORM_JWT_SECRET` (в docker-compose для backend), минимум 32+ символа для HS256.

## Переменные для GigaChat
- `GIGACHAT_BASE_URL`
- `GIGACHAT_MODEL`
- `GIGACHAT_TOKEN`

По умолчанию в проекте стоит `demo-token`; для реальной проверки замените токен.

## Тесты
```bash
./mvnw test
```
