# Example using migratus with Postgres

This is an example project that uses migratus to apply migrations to a PostgreSQL database.

TODO: Add instructions on how to use migratus via code.

## Using migratus via cli

### Setup your database

If you have an existing database, skip this step.
This guide uses docker for PostgreSQL setup, but you can setup PostgreSQL any way you like.

Bellow is a short guide on how to manage a PostgreSQL db as a container for the purpose of the guide.

For more information on PostgreSQL see [postgres image](https://hub.docker.com/_/postgres/)

```shell
# Run a postgresql instance as a container named migratus-pg.
# We ask PostgreSQL to create a database named migratus_example_db
docker run --name migratus-pg --detach -p 5432:5432 \
    -e POSTGRES_PASSWORD=migrate-me \
    -e POSTGRES_DB=migratus_example_db \
    -v migratus_data:/var/lib/postgresql/data \
    postgres:latest

# If all is well, we should see the container running
docker ps

> CONTAINER ID   IMAGE             COMMAND                  CREATED          STATUS          PORTS                                       NAMES
> c37a91d27631   postgres:latest   "docker-entrypoint.s…"   23 seconds ago   Up 23 seconds   0.0.0.0:5432->5432/tcp, :::5432->5432/tcp   migratus-pg

# View the data volume for postgres
docker volume ls

# And we can view the logs (in another terminal perhaps ?!)
docker logs -f migratus-pg

# You can stop and start the container
docker container stop migratus-pg
docker container start migratus-pg

# We can remove the container once you are done, or if you want to reset everything
docker container rm --force --volumes migratus-pg
```

### Setup migratus cli

We use migratus cli via `deps.edn` aliases.
See the `deps.edn` file in this project for details.

The file should look like this
```clojure
{:paths ["resources"]
 :deps {io.github.yogthos/migratus  {:mvn/version "RELEASE"}
        org.postgresql/postgresql {:mvn/version "42.6.0"}}
 :aliases
 {:migratus {:jvm-opts ["-Dclojure.main.report=stderr"]
             :main-opts ["-m" "migratus.cli"]}}}
```

If you have such a configuration, we can use `clojure` or `clj` tool to drive the CLI.
Since Migratus is a clojure library, we need to run it via clojure like this `clojure -M:migratus --help`

There is also a bash script `migratus.sh` that does the same: `./migratus.sh --help`


Commands with migratus

```shell

# We export the configuration as env variable, but we can use cli or file as well
export MIGRATUS_CONFIG='{:store :database :db {:jdbcUrl "jdbc:postgresql://localhost:5432/migratus_example_db?user=postgres&password=migrate-me"}}'

clojure -M:migratus status

```