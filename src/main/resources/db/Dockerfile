FROM iterators/flyway-docker@sha256:7a31476f42775c576b14e155cb4243654d2962e45ab9ddf88cd00df337edeae1

COPY ./migration /sql
COPY ./configured /sql-configured
COPY ./bootstrap /sql-bootstrap
COPY ./cmd.sh /cmd.sh
RUN chmod 755 /cmd.sh
