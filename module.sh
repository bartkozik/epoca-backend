#!/bin/bash

set -e

if [ $# -ne 1 ]
  then
    echo "Wong number of parameters provided. Usage ./module.sh [NAME]";
    exit 1;
fi

NAME=$1
LOWER_NAME=`echo "$NAME" | tr '[:upper:]' '[:lower:]'`
UPPER_NAME=`echo ${LOWER_NAME^}`

cp src/main/resources/db/migration/V2__roadmap.sql src/main/resources/db/migration/V3__$LOWER_NAME.sql
cp -r src/main/scala/pl/iterators/epoca/roadmap ./src/main/scala/pl/iterators/epoca/$LOWER_NAME
cp -r src/test/scala/pl/iterators/epoca/roadmap ./src/test/scala/pl/iterators/epoca/$LOWER_NAME


sed -i "s/roadmap/$LOWER_NAME/g" src/main/resources/db/migration/V3__$LOWER_NAME.sql

find ./src/main/scala/pl/iterators/epoca/${LOWER_NAME} -type f -exec sed -i "s/roadmap/$LOWER_NAME/g" {} \;
find ./src/main/scala/pl/iterators/epoca/${LOWER_NAME} -type f -exec sed -i "s/Roadmap/$UPPER_NAME/g" {} \;
for NAME in `find ./src/main/scala/pl/iterators/epoca/${LOWER_NAME} -type f -name '*Roadmap*'`;
do
  mv $NAME ${NAME/Roadmap/$UPPER_NAME}
done;

find ./src/test/scala/pl/iterators/epoca/${LOWER_NAME} -type f -exec sed -i "s/roadmap/$LOWER_NAME/g" {} \;
find ./src/test/scala/pl/iterators/epoca/${LOWER_NAME} -type f -exec sed -i "s/Roadmap/$UPPER_NAME/g" {} \;
for NAME in `find ./src/test/scala/pl/iterators/epoca/${LOWER_NAME} -type f -name '*Roadmap*'`;
do
  mv $NAME ${NAME/Roadmap/$UPPER_NAME}
done;

echo "done"


