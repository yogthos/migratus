CREATE TABLE IF NOT EXISTS quux(id bigint, name varchar(255), updated_at timestamp);
--;;
CREATE INDEX quux_name on quux(name);
