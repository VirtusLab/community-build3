#!/usr/bin/env bash
set -e

if [ $# -ne 3 ]; then
  echo "Wrong number of script arguments"
  exit 1
fi

repo="$1" # e.g. https://github.com/scala/scala3.git
rev="$2" # e.g. 1.0.2
repoDir="$3" # e.g. repo

echo '##################################'
echo Clonning $repo into $repoDir using revision $rev
echo '##################################'

rm -rf "$repoDir" || (sleep 1 && rm -rf "$repoDir") || true
branch=""
if [ -n "$rev" ]; then
  branch="-b $rev"
fi

depth=""
if [ -n "$OPENCB_GIT_DEPTH" ]; then
  depth="--depth=$OPENCB_GIT_DEPTH"
fi

if ! git clone --quiet --recurse-submodules "$repo" "$repoDir" $branch $depth; then
  echo "Initial clone with submodules failed, retrying with fallbacks..."

  # Clean up any partial clone from the failed attempt
  rm -rf "$repoDir" || true

  git clone --quiet "$repo" "$repoDir" $branch
  cd $repoDir
  git fetch --shallow-since=2021-05-13 || true
  git fetch --tags || true
  
  if [ -n "$rev" ]; then 
    git checkout "$rev"
  fi
  
  if ! git submodule update --init --recursive; then
    echo "Submodule update via SSH failed, retrying with HTTPS remap..." >&2

    git config url."https://github.com/".insteadOf "git@github.com:"
    git submodule sync --recursive
    git submodule update --init --recursive
  fi
fi