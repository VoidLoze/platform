create table if not exists chat_message_attachments (
    id uuid primary key,
    message_id uuid not null references chat_messages(id) on delete cascade,
    file_key varchar(512) not null,
    original_name varchar(512),
    content_type varchar(255),
    sort_index int not null default 0
);

create index if not exists idx_chat_msg_att_message on chat_message_attachments(message_id);

insert into chat_message_attachments (id, message_id, file_key, original_name, content_type, sort_index)
select gen_random_uuid(), m.id, m.attachment_file_key, m.attachment_original_name, m.attachment_content_type, 0
from chat_messages m
where m.attachment_file_key is not null and trim(m.attachment_file_key) <> '';

alter table chat_messages drop column if exists attachment_file_key;
alter table chat_messages drop column if exists attachment_original_name;
alter table chat_messages drop column if exists attachment_content_type;
