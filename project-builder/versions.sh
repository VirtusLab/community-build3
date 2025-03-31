#!/usr/bin/env bash

# Compare two semantic versions using major and minor segments.
# Returns (exit code) 0 if both versions are equal
# Returns (exit code) -1 if first version is smaller
# Returns (exit code) 1 if first version is greater
function compareBinVersion() {
    version1=$1
    version2=$2

    if [[ $version1 == $version2 ]]; then
        return 0
    fi

    IFS='.' read -ra v1 <<< "$version1"
    IFS='.' read -ra v2 <<< "$version2"

    # Compare major version
    if [[ ${v1[0]} -lt ${v2[0]} ]]; then
        return -1 # 255
    elif [[ ${v1[0]} -gt ${v2[0]} ]]; then
        return 1
    fi

    # Major versions are equal, compare minor version
    if [[ ${v1[1]} -lt ${v2[1]} ]]; then
        return -1
    elif [[ ${v1[1]} -gt ${v2[1]} ]]; then
        return 1
    fi
    
    return 0
}

# Check if the first provided semantic version is larger then second one by comparing the major and minor segments.
function isBinVersionGreaterThan() {
  compareBinVersion "$1" "$2"
  if [[ $? -eq 1 ]]; then
    return 0
  fi
  return 1 
}
