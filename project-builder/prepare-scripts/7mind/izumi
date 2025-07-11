#!/bin/bash

ProjectDir="${OPENCB_PROJECT_DIR:?OPENCB_PROJECT_DIR is not defined}"
cd $ProjectDir
if which cs >/dev/null 2>&1; then
  ./sbtgen.sc
else
  echo "Not found coursier, trying to install"
  curl -fL "https://github.com/coursier/launchers/raw/master/cs-x86_64-pc-linux.gz" | gzip -d > ./cs
  chmod +x ./cs
  PATH=$PATH:$PWD ./sbtgen.sc
fi