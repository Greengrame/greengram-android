# Badge Database

Apply `001_badges.sql` and then `002_seed_profiles.sql` in Supabase SQL Editor.

The mobile client only needs the public Supabase URL and anon key. Writes must
use the Supabase dashboard or a server-side service-role job; never ship the
service-role key in the APK.

The REST endpoint is:

```text
GET {SUPABASE_URL}/rest/v1/profiles?select=id,type,status,badge_document_id,badge_text,can_change_badge,deleted,updated_at&deleted=eq.false
```

The client maps `badge_document_id` and `badge_text` into `BadgeDTO` and keeps
the response in its local cache for offline rendering.
