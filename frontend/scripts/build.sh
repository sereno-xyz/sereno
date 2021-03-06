#!/usr/bin/env bash

source ~/.bashrc
set -ex

TAG=`git log -n 1 --pretty=format:%H -- ./`

yarn install

export NODE_ENV=production;

# Clean the output directory
npx gulp clean || exit 1;

npx shadow-cljs compile main;
npx shadow-cljs release main --config-merge "{:release-version \"${TAG}\"}" --source-maps $SHADOWCLJS_EXTRA_PARAMS;
npx gulp build || exit 1;
npx gulp dist:clean || exit 1;
npx gulp dist:copy || exit 1;
