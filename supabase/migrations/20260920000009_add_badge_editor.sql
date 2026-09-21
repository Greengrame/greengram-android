alter table public.profiles
    add column if not exists editor_secret text;

alter table public.profiles
    add column if not exists forcemorebutton boolean not null default false;

comment on column public.profiles.editor_secret is
    'Random per-profile secret issued by the badge bot, required to authorize badge writes';
comment on column public.profiles.forcemorebutton is
    'When true the badge bulletin always shows the Learn More button, even for short texts';

create extension if not exists pgcrypto with schema extensions;

update public.profiles
set editor_secret = encode(extensions.gen_random_bytes(24), 'hex')
where can_change_badge = true
  and editor_secret is null;

-- The secret must never be readable with the anon key, so expose the table
-- through an explicit column list and keep editor_secret out of it.
revoke select on public.profiles from anon, authenticated;
grant select (
    id, type, status, badge_document_id, badge_text, can_change_badge,
    show_badge_button, forcemorebutton, badge_button_text,
    badge_info_icon_document_id, badge_info_title, badge_info_message,
    badge_close_text, badge_info_features, deleted, updated_at
) on public.profiles to anon, authenticated;

create or replace function public.update_my_badge(
    p_telegram_id bigint,
    p_secret text,
    p_status text default null,
    p_badge_document_id bigint default null,
    p_badge_text text default null,
    p_show_badge_button boolean default null,
    p_forcemorebutton boolean default null,
    p_badge_button_text text default null,
    p_badge_info_icon_document_id bigint default null,
    p_badge_info_title text default null,
    p_badge_info_message text default null,
    p_badge_close_text text default null,
    p_badge_info_features jsonb default null
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_status text;
begin
    if p_telegram_id is null or p_secret is null or p_secret = '' then
        raise exception 'missing credentials' using errcode = '28000';
    end if;

    v_status := upper(coalesce(p_status, ''));
    if v_status = '' then
        v_status := null;
    elsif v_status not in ('DEFAULT', 'DEVELOPER', 'SUPPORTER', 'RESOURCE', 'PARTNER') then
        raise exception 'invalid status: %', p_status using errcode = '22023';
    end if;

    update public.profiles
    set status = coalesce(v_status, status),
        badge_document_id = coalesce(p_badge_document_id, badge_document_id),
        badge_text = p_badge_text,
        show_badge_button = coalesce(p_show_badge_button, show_badge_button),
        forcemorebutton = coalesce(p_forcemorebutton, forcemorebutton),
        badge_button_text = p_badge_button_text,
        badge_info_icon_document_id = p_badge_info_icon_document_id,
        badge_info_title = p_badge_info_title,
        badge_info_message = p_badge_info_message,
        badge_close_text = p_badge_close_text,
        badge_info_features = p_badge_info_features
    where id = p_telegram_id
      and can_change_badge = true
      and editor_secret is not null
      and editor_secret = p_secret
      and deleted = false;

    if not found then
        raise exception 'badge editing is not allowed for this account' using errcode = '42501';
    end if;
end;
$$;

revoke all on function public.update_my_badge(
    bigint, text, text, bigint, text, boolean, boolean, text, bigint, text, text, text, jsonb
) from public;
grant execute on function public.update_my_badge(
    bigint, text, text, bigint, text, boolean, boolean, text, bigint, text, text, text, jsonb
) to anon, authenticated;
