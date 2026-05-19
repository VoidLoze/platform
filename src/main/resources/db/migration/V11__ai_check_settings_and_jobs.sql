-- V11: настройки AI-провайдеров проверки + асинхронные задачи AI-проверки.

create table if not exists ai_check_settings (
    id integer primary key,
    default_provider varchar(50) not null,
    fallback_chain varchar(500) not null,
    subject_policy_json text,
    updated_at timestamp with time zone not null default now()
);

insert into ai_check_settings (id, default_provider, fallback_chain, subject_policy_json)
select 1, 'GIGACHAT', 'GIGACHAT,DEEPSEEK,QWEN', '{}'::text
where not exists (select 1 from ai_check_settings where id = 1);

create table if not exists ai_check_jobs (
    id uuid primary key,
    requester_id uuid not null references users(id),
    lab_work_id uuid references lab_works(id) on delete set null,
    subject varchar(50) not null,
    source_type varchar(50) not null,
    title varchar(500),
    custom_instructions text,
    student_text text,
    attachment_file_key varchar(500),
    git_url varchar(1000),
    selected_provider varchar(50),
    fallback_chain varchar(500),
    status varchar(50) not null,
    error_message text,
    created_at timestamp with time zone not null default now(),
    started_at timestamp with time zone,
    finished_at timestamp with time zone
);

create index if not exists idx_ai_check_jobs_status on ai_check_jobs(status);
create index if not exists idx_ai_check_jobs_requester on ai_check_jobs(requester_id, created_at desc);

create table if not exists ai_check_results (
    id uuid primary key,
    job_id uuid not null references ai_check_jobs(id) on delete cascade,
    provider varchar(50) not null,
    score double precision not null,
    max_score double precision not null,
    summary text,
    detailed_feedback text,
    strengths_json text,
    issues_json text,
    recommendations_json text,
    raw_response text,
    latency_ms bigint not null default 0,
    created_at timestamp with time zone not null default now(),
    is_primary boolean not null default false
);

create index if not exists idx_ai_check_results_job on ai_check_results(job_id);
