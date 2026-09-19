insert into public.profiles (
    id,
    type,
    status,
    badge_document_id,
    badge_text,
    can_change_badge,
    deleted
)
values
    (6443536403, 'USER', 'DEVELOPER', 5359407509327085568, null, false, false),
    (1940952420, 'USER', 'SUPPORTER', 5391059537102927631, null, false, false),
    (7596005407, 'USER', 'DEFAULT', 5271799200214960712, null, false, false)
on conflict (id) do update set
    type = excluded.type,
    status = excluded.status,
    badge_document_id = excluded.badge_document_id,
    badge_text = excluded.badge_text,
    can_change_badge = excluded.can_change_badge,
    deleted = excluded.deleted,
    updated_at = now();
