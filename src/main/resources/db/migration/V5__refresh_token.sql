CREATE TABLE refresh_token(
  user_id UUID NOT NULL,
  device_id TEXT NOT NULL,
  value TEXT NOT NULL,
  PRIMARY KEY (user_id, device_id)
);
