#!/usr/bin/env bash
set -e

if [ $# -ne 2 ]; then
  echo "Wrong number of script arguments"
  exit 1
fi

repo=$1 #'https://github.com/Stiuil06/deploySbt.git'
rev=$2 #'1.0.2'

echo '##################################'
echo Clonning $repo using revision $rev
echo '##################################'

git clone $repo repo -b $rev --depth 1
