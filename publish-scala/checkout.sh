#!/usr/bin/env bash
set -e

if [ $# -ne 3 ]; then
  echo "Wrong number of script arguments"
  exit 1
fi

repo="$1" # e.g. https://github.com/Stiuil06/deploySbt.git
rev="$2" # e.g. 1.0.2
repoDir="$3" # e.g. repo

echo '##################################'
echo Clonning $repo into $repoDir using revision $rev
echo '##################################'

rm -rf "$repoDir"
if [ -n "$rev" ]; then
  git clone "$repo" "$repoDir" -b "$rev" --depth 1
else
  git clone "$repo" "$repoDir" --depth 1
fi
