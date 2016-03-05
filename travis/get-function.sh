#!/usr/bin/env bash
if [[ ! -z $2 ]]; then REGION+="--region $2"; fi
aws lambda get-function --function-name $1 $REGION > /dev/null 2>&1
