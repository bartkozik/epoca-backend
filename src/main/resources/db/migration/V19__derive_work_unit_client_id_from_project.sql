CREATE FUNCTION public.set_work_unit_client_id_from_project()
    RETURNS trigger
    LANGUAGE plpgsql
AS
$$
DECLARE
    derived_client_id UUID;
BEGIN
    SELECT project.client_id INTO derived_client_id
    FROM public.project
    WHERE project.id = NEW.project_id;

    IF FOUND THEN
        NEW.client_id := derived_client_id;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER work_unit_client_id_from_project
    BEFORE INSERT OR UPDATE ON public.work_unit
    FOR EACH ROW
EXECUTE FUNCTION public.set_work_unit_client_id_from_project();

UPDATE public.work_unit wu
SET client_id = p.client_id
FROM public.project p
WHERE p.id = wu.project_id
  AND wu.client_id IS DISTINCT FROM p.client_id;

DO
$$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.work_unit wu
        JOIN public.project p ON p.id = wu.project_id
        WHERE wu.client_id IS DISTINCT FROM p.client_id
    ) THEN
        RAISE EXCEPTION 'work_unit.client_id reconciliation failed';
    END IF;
END;
$$;
