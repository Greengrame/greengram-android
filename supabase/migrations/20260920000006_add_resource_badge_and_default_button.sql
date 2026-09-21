alter table public.profiles
    drop constraint if exists profiles_status_check;

alter table public.profiles
    add constraint profiles_status_check
        check (status in ('DEFAULT', 'DEVELOPER', 'SUPPORTER', 'RESOURCE'));

alter table public.profiles
    alter column show_badge_button set default true;

update public.profiles
set show_badge_button = true
where show_badge_button = false;
