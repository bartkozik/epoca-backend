CREATE TABLE mail_history(
  mail_uuid UUID NOT NULL PRIMARY KEY,
  mail_type TEXT NOT NULL,
  email TEXT NOT NULL,
  created TIMESTAMP NULL,
  api_success TIMESTAMP NULL,
  delivered TIMESTAMP NULL,
  clicked TIMESTAMP NULL,
  opened TIMESTAMP NULL
);

CREATE TABLE sendgrid_mail_event(
  mail_uuid UUID NOT NULL,
  environment TEXT NOT NULL,
  email TEXT NOT NULL,
  event TEXT NULL,
  ip TEXT NULL,
  mc_stats TEXT NULL,
  phase_id TEXT NULL,
  send_at BIGINT NULL,
  sg_content_type TEXT NULL,
  sg_event_id TEXT NULL,
  sg_message_id TEXT NULL,
  sg_template_id TEXT NULL,
  sg_template_name TEXT NULL,
  singlesend_id TEXT NULL,
  singlesend_name TEXT NULL,
  template_hash TEXT NULL,
  template_id TEXT NULL,
  template_version_id TEXT NULL,
  timestamp BIGINT NULL,
  useragent TEXT NULL,
  processed BOOLEAN NULL
);

CREATE INDEX ON sendgrid_mail_event(mail_uuid);
CREATE INDEX ON sendgrid_mail_event(email);