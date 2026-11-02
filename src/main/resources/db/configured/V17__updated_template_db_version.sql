CREATE TABLE user_identity (
    id UUID NOT NULL PRIMARY KEY,
    user_id UUID NOT NULL,
    type TEXT NOT NULL,
    value TEXT NOT NULL,
    main BOOLEAN NOT NULL,
    device_id TEXT NULL,
    confirmed BOOLEAN NOT NULL,
    confirmation_code TEXT NULL,
    created_at  TIMESTAMP NOT NULL
);

CREATE INDEX ON user_identity (user_id, type, value);
CREATE UNIQUE INDEX ON user_identity (value);

ALTER TABLE "user" DROP COLUMN email;

DROP TABLE user_login_token;
CREATE TABLE user_login_token(
  id UUID PRIMARY KEY NOT NULL,
  user_identity_id UUID NOT NULL,
  login_token TEXT NOT NULL,
  valid_to TIMESTAMP NULL
);

DROP TABLE user_password;
CREATE TABLE user_password(
  user_identity_id UUID PRIMARY KEY NOT NULL,
  password TEXT NOT NULL
);
CREATE UNIQUE INDEX ON  user_password(user_identity_id);

DROP TABLE refresh_token;
CREATE TABLE refresh_token(
  user_id UUID NOT NULL,
  device_id TEXT NOT NULL,
  user_agent TEXT NULL,
  value TEXT NOT NULL,
  authed_by UUID[] NOT NULL,
  last_authed_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL,
  expired_at TIMESTAMP NULL,
  PRIMARY KEY (user_id, device_id)
);
CREATE UNIQUE INDEX ON  user_login_token(login_token);

CREATE TABLE invitation (
    id               UUID PRIMARY KEY,
    identity_value   TEXT NOT NULL,
    identity_type    TEXT NOT NULL,
    roles            TEXT[] NOT NULL,
    expire_at        TIMESTAMP NULL,
    accept_at        TIMESTAMP NULL,
    reject_at        TIMESTAMP NULL,
    organization_id  UUID  NOT NULL REFERENCES organization(id),
    created_at       TIMESTAMP NOT NULL,
    created_by       UUID      NOT NULL,
    modified_at      TIMESTAMP NOT NULL,
    modified_by      UUID      NOT NULL,
    archived_at      TIMESTAMP NULL
);

DROP TABLE mail_history;
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

DROP TABLE sendgrid_mail_event;
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
  processed BOOLEAN NULL,
  reason TEXT NULL
);

CREATE INDEX ON sendgrid_mail_event(mail_uuid);
CREATE INDEX ON sendgrid_mail_event(email);

CREATE TABLE quartz_job_details
  (
    SCHED_NAME VARCHAR(120) NOT NULL,
    JOB_NAME  VARCHAR(200) NOT NULL,
    JOB_GROUP VARCHAR(200) NOT NULL,
    DESCRIPTION VARCHAR(250) NULL,
    JOB_CLASS_NAME   VARCHAR(250) NOT NULL,
    IS_DURABLE BOOL NOT NULL,
    IS_NONCONCURRENT BOOL NOT NULL,
    IS_UPDATE_DATA BOOL NOT NULL,
    REQUESTS_RECOVERY BOOL NOT NULL,
    JOB_DATA BYTEA NULL,
    PRIMARY KEY (SCHED_NAME,JOB_NAME,JOB_GROUP)
);

CREATE TABLE quartz_triggers
  (
    SCHED_NAME VARCHAR(120) NOT NULL,
    TRIGGER_NAME VARCHAR(200) NOT NULL,
    TRIGGER_GROUP VARCHAR(200) NOT NULL,
    JOB_NAME  VARCHAR(200) NOT NULL,
    JOB_GROUP VARCHAR(200) NOT NULL,
    DESCRIPTION VARCHAR(250) NULL,
    NEXT_FIRE_TIME BIGINT NULL,
    PREV_FIRE_TIME BIGINT NULL,
    PRIORITY INTEGER NULL,
    TRIGGER_STATE VARCHAR(16) NOT NULL,
    TRIGGER_TYPE VARCHAR(8) NOT NULL,
    START_TIME BIGINT NOT NULL,
    END_TIME BIGINT NULL,
    CALENDAR_NAME VARCHAR(200) NULL,
    MISFIRE_INSTR SMALLINT NULL,
    JOB_DATA BYTEA NULL,
    PRIMARY KEY (SCHED_NAME,TRIGGER_NAME,TRIGGER_GROUP),
    FOREIGN KEY (SCHED_NAME,JOB_NAME,JOB_GROUP)
	REFERENCES quartz_job_details(SCHED_NAME,JOB_NAME,JOB_GROUP)
);

CREATE TABLE quartz_simple_triggers
  (
    SCHED_NAME VARCHAR(120) NOT NULL,
    TRIGGER_NAME VARCHAR(200) NOT NULL,
    TRIGGER_GROUP VARCHAR(200) NOT NULL,
    REPEAT_COUNT BIGINT NOT NULL,
    REPEAT_INTERVAL BIGINT NOT NULL,
    TIMES_TRIGGERED BIGINT NOT NULL,
    PRIMARY KEY (SCHED_NAME,TRIGGER_NAME,TRIGGER_GROUP),
    FOREIGN KEY (SCHED_NAME,TRIGGER_NAME,TRIGGER_GROUP)
	REFERENCES quartz_triggers(SCHED_NAME,TRIGGER_NAME,TRIGGER_GROUP)
);

CREATE TABLE quartz_cron_triggers
  (
    SCHED_NAME VARCHAR(120) NOT NULL,
    TRIGGER_NAME VARCHAR(200) NOT NULL,
    TRIGGER_GROUP VARCHAR(200) NOT NULL,
    CRON_EXPRESSION VARCHAR(120) NOT NULL,
    TIME_ZONE_ID VARCHAR(80),
    PRIMARY KEY (SCHED_NAME,TRIGGER_NAME,TRIGGER_GROUP),
    FOREIGN KEY (SCHED_NAME,TRIGGER_NAME,TRIGGER_GROUP)
	REFERENCES quartz_triggers(SCHED_NAME,TRIGGER_NAME,TRIGGER_GROUP)
);

CREATE TABLE quartz_simprop_triggers
  (
    SCHED_NAME VARCHAR(120) NOT NULL,
    TRIGGER_NAME VARCHAR(200) NOT NULL,
    TRIGGER_GROUP VARCHAR(200) NOT NULL,
    STR_PROP_1 VARCHAR(512) NULL,
    STR_PROP_2 VARCHAR(512) NULL,
    STR_PROP_3 VARCHAR(512) NULL,
    INT_PROP_1 INT NULL,
    INT_PROP_2 INT NULL,
    LONG_PROP_1 BIGINT NULL,
    LONG_PROP_2 BIGINT NULL,
    DEC_PROP_1 NUMERIC(13,4) NULL,
    DEC_PROP_2 NUMERIC(13,4) NULL,
    BOOL_PROP_1 BOOL NULL,
    BOOL_PROP_2 BOOL NULL,
    PRIMARY KEY (SCHED_NAME,TRIGGER_NAME,TRIGGER_GROUP),
    FOREIGN KEY (SCHED_NAME,TRIGGER_NAME,TRIGGER_GROUP)
    REFERENCES quartz_triggers(SCHED_NAME,TRIGGER_NAME,TRIGGER_GROUP)
);

CREATE TABLE quartz_blob_triggers
  (
    SCHED_NAME VARCHAR(120) NOT NULL,
    TRIGGER_NAME VARCHAR(200) NOT NULL,
    TRIGGER_GROUP VARCHAR(200) NOT NULL,
    BLOB_DATA BYTEA NULL,
    PRIMARY KEY (SCHED_NAME,TRIGGER_NAME,TRIGGER_GROUP),
    FOREIGN KEY (SCHED_NAME,TRIGGER_NAME,TRIGGER_GROUP)
        REFERENCES quartz_triggers(SCHED_NAME,TRIGGER_NAME,TRIGGER_GROUP)
);

CREATE TABLE quartz_calendars
  (
    SCHED_NAME VARCHAR(120) NOT NULL,
    CALENDAR_NAME  VARCHAR(200) NOT NULL,
    CALENDAR BYTEA NOT NULL,
    PRIMARY KEY (SCHED_NAME,CALENDAR_NAME)
);


CREATE TABLE quartz_paused_trigger_grps
  (
    SCHED_NAME VARCHAR(120) NOT NULL,
    TRIGGER_GROUP  VARCHAR(200) NOT NULL,
    PRIMARY KEY (SCHED_NAME,TRIGGER_GROUP)
);

CREATE TABLE quartz_fired_triggers
  (
    SCHED_NAME VARCHAR(120) NOT NULL,
    ENTRY_ID VARCHAR(95) NOT NULL,
    TRIGGER_NAME VARCHAR(200) NOT NULL,
    TRIGGER_GROUP VARCHAR(200) NOT NULL,
    INSTANCE_NAME VARCHAR(200) NOT NULL,
    FIRED_TIME BIGINT NOT NULL,
    SCHED_TIME BIGINT NOT NULL,
    PRIORITY INTEGER NOT NULL,
    STATE VARCHAR(16) NOT NULL,
    JOB_NAME VARCHAR(200) NULL,
    JOB_GROUP VARCHAR(200) NULL,
    IS_NONCONCURRENT BOOL NULL,
    REQUESTS_RECOVERY BOOL NULL,
    PRIMARY KEY (SCHED_NAME,ENTRY_ID)
);

CREATE TABLE quartz_scheduler_state
  (
    SCHED_NAME VARCHAR(120) NOT NULL,
    INSTANCE_NAME VARCHAR(200) NOT NULL,
    LAST_CHECKIN_TIME BIGINT NOT NULL,
    CHECKIN_INTERVAL BIGINT NOT NULL,
    PRIMARY KEY (SCHED_NAME,INSTANCE_NAME)
);

CREATE TABLE quartz_locks
  (
    SCHED_NAME VARCHAR(120) NOT NULL,
    LOCK_NAME  VARCHAR(40) NOT NULL,
    PRIMARY KEY (SCHED_NAME,LOCK_NAME)
);

create index idx_j_req_recovery on quartz_job_details(SCHED_NAME,REQUESTS_RECOVERY);
create index idx_j_grp on quartz_job_details(SCHED_NAME,JOB_GROUP);

create index idx_t_j on quartz_triggers(SCHED_NAME,JOB_NAME,JOB_GROUP);
create index idx_t_jg on quartz_triggers(SCHED_NAME,JOB_GROUP);
create index idx_t_c on quartz_triggers(SCHED_NAME,CALENDAR_NAME);
create index idx_t_g on quartz_triggers(SCHED_NAME,TRIGGER_GROUP);
create index idx_t_state on quartz_triggers(SCHED_NAME,TRIGGER_STATE);
create index idx_t_n_state on quartz_triggers(SCHED_NAME,TRIGGER_NAME,TRIGGER_GROUP,TRIGGER_STATE);
create index idx_t_n_g_state on quartz_triggers(SCHED_NAME,TRIGGER_GROUP,TRIGGER_STATE);
create index idx_t_next_fire_time on quartz_triggers(SCHED_NAME,NEXT_FIRE_TIME);
create index idx_t_nft_st on quartz_triggers(SCHED_NAME,TRIGGER_STATE,NEXT_FIRE_TIME);
create index idx_t_nft_misfire on quartz_triggers(SCHED_NAME,MISFIRE_INSTR,NEXT_FIRE_TIME);
create index idx_t_nft_st_misfire on quartz_triggers(SCHED_NAME,MISFIRE_INSTR,NEXT_FIRE_TIME,TRIGGER_STATE);
create index idx_t_nft_st_misfire_grp on quartz_triggers(SCHED_NAME,MISFIRE_INSTR,NEXT_FIRE_TIME,TRIGGER_GROUP,TRIGGER_STATE);

create index idx_ft_trig_inst_name on quartz_fired_triggers(SCHED_NAME,INSTANCE_NAME);
create index idx_ft_inst_job_req_rcvry on quartz_fired_triggers(SCHED_NAME,INSTANCE_NAME,REQUESTS_RECOVERY);
create index idx_ft_j_g on quartz_fired_triggers(SCHED_NAME,JOB_NAME,JOB_GROUP);
create index idx_ft_jg on quartz_fired_triggers(SCHED_NAME,JOB_GROUP);
create index idx_ft_t_g on quartz_fired_triggers(SCHED_NAME,TRIGGER_NAME,TRIGGER_GROUP);
create index idx_ft_tg on quartz_fired_triggers(SCHED_NAME,TRIGGER_GROUP);


DROP TABLE organization_user_email;



