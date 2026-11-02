ALTER TABLE public.refresh_token
    ADD CONSTRAINT refresh_token_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES public."user" (id) ON DELETE NO ACTION;

ALTER TABLE public.user_identity
    ADD CONSTRAINT user_identity_user_id_fkey
        FOREIGN KEY (user_id) REFERENCES public."user" (id) ON DELETE NO ACTION;

ALTER TABLE public.user_login_token
    ADD CONSTRAINT user_login_token_user_identity_id_fkey
        FOREIGN KEY (user_identity_id) REFERENCES public.user_identity (id) ON DELETE CASCADE;

ALTER TABLE public.user_password
    ADD CONSTRAINT user_password_user_identity_id_fkey
        FOREIGN KEY (user_identity_id) REFERENCES public.user_identity (id) ON DELETE CASCADE;

ALTER TABLE public.sendgrid_mail_event
    ADD CONSTRAINT sendgrid_mail_event_mail_uuid_fkey
        FOREIGN KEY (mail_uuid) REFERENCES public.mail_history (mail_uuid) ON DELETE NO ACTION;

CREATE INDEX user_login_token_user_identity_id_idx ON public.user_login_token (user_identity_id);
