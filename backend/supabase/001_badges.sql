create table if not exists public.profiles (
    id bigint primary key,
    type text not null default 'USER' check (type in ('USER', 'CHAT')),
    status text not null default 'DEFAULT' check (status in ('DEFAULT', 'DEVELOPER', 'SUPPORTER', 'RESOURCE', 'PARTNER')),
    badge_document_id bigint,
    badge_text text,
    can_change_badge boolean not null default false,
    show_badge_button boolean not null default true,
    badge_button_text text,
    badge_info_icon_document_id bigint,
    badge_info_title text,
    badge_info_message text,
    badge_close_text text,
    badge_info_features jsonb,
    forcemorebutton boolean not null default false,
    deleted boolean not null default false,
    updated_at timestamptz not null default now()
);

create index if not exists profiles_updated_at_idx on public.profiles (updated_at);
create index if not exists profiles_status_idx on public.profiles (status);

alter table public.profiles enable row level security;

drop policy if exists "public profiles are readable" on public.profiles;
create policy "public profiles are readable"
    on public.profiles
    for select
    to anon, authenticated
    using (deleted = false);

create or replace function public.touch_profiles_updated_at()
returns trigger
language plpgsql
as $$
begin
    new.updated_at = now();
    return new;
end;
$$;

drop trigger if exists profiles_updated_at on public.profiles;
create trigger profiles_updated_at
    before update on public.profiles
    for each row execute function public.touch_profiles_updated_at();

revoke insert, update, delete on public.profiles from anon, authenticated;
grant select on public.profiles to anon, authenticated;

-- Self-service badge editor. Writes go through a security definer function that
-- resolves the caller from the JWT subject, so the anon key stays read-only.
create or replace function public.update_my_badge(
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
    v_uid bigint;
    v_status text;
begin
    v_uid := nullif(current_setting('request.jwt.claim.sub', true), '');
    if v_uid is null then
        begin
            v_uid := nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'sub';
        exception when others then
            v_uid := null;
        end;
    end if;
    if v_uid is null then
        raise exception 'not authenticated' using errcode = '28000';
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
    where id = v_uid
      and can_change_badge = true
      and deleted = false;

    if not found then
        raise exception 'badge editing is not allowed for this account' using errcode = '42501';
    end if;
end;
$$;

revoke all on function public.update_my_badge(
    text, bigint, text, boolean, boolean, text, bigint, text, text, text, jsonb
) from public;
grant execute on function public.update_my_badge(
    text, bigint, text, boolean, boolean, text, bigint, text, text, text, jsonb
) to anon, authenticated;
