create table if not exists public.account_settings (
  user_id uuid primary key references auth.users(id) on delete cascade,
  settings jsonb not null default '{}'::jsonb,
  updated_at timestamptz not null default now()
);

create table if not exists public.devices (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  device_id text not null,
  name text not null,
  platform text not null check (platform in ('windows','macos','linux')),
  status text not null default 'offline' check (status in ('online','offline')),
  last_seen_at timestamptz,
  created_at timestamptz not null default now(),
  unique(user_id, device_id)
);

alter table public.account_settings enable row level security;
alter table public.devices enable row level security;

create policy "users manage own settings" on public.account_settings
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "users read own devices" on public.devices
  for select using (auth.uid() = user_id);
create policy "users insert own devices" on public.devices
  for insert with check (auth.uid() = user_id);

create or replace function public.touch_settings_updated_at()
returns trigger language plpgsql as $$
begin new.updated_at = now(); return new; end; $$;

drop trigger if exists account_settings_updated_at on public.account_settings;
create trigger account_settings_updated_at before update on public.account_settings
for each row execute function public.touch_settings_updated_at();

create table if not exists public.account_members (
  user_id uuid primary key references auth.users(id) on delete cascade,
  role text not null default 'member' check (role in ('owner','admin','member','viewer')),
  created_at timestamptz not null default now()
);

create table if not exists public.phones (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  device_id text not null,
  phone_id text not null,
  name text not null,
  email text not null,
  paired_at timestamptz,
  revoked_at timestamptz,
  created_at timestamptz not null default now(),
  unique(user_id, device_id, phone_id)
);

create table if not exists public.audit_events (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references auth.users(id) on delete set null,
  event_type text not null,
  source text not null check (source in ('cloud','local')),
  metadata jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

alter table public.account_members enable row level security;
alter table public.phones enable row level security;
alter table public.audit_events enable row level security;

create policy "members read own role" on public.account_members
  for select using (auth.uid() = user_id);
create policy "users read own phones" on public.phones
  for select using (auth.uid() = user_id);
create policy "users insert own phones" on public.phones
  for insert with check (auth.uid() = user_id);
create policy "users read own audit" on public.audit_events
  for select using (auth.uid() = user_id);
