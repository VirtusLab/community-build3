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
git clone --quiet --recurse-submodules "$repo" "$repoDir" $branch || 
  ( git clone --quiet --recurse-submodules "$repo" "$repoDir" && cd $repoDir && git fetch --shallow-since=2021-05-13 && git fetch --tags && git checkout $rev )
