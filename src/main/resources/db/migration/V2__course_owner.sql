alter table courses add column if not exists owner_id uuid references users(id);
