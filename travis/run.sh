#!/usr/bin/env bash

lein doo node test-project-test once

if [ ! -z "$AWS_SECRET_ACCESS_KEY" ] ; then
  lein cljs-lambda default-iam-role
  lein cljs-lambda deploy :region us-west-2
  lein cljs-lambda invoke work-magic \
       '{"magic-word": "test-project-token", "spell": "delay"}' \
       :region us-west-2
  lein cljs-lambda update-config work-magic :memory-size 512 :region us-west-2
else
  echo "No AWS key, exiting quietly"
fi
