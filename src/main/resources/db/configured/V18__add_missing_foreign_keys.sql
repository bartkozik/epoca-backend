ALTER TABLE "${flyway:defaultSchema}".refresh_token
    ADD CONSTRAINT refresh_token_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES "${flyway:defaultSchema}"."user" (id) ON DELETE NO ACTION;

ALTER TABLE "${flyway:defaultSchema}".user_identity
    ADD CONSTRAINT user_identity_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES "${flyway:defaultSchema}"."user" (id) ON DELETE NO ACTION;

ALTER TABLE "${flyway:defaultSchema}".user_login_token
    ADD CONSTRAINT user_login_token_user_identity_id_fkey
        FOREIGN KEY (user_identity_id) REFERENCES "${flyway:defaultSchema}".user_identity (id) ON DELETE CASCADE;

ALTER TABLE "${flyway:defaultSchema}".user_password
    ADD CONSTRAINT user_password_user_identity_id_fkey
        FOREIGN KEY (user_identity_id) REFERENCES "${flyway:defaultSchema}".user_identity (id) ON DELETE CASCADE;

ALTER TABLE "${flyway:defaultSchema}".sendgrid_mail_event
    ADD CONSTRAINT sendgrid_mail_event_mail_uuid_fkey
        FOREIGN KEY (mail_uuid) REFERENCES "${flyway:defaultSchema}".mail_history (mail_uuid) ON DELETE NO ACTION;

CREATE INDEX user_login_token_user_identity_id_idx ON "${flyway:defaultSchema}".user_login_token (user_identity_id);
