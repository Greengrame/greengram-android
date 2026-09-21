alter table public.profiles
    add column if not exists badge_info_icon_document_id bigint,
    add column if not exists badge_close_text text;

comment on column public.profiles.badge_info_icon_document_id is
    'Animated emoji document used as the main icon in the badge details sheet';
comment on column public.profiles.badge_close_text is
    'Optional label for the badge details sheet close button';
