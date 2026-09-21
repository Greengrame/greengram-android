# Badge Database

Apply `001_badges.sql` and then `002_seed_profiles.sql` in Supabase SQL Editor.

The mobile client only needs the public Supabase URL and anon key. Reads are
open; writes go through the `update_my_badge` security definer function that
resolves the caller from `auth.uid()`, so the anon key alone cannot edit rows.
Never ship the service-role key in the APK.

The REST endpoints are:

```text
GET {SUPABASE_URL}/rest/v1/profiles?select=id,type,status,badge_document_id,badge_text,can_change_badge,forcemorebutton,deleted,updated_at&deleted=eq.false

POST {SUPABASE_URL}/rest/v1/rpc/update_my_badge
```

The client maps `badge_document_id` and `badge_text` into `BadgeDTO` and keeps
the response in its local cache for offline rendering. Callers whose profile has
`can_change_badge = true` may edit their own badge fields, including
`forcemorebutton`, which forces the Learn More button to always appear.
