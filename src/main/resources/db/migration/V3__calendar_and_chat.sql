create table if not exists calendar_tasks (
    id uuid primary key,
    user_id uuid not null references users(id),
    course_id uuid not null references courses(id),
    assignment_id uuid not null references assignments(id),
    title varchar(255) not null,
    due_date timestamp with time zone not null,
    status varchar(50) not null,
    created_at timestamp with time zone not null default now(),
    unique (user_id, assignment_id)
);

create index if not exists idx_calendar_tasks_user_due_date on calendar_tasks(user_id, due_date);
create index if not exists idx_calendar_tasks_course_due_date on calendar_tasks(course_id, due_date);

create table if not exists chat_rooms (
    id uuid primary key,
    room_type varchar(50) not null,
    title varchar(255),
    course_id uuid references courses(id),
    created_by uuid not null references users(id),
    created_at timestamp with time zone not null default now()
);

create index if not exists idx_chat_rooms_type on chat_rooms(room_type);
create index if not exists idx_chat_rooms_course_id on chat_rooms(course_id);

create table if not exists chat_participants (
    id uuid primary key,
    room_id uuid not null references chat_rooms(id) on delete cascade,
    user_id uuid not null references users(id),
    joined_at timestamp with time zone not null default now(),
    unique (room_id, user_id)
);

create index if not exists idx_chat_participants_user_id on chat_participants(user_id);
create index if not exists idx_chat_participants_room_id on chat_participants(room_id);

create table if not exists chat_messages (
    id uuid primary key,
    room_id uuid not null references chat_rooms(id) on delete cascade,
    sender_id uuid not null references users(id),
    content text not null,
    created_at timestamp with time zone not null default now()
);

create index if not exists idx_chat_messages_room_created on chat_messages(room_id, created_at);
create index if not exists idx_chat_messages_sender_created on chat_messages(sender_id, created_at);
