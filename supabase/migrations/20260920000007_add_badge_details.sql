alter table public.profiles
    drop constraint if exists profiles_status_check;

alter table public.profiles
    add constraint profiles_status_check
        check (status in ('DEFAULT', 'DEVELOPER', 'SUPPORTER', 'RESOURCE', 'PARTNER'));

alter table public.profiles
    add column if not exists badge_button_text text,
    add column if not exists badge_info_title text,
    add column if not exists badge_info_message text,
    add column if not exists badge_info_features jsonb;

comment on column public.profiles.badge_button_text is
    'Optional label for the badge details button';
comment on column public.profiles.badge_info_title is
    'Optional title shown in the badge details sheet';
comment on column public.profiles.badge_info_message is
    'Optional message shown in the badge details sheet';
comment on column public.profiles.badge_info_features is
    'Optional JSON array of badge details rows with enabled, icon_document_id, title and text';
