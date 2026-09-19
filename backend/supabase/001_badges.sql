create table if not exists public.profiles (
    id bigint primary key,
    type text not null default 'USER' check (type in ('USER', 'CHAT')),
    status text not null default 'DEFAULT' check (status in ('DEFAULT', 'DEVELOPER', 'SUPPORTER')),
    badge_document_id bigint,
    badge_text text,
    can_change_badge boolean not null default false,
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
