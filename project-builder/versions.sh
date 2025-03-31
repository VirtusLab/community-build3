#!/usr/bin/env bash

function isBinVersionGreaterThen() {
    version1=$1
    version2=$2

    if [[ $version1 == $version2 ]]; then
        return 1
    fi

    IFS='.' read -ra v1 <<< "$version1"
    IFS='.' read -ra v2 <<< "$version2"

    # Compare major version
    if [[ ${v1[0]} -lt ${v2[0]} ]]; then
        return 1
    elif [[ ${v1[0]} -gt ${v2[0]} ]]; then
        return 0
    fi

    # Major versions are equal, compare minor version
    if [[ ${v1[1]} -lt ${v2[1]} ]]; then
        return 1
    elif [[ ${v1[1]} -gt ${v2[1]} ]]; then
        return 0
    fi
}
