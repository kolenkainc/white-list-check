-- IPv4-адреса для проверки с мобильного интернета
CREATE TABLE ip_checks (
  ip TEXT PRIMARY KEY NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('pending', 'reachable', 'unreachable')),
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX idx_ip_checks_pending_created
  ON ip_checks (created_at)
  WHERE status = 'pending';
