
oldLibPath = "/var/jenkins_home/common-lib"
newLibPath = "/var/lib/jenkins/common-lib"

// The directory has to be copied because of lack of write permissions in it
new ProcessBuilder(['bash', '-c', """
  rm -rf $newLibPath && \
  cp -rL $oldLibPath $newLibPath && \
  cd $newLibPath && \
  git init && \
  git config user.name "Scala3 Community Build" && \
  git config user.email "<>" && \
  git add --all && \
  git commit -m "init"
"""] as String[]).start()
