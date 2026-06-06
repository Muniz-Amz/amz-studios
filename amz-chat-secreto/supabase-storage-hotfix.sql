drop policy if exists "secret chat delete expired media" on storage.objects;

create policy "secret chat delete expired media"
    on storage.objects
    for delete
    to anon
    using (
        bucket_id = 'secret-chat-media'
        and created_at < now() - interval '1 day'
        and (storage.foldername(name))[1] = public.current_secret_chat_room()
    );

create or replace function public.get_expired_secret_chat_media_paths()
returns text[]
language sql
security definer
set search_path = public
as $$
    select coalesce(array_agg(attachment_path), '{}'::text[])
    from public.secret_chat_messages
    where room_id = public.current_secret_chat_room()
      and attachment_path is not null
      and created_at < now() - interval '1 day';
$$;

revoke all on function public.get_expired_secret_chat_media_paths() from public;
grant execute on function public.get_expired_secret_chat_media_paths() to anon;

create or replace function public.cleanup_secret_chat_messages()
returns integer
language plpgsql
security definer
set search_path = public
as $$
declare
    deleted_messages integer := 0;
begin
    delete from public.secret_chat_messages
    where created_at < now() - interval '1 day';

    get diagnostics deleted_messages = row_count;

    return deleted_messages;
end;
$$;

revoke all on function public.cleanup_secret_chat_messages() from public;
grant execute on function public.cleanup_secret_chat_messages() to anon;
