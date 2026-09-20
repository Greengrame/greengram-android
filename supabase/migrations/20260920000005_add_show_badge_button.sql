alter table public.profiles
    add column if not exists show_badge_button boolean not null default false;

comment on column public.profiles.show_badge_button is
    'Show the Learn More button in the badge bulletin for this profile';
