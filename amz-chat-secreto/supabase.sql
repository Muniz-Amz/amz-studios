create extension if not exists pgcrypto;

create table if not exists public.secret_chat_messages (
    id uuid primary key default gen_random_uuid(),
    room_id text not null,
    profile_id text not null check (profile_id in ('perfil_1', 'perfil_2')),
    profile_name text not null,
    body text,
    attachment_url text,
    attachment_path text,
    attachment_type text check (attachment_type in ('image', 'video') or attachment_type is null),
    attachment_name text,
    attachment_size bigint,
    created_at timestamptz not null default now()
);

create index if not exists secret_chat_messages_room_created_idx
    on public.secret_chat_messages (room_id, created_at desc);

create or replace function public.current_secret_chat_room()
returns text
language sql
stable
as $$
    select nullif(
        coalesce(nullif(current_setting('request.headers', true), ''), '{}')::json ->> 'x-amz-room-id',
        ''
    );
$$;

alter table public.secret_chat_messages enable row level security;

drop policy if exists "secret chat read messages" on public.secret_chat_messages;
drop policy if exists "secret chat insert messages" on public.secret_chat_messages;

create policy "secret chat read messages"
    on public.secret_chat_messages
    for select
    to anon
    using (room_id = public.current_secret_chat_room());

create policy "secret chat insert messages"
    on public.secret_chat_messages
    for insert
    to anon
    with check (
        length(room_id) = 64
        and room_id = public.current_secret_chat_room()
        and profile_id in ('perfil_1', 'perfil_2')
        and (
            body is not null
            or attachment_url is not null
        )
    );

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
    'secret-chat-media',
    'secret-chat-media',
    true,
    62914560,
    array[
        'image/jpeg',
        'image/png',
        'image/webp',
        'image/gif',
        'video/mp4',
        'video/webm',
        'video/quicktime'
    ]
)
on conflict (id) do update
set
    public = excluded.public,
    file_size_limit = excluded.file_size_limit,
    allowed_mime_types = excluded.allowed_mime_types;

drop policy if exists "secret chat read media" on storage.objects;
drop policy if exists "secret chat upload media" on storage.objects;

create policy "secret chat read media"
    on storage.objects
    for select
    to anon
    using (bucket_id = 'secret-chat-media');

create policy "secret chat upload media"
    on storage.objects
    for insert
    to anon
    with check (
        bucket_id = 'secret-chat-media'
        and (storage.foldername(name))[1] is not null
    );
