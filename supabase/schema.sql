-- 자산 원장 · Supabase 스키마
-- Supabase 대시보드 > SQL Editor 에 그대로 붙여넣고 실행하세요.
-- 실행 전에 Authentication > Users 에서 본인 계정을 하나 만들어 두면 됩니다.

-- ── 일별 기록 ────────────────────────────────────────────────
create table if not exists public.ledger_days (
  d           date        not null,
  owner       uuid        not null default auth.uid(),
  data        jsonb       not null,
  updated_at  timestamptz not null default now(),
  primary key (owner, d)
);

-- ── 설정값 (기준환율, 기준원금, 목표 등) ──────────────────────
create table if not exists public.meta (
  k           text        not null,
  owner       uuid        not null default auth.uid(),
  v           jsonb       not null,
  updated_at  timestamptz not null default now(),
  primary key (owner, k)
);

-- ── 시세 (알림수집 앱이 주기적으로 갱신) ──────────────────────
create table if not exists public.quotes (
  sym         text        not null,
  owner       uuid        not null default auth.uid(),
  price       numeric     not null,
  at          timestamptz not null default now(),
  primary key (owner, sym)
);

-- ── 수신함: 알림수집 앱이 넣는 알림톡 원문 ────────────────────
create table if not exists public.inbox (
  id          bigint generated always as identity primary key,
  owner       uuid        not null default auth.uid(),
  raw         text        not null,
  pkg         text,
  received_at timestamptz not null,
  status      text        not null default 'pending',   -- pending | applied | failed
  fingerprint text,                                     -- 중복 방지 키
  created_at  timestamptz not null default now()
);
-- 같은 알림이 두 번 올라와도 한 번만 저장되게 한다
create unique index if not exists inbox_fp_uniq on public.inbox (owner, fingerprint);
create index if not exists inbox_pending_idx on public.inbox (owner, status, received_at);

-- ── 행 수준 보안: 본인 데이터만 ───────────────────────────────
alter table public.ledger_days enable row level security;
alter table public.meta        enable row level security;
alter table public.quotes      enable row level security;
alter table public.inbox       enable row level security;

do $$
declare t text;
begin
  foreach t in array array['ledger_days','meta','quotes','inbox'] loop
    execute format('drop policy if exists own_all on public.%I', t);
    execute format(
      'create policy own_all on public.%I for all to authenticated using (owner = auth.uid()) with check (owner = auth.uid())', t);
  end loop;
end $$;

-- ── updated_at 자동 갱신 ──────────────────────────────────────
create or replace function public.touch_updated_at() returns trigger
language plpgsql as $$ begin new.updated_at = now(); return new; end $$;

drop trigger if exists t_days on public.ledger_days;
create trigger t_days before update on public.ledger_days
  for each row execute function public.touch_updated_at();

drop trigger if exists t_meta on public.meta;
create trigger t_meta before update on public.meta
  for each row execute function public.touch_updated_at();
