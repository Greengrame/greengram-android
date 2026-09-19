update public.profiles
set badge_text = null,
    updated_at = now()
where id in (6443536403, 1940952420);
