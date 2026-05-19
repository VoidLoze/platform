-- Журнал этапов AI-проверки (для SSE / real-time прогресса).

create table if not exists ai_check_job_events (
    id bigserial primary key,
    job_id uuid not null references ai_check_jobs(id) on delete cascade,
    phase varchar(64) not null,
    detail text,
    created_at timestamp with time zone not null default now()
);

create index if not exists idx_ai_check_job_events_job_id_id on ai_check_job_events(job_id, id);
