-- :repeatable
CREATE OR REPLACE FUNCTION quux_set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = now();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;
--;;
DROP TRIGGER IF EXISTS quux_set_updated_at ON quux;
--;;
CREATE TRIGGER quux_set_updated_at
BEFORE UPDATE ON quux
FOR EACH ROW
EXECUTE PROCEDURE quux_set_updated_at();
