-- comment-only section before the first statement (must be skipped, not run as an empty statement)
--;;
CREATE SCHEMA foo;
--;;
CREATE TABLE IF NOT EXISTS foo(id bigint, note varchar(20));
--;;
INSERT INTO foo(id, note) VALUES (1, '5--10');
