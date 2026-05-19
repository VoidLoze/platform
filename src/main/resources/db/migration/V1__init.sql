create table if not exists users (
    id uuid primary key,
    first_name varchar(255) not null,
    last_name varchar(255) not null,
    middle_name varchar(255),
    email varchar(255) unique not null,
    password_hash varchar(255) not null,
    role varchar(50) not null
);

create table if not exists courses (
    id uuid primary key,
    title varchar(255) not null
);

create table if not exists enrollments (
    course_id uuid not null references courses(id),
    user_id uuid not null references users(id),
    primary key (course_id, user_id)
);

create table if not exists assignments (
    id uuid primary key,
    course_id uuid not null references courses(id),
    topic_title varchar(255) not null,
    topic_description text,
    subject_area varchar(255),
    due_date timestamp with time zone not null,
    allow_late_submission boolean not null,
    late_penalty_percent double precision not null,
    max_score double precision not null
);

create table if not exists lab_works (
    id uuid primary key,
    assignment_id uuid not null references assignments(id),
    student_id uuid not null references users(id),
    status varchar(50) not null,
    text_content text,
    attachment_key varchar(500),
    language varchar(50),
    submission_time timestamp with time zone,
    final_grade_value double precision,
    final_grade_letter varchar(16)
);

create table if not exists ai_reviews (
    id uuid primary key,
    lab_work_id uuid unique not null references lab_works(id),
    score_value double precision not null,
    max_score double precision not null,
    summary text,
    detailed_feedback text,
    recommendations text
);

create table if not exists generated_tests (
    id uuid primary key,
    lab_work_id uuid unique not null references lab_works(id),
    max_score double precision not null
);

create table if not exists questions (
    id uuid primary key,
    test_id uuid not null references generated_tests(id),
    text text not null,
    difficulty varchar(32),
    correct_answer text
);

create table if not exists plagiarism_reports (
    id uuid primary key,
    lab_work_id uuid unique not null references lab_works(id),
    similarity_score double precision not null,
    matched_sources_json text
);

create table if not exists outbox_events (
    id uuid primary key,
    event_type varchar(255) not null,
    payload text not null,
    created_at timestamp with time zone not null,
    processed_at timestamp with time zone
);
