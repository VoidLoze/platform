-- V10: групповая лента (новости), материалы группы и аватарки/описание группы.

alter table courses
    add column if not exists avatar_file_key varchar(500),
    add column if not exists description text,
    add column if not exists created_at timestamp with time zone not null default now();

create table if not exists group_posts (
    id uuid primary key,
    course_id uuid not null references courses(id) on delete cascade,
    author_id uuid not null references users(id),
    title varchar(500),
    body text not null,
    pinned boolean not null default false,
    created_at timestamp with time zone not null default now(),
    updated_at timestamp with time zone not null default now()
);

create index if not exists idx_group_posts_course_created
    on group_posts (course_id, created_at desc);

create table if not exists group_post_attachments (
    id uuid primary key,
    post_id uuid not null references group_posts(id) on delete cascade,
    file_key varchar(500) not null,
    original_name varchar(500) not null,
    content_type varchar(255),
    size_bytes bigint
);

create index if not exists idx_group_post_attachments_post on group_post_attachments(post_id);

create table if not exists group_materials (
    id uuid primary key,
    course_id uuid not null references courses(id) on delete cascade,
    uploader_id uuid not null references users(id),
    file_key varchar(500) not null unique,
    original_name varchar(500) not null,
    content_type varchar(255),
    size_bytes bigint,
    title varchar(500),
    description text,
    uploaded_at timestamp with time zone not null default now()
);

create index if not exists idx_group_materials_course on group_materials(course_id, uploaded_at desc);
