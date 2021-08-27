repoPath="$1"

cd $repoPath &&
  git init --initial-branch=main &&
  git config user.name "Scala3 Community Build" &&
  git config user.email "<>" &&
  git add --all &&
  git commit -m "init"
