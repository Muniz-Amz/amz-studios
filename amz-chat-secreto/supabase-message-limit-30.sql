create extension if not exists pgcrypto;

create table if not exists public.secret_chat_media_cleanup_queue (
    id uuid primary key default gen_random_uuid(),
    room_id text not null,
    object_path text not null unique,
    queued_at timestamptz not null default now()
);

create index if not exists secret_chat_media_cleanup_queue_room_idx
    on public.secret_chat_media_cleanup_queue (room_id, queued_at);

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

create or replace function public.cleanup_secret_chat_messages()
returns integer
language plpgsql
security definer
set search_path = public
as $$
declare
    deleted_messages integer := 0;
    deleted_expired integer := 0;
    deleted_overflow integer := 0;
begin
    perform public.queue_expired_secret_chat_media();

    delete from public.secret_chat_messages
    where created_at < now() - interval '1 day';

    get diagnostics deleted_expired = row_count;

    with overflow_messages as (
        select id, room_id, attachment_path
        from (
            select
                id,
                room_id,
                attachment_path,
                row_number() over (
                    partition by room_id
                    order by created_at desc, id desc
                ) as message_rank
            from public.secret_chat_messages
        ) ranked_messages
        where message_rank > 30
    ),
    queued_media as (
        insert into public.secret_chat_media_cleanup_queue (room_id, object_path)
        select distinct room_id, attachment_path
        from overflow_messages
        where attachment_path is not null
        on conflict (object_path) do nothing
        returning 1
    ),
    deleted_rows as (
        delete from public.secret_chat_messages
        where id in (select id from overflow_messages)
        returning 1
    )
    select count(*)
    into deleted_overflow
    from deleted_rows;

    deleted_messages := deleted_expired + deleted_overflow;

    return deleted_messages;
end;
$$;

revoke all on function public.cleanup_secret_chat_messages() from public;
grant execute on function public.cleanup_secret_chat_messages() to anon;

create or replace function public.secret_chat_cleanup_after_insert()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    perform public.cleanup_secret_chat_messages();
    return null;
end;
$$;

drop trigger if exists secret_chat_cleanup_after_insert on public.secret_chat_messages;

create trigger secret_chat_cleanup_after_insert
    after insert on public.secret_chat_messages
    for each statement
    execute function public.secret_chat_cleanup_after_insert();

select public.cleanup_secret_chat_messages() as mensagens_removidas;
