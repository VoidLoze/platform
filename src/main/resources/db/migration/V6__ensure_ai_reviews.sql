-- Older deployments may have applied an earlier V1 without ai_reviews; Flyway will not re-run V1.
create table if not exists ai_reviews (
    id uuid primary key,
    lab_work_id uuid unique not null references lab_works(id),
    score_value double precision not null,
    max_score double precision not null,
    summary text,
    detailed_feedback text,
    recommendations text
);
