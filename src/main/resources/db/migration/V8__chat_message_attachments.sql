alter table chat_messages
    add column if not exists attachment_file_key varchar(512),
    add column if not exists attachment_original_name varchar(512),
    add column if not exists attachment_content_type varchar(255);
