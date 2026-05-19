alter table if exists ai_check_job_events
    add column if not exists scope varchar(16),
    add column if not exists file_path varchar(1024),
    add column if not exists test_id varchar(128);

create index if not exists idx_ai_check_job_events_job_scope on ai_check_job_events(job_id, scope);
