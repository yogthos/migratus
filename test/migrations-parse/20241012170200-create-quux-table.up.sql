-- test comment parsed correctly
CREATE TABLE -- first comment
quux -- second comment;
(id bigint,
 name varchar(255)); -- last comment
-- end
