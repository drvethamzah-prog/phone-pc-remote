# Supabase setup

1. Create a Supabase project on the Free plan.
2. Open SQL Editor and run `supabase/schema.sql`.
3. In Authentication → Providers → Email, enable email sign-ups and email confirmations.
4. Set the Site URL to the final Vercel URL.
5. Copy the project URL and anon key into Vercel environment variables:

```text
SUPABASE_URL
SUPABASE_ANON_KEY
```

The Vercel API uses Supabase Auth for registration and sign-in. Desktop control
is never routed through this API; it remains a local-LAN connection.
