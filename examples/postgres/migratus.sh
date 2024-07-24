#!/bin/sh

# Run migratus passing all args to it
clojure -J-Dclojure.main.report=stderr -M:migratus "$@"