create table if not exists chat_read_receipts (
    id uuid primary key,
    room_id uuid not null references chat_rooms(id) on delete cascade,
    user_id uuid not null references users(id),
    message_id uuid not null references chat_messages(id) on delete cascade,
    read_at timestamp with time zone not null default now(),
    unique (room_id, user_id)
);

create index if not exists idx_chat_receipts_room_user on chat_read_receipts(room_id, user_id);
create index if not exists idx_chat_receipts_message on chat_read_receipts(message_id);
