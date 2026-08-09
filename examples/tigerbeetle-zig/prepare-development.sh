#!/bin/sh
set -eu

development_root=${1:?development project path is required}
data_path=${2:?development data path is required}

mkdir -p "$(dirname "$data_path")"
data_path="$(cd "$(dirname "$data_path")" && pwd)/$(basename "$data_path")"
development_root="$(cd "$development_root" && pwd)"

cd "$development_root"
zig build

if [ ! -f "$data_path" ]; then
  ./tigerbeetle format \
    --cluster=0 \
    --replica=0 \
    --replica-count=1 \
    --development \
    "$data_path"
fi
