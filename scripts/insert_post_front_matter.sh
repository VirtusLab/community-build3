#!/usr/bin/env bash

filename="$1"
title="$2"

if [ -z "$filename" ]; then
    echo "Provide a filename as an argument."
    exit 1
fi

if [ -z "$title" ]; then
    echo "Provide a title as an argument."
    exit 1
fi

date=$(date -u "+%Y-%m-%d %H:%M:%S")

echo "---" > "$filename.tmp"
echo "layout: post" >> "$filename.tmp"
echo "title: \"$title\"" >> "$filename.tmp"
echo "date: $date" >> "$filename.tmp"
echo "---" >> "$filename.tmp"
echo "" >> "$filename.tmp"

cat "$filename" >> "$filename.tmp"
mv "$filename.tmp" "$filename"
