-- Repeatable: ensures ai_reviews exists if an old DB missed the versioned fix (idempotent).
create table if not exists ai_reviews (
    id uuid primary key,
    lab_work_id uuid unique not null references lab_works(id),
    score_value double precision not null,
    max_score double precision not null,
    summary text,
    detailed_feedback text,
    recommendations text
);
