#!/usr/bin/env bash

readonly IS_EQ=0
readonly IS_GT=1
readonly IS_LT=255
# Compare two semantic versions using up to 3 segments (major, minor, patch)
# Returns (exit code) 0 if both versions are equal
# Returns (exit code) -1 if first version is smaller
# Returns (exit code) 1 if first version is greater
function _compareVersionParts() {
    local version1="$1"
    local version2="$2"
    local parts="${3:-3}"  # how many parts to compare (1..3)

    # clamp to [1,3]
    if (( parts < 1 )); then 
        parts=1
    elif (( parts > 3 )); then 
        parts=3;
    fi

    # split on '.'
    local IFS='.'
    local -a v1 v2
    read -r -a v1 <<< "$version1"
    read -r -a v2 <<< "$version2"

    # normalize first 3 numeric parts; strip any '-' suffix from the 3rd
    for i in 0 1 2; do
        local a="${v1[$i]:-0}"
        local b="${v2[$i]:-0}"
        if (( i == 2 )); then
            a="${a%%-*}"
            b="${b%%-*}"
        fi
        [[ -z "$a" ]] && a=0
        [[ -z "$b" ]] && b=0
        v1[$i]="$a"
        v2[$i]="$b"
    done

    # compare up to `parts`
    for (( i=0; i<parts; i++ )); do
        if (( ${v1[$i]} < ${v2[$i]} )); then
            return $IS_LT   # (255)
        elif (( ${v1[$i]} > ${v2[$i]} )); then
            return $IS_GT
        fi
    done

    return $IS_EQ
}


# Compare two semantic versions using major and minor segments.
# Returns (exit code) 0 if both versions are equal
# Returns (exit code) -1 if first version is smaller
# Returns (exit code) 1 if first version is greater
function compareBinVersion() {
    _compareVersionParts $1 $2 2
    return $?
}

function compareVersion() {
    _compareVersionParts $1 $2 3
    return $?
}

# Check if the first provided semantic version is larger then second one by comparing the major and minor segments.
function isBinVersionGreaterThan() {
  compareBinVersion "$1" "$2"
  case $? in
    $IS_GT)  return 0 ;;
    *)    return 1 ;;
  esac
}

function isBinVersionGreaterOrEqual() {
  compareBinVersion "$1" "$2"
  case $? in
    $IS_EQ|$IS_GT)  return 0 ;; 
    *)         return 1 ;;
  esac
}

# Check if the first provided semantic version is larger then second one by comparing the major, minor an patch (rc suffixes are ignroed).
function isVersionGreaterOrEqual() {
  compareVersion "$1" "$2"
  case $? in
    $IS_EQ|$IS_GT)  return 0 ;; 
    *)        return 1 ;;
  esac
}

# isVersionInRange <version> <min> <max> [parts=3]
# Returns 0 (true) if min <= version <= max, else 1 (false).
# If <min> is empty -> no lower bound. If <max> is empty -> no upper bound.
function _isVersionInRange() {
  local ver="$1"
  local min="$2"
  local max="$3"
  local parts="${4:-3}"

  # lower bound: ver < min  -> not in range
  if [[ -n "$min" ]]; then
    compareVersion "$ver" "$min" "$parts"
    case $? in
      $IS_LT) return 1 ;; # ver < min
      $IS_EQ|$IS_GT) ;;
      *)   return 2 ;;# unexpected compareVersion code
    esac
  fi

  # upper bound: ver > max -> not in range
  if [[ -n "$max" ]]; then
    compareVersion "$ver" "$max" "$parts"
    case $? in
      $IS_GT)   return 1 ;; # ver > max
      0|255) ;;        # ver == max or ver < max -> ok
      *)   return 2 ;;
    esac
  fi

  return 0
}

# isVersionInRange <version> <min> <max>
# Returns 0 (true) if min <= version <= max, else 1 (false).
# If <min> is empty -> no lower bound. If <max> is empty -> no upper bound.
function isVersionInRange() {
  _isVersionInRange $1 $2 $3 3
  return $?
}

# isBinVersionInRange <version> <min> <max> 
# Returns 0 (true) if min <= version <= max, else 1 (false).
# If <min> is empty -> no lower bound. If <max> is empty -> no upper bound.
function isBinVersionInRange() {
  _isVersionInRange $1 $2 $3 2
  return $?
}



