create table if not exists assignment_materials (
    id uuid primary key,
    assignment_id uuid not null references assignments(id) on delete cascade,
    file_key varchar(500) not null unique,
    original_name varchar(255) not null,
    content_type varchar(255),
    uploaded_at timestamp with time zone not null default now()
);

create index if not exists idx_assignment_materials_assignment on assignment_materials(assignment_id);

create table if not exists assignment_tests (
    id uuid primary key,
    assignment_id uuid not null unique references assignments(id) on delete cascade,
    title varchar(255) not null,
    max_score double precision not null
);

create table if not exists assignment_test_questions (
    id uuid primary key,
    test_id uuid not null references assignment_tests(id) on delete cascade,
    question_text text not null,
    question_type varchar(32) not null,
    options_json text,
    correct_answer text not null,
    points double precision not null
);

create index if not exists idx_assignment_test_questions_test on assignment_test_questions(test_id);

create table if not exists assignment_test_attempts (
    id uuid primary key,
    test_id uuid not null references assignment_tests(id) on delete cascade,
    student_id uuid not null references users(id),
    score double precision not null,
    submitted_at timestamp with time zone not null default now(),
    answers_json text not null
);

create index if not exists idx_assignment_test_attempts_test_student on assignment_test_attempts(test_id, student_id);
