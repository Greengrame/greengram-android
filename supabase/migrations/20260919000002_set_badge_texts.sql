update public.profiles
set badge_text = case id
    when 6443536403 then 'Developer'
    when 1940952420 then 'Supporter'
    when 7596005407 then 'special tranny supporter'
    else badge_text
end,
updated_at = now()
where id in (6443536403, 1940952420, 7596005407);
