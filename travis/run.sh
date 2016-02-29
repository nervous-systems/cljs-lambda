#!/usr/bin/env bash

# It'd be nice to have some unit tests etc. for the junk in aws.clj,
# but the project map manipulation / cljsbuild integration is
# sufficiently complex and Leiningen-entangled that I don't feel like
# this is a terrible idea
set -e
set -o pipefail

lein doo node ${PROJECT_DIR}-test once

if [ ! -z "$AWS_SECRET_ACCESS_KEY" ] ; then
  FN_NAME=work-magic-${PROJECT_DIR}
  INVOKE="lein cljs-lambda invoke $FN_NAME"
  export AWS_DEFAULT_REGION=us-east-1

  lein cljs-lambda default-iam-role :quiet > /dev/null &

  echo "Delete functions in us-east-1 and us-west-2, if they exist"
  parallel ./delete-function.sh $FN_NAME ::: "" us-west-2

  wait
  echo "Deploy into us-west-2"
  lein cljs-lambda deploy work-magic :name $FN_NAME :region us-west-2 :quiet > /dev/null

  echo "Assert function doesn't exist in us-east-1"
  (echo "! ./get-function.sh $FN_NAME"; echo "./get-function.sh $FN_NAME us-west-2") | parallel

  echo "Assert that we can invoke the function in us-west-2 and get stripped output"
  FN_OUT=$($INVOKE \
             "{\"magic-word\": \"${PROJECT_DIR}-token\", \"spell\": \"delay\"}" \
             :region us-west-2 :quiet)
  if [[ $FN_OUT != \{:waited* ]]; then
    echo "Failed to retrieve invocation output $FN_OUT"
    exit 1
  fi

  echo "Assert that we can update the function's memory size"
  lein cljs-lambda update-config :name $FN_NAME :memory-size 512 :region us-west-2 :quiet
  FN_MEM_SIZE=$(aws lambda get-function-configuration \
                    --function-name $FN_NAME --query MemorySize --region us-west-2)
  if [[ $FN_MEM_SIZE != 512 ]]; then
    echo "Memory size doesn't match $FN_MEM_SIZE"
    exit 1
  fi

  echo "Deploy & publish an alias of the function into us-east-1"
  lein cljs-lambda deploy work-magic :name $FN_NAME :publish :alias jolly-roger :quiet

  echo "Assert that we can invoke the alias"
  (echo "  $INVOKE :version jolly-roger :quiet";
   echo "! $INVOKE :version go-home-roger :quiet") | parallel

  echo "Re-publish w/out aliasing, and assert we get a non-empty version"
  FN_VERSION=$(lein cljs-lambda deploy work-magic :name $FN_NAME :publish :quiet)
  if [[ -z $FN_VERSION ]]; then
    echo "Didn't get a version"
    exit 1
  fi

  echo "Create another alias, w/out publishing"
  lein cljs-lambda alias go-home-roger $FN_NAME $FN_VERSION

  echo "Assert we can invoke the previous alias using :qualifier & partial ARN"
  (echo "$INVOKE :qualifier $FN_VERSION :quiet";
   echo "$INVOKE:$FN_VERSION :quiet") | parallel
else
  echo "No AWS key, exiting quietly"
fi
