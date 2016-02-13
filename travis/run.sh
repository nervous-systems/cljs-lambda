#!/usr/bin/env bash

set -e
set -o pipefail

lein doo node ${PROJECT_DIR}-test once

if [ ! -z "$AWS_SECRET_ACCESS_KEY" ] ; then
  lein cljs-lambda default-iam-role
  lein cljs-lambda deploy work-magic :region us-west-2 :name work-magic-${PROJECT_DIR}
  lein cljs-lambda invoke work-magic-${PROJECT_DIR} \
       "{\"magic-word\": \"${PROJECT_DIR}-token\", \"spell\": \"delay\"}" \
       :region us-west-2 | grep :waited
  lein cljs-lambda update-config work-magic-${PROJECT_DIR} :memory-size 512 :region us-west-2
else
  echo "No AWS key, exiting quietly"
fi
