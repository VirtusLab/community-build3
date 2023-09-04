#!/usr/bin/env bash
set -e

if [ $# -ne 3 ]; then
  echo "Wrong number of script arguments"
  exit 1
fi

repo="$1" # e.g. https://github.com/lampepfl/dotty.git
rev="$2" # e.g. 1.0.2
repoDir="$3" # e.g. repo

echo '##################################'
echo Clonning $repo into $repoDir using revision $rev
echo '##################################'

rm -rf "$repoDir"
branch=""
if [ -n "$rev" ]; then
  branch="-b $rev"
fi
git clone --quiet --recurse-submodules "$repo" "$repoDir" $branch || 
  ( git clone --quiet --recurse-submodules "$repo" "$repoDir" && cd $repoDir && git checkout $rev )
