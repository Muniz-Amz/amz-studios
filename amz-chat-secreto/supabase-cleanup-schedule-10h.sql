create extension if not exists pgcrypto;

create table if not exists public.secret_chat_media_cleanup_queue (
    id uuid primary key default gen_random_uuid(),
    room_id text not null,
    object_path text not null unique,
    queued_at timestamptz not null default now()
);

create index if not exists secret_chat_media_cleanup_queue_room_idx
    on public.secret_chat_media_cleanup_queue (room_id, queued_at);

alter table public.secret_chat_media_cleanup_queue enable row level security;

drop policy if exists "secret chat read media cleanup queue" on public.secret_chat_media_cleanup_queue;

create policy "secret chat read media cleanup queue"
    on public.secret_chat_media_cleanup_queue
    for select
    to anon
    using (room_id = public.current_secret_chat_room());

create or replace function public.queue_expired_secret_chat_media()
returns integer
language plpgsql
security definer
set search_path = public
as $$
declare
    queued_paths integer := 0;
begin
    insert into public.secret_chat_media_cleanup_queue (room_id, object_path)
    select distinct room_id, attachment_path
    from public.secret_chat_messages
    where attachment_path is not null
      and created_at < now() - interval '1 day'
    on conflict (object_path) do nothing;

    get diagnostics queued_paths = row_count;

    return queued_paths;
end;
$$;

revoke all on function public.queue_expired_secret_chat_media() from public;
grant execute on function public.queue_expired_secret_chat_media() to anon;

create or replace function public.get_expired_secret_chat_media_paths()
returns text[]
language plpgsql
security definer
set search_path = public
as $$
declare
    media_paths text[] := '{}'::text[];
begin
    perform public.queue_expired_secret_chat_media();

    select coalesce(array_agg(object_path), '{}'::text[])
    into media_paths
    from public.secret_chat_media_cleanup_queue
    where room_id = public.current_secret_chat_room();

    return media_paths;
end;
$$;

revoke all on function public.get_expired_secret_chat_media_paths() from public;
grant execute on function public.get_expired_secret_chat_media_paths() to anon;

create or replace function public.clear_secret_chat_media_cleanup_paths(media_paths text[])
returns integer
language plpgsql
security definer
set search_path = public
as $$
declare
    cleared_paths integer := 0;
begin
    delete from public.secret_chat_media_cleanup_queue
    where room_id = public.current_secret_chat_room()
      and object_path = any(coalesce(media_paths, '{}'::text[]));

    get diagnostics cleared_paths = row_count;

    return cleared_paths;
end;
$$;

revoke all on function public.clear_secret_chat_media_cleanup_paths(text[]) from public;
grant execute on function public.clear_secret_chat_media_cleanup_paths(text[]) to anon;

create or replace function public.cleanup_secret_chat_messages()
returns integer
language plpgsql
security definer
set search_path = public
as $$
declare
    deleted_messages integer := 0;
begin
    perform public.queue_expired_secret_chat_media();

    delete from public.secret_chat_messages
    where created_at < now() - interval '1 day';

    get diagnostics deleted_messages = row_count;

    return deleted_messages;
end;
$$;

revoke all on function public.cleanup_secret_chat_messages() from public;
grant execute on function public.cleanup_secret_chat_messages() to anon;

create extension if not exists pg_cron;

select cron.schedule(
    'amz_secret_chat_cleanup_10h',
    '10 hours',
    $$select public.cleanup_secret_chat_messages();$$
);

select jobname, schedule, command
from cron.job
where jobname = 'amz_secret_chat_cleanup_10h';
