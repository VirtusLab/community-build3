#!/usr/bin/env bash
set -e

scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"

TAG_NAME=latest
imageName=ghcr.io/virtuslab/scala-community-build-dashboard

# Target platform for Kubernetes (linux/amd64)
PLATFORM="${PLATFORM:-linux/amd64}"

echo "Building dashboard image: $imageName:$TAG_NAME"
echo "Target platform: $PLATFORM"

# Ensure buildx builder exists with multi-platform support
if ! docker buildx inspect multiplatform-builder &>/dev/null; then
  echo "Creating buildx builder for multi-platform builds..."
  docker buildx create --name multiplatform-builder --use --bootstrap
else
  docker buildx use multiplatform-builder
fi

# Build and push in one step (buildx doesn't keep local images for cross-platform)
# Use --load for local testing (only works for native platform)
# Use --push to build and push directly to registry
# --push for registry, --load for local (local only works for native platform)
OUTPUT_FLAG=$([ "${PUSH:-true}" = "true" ] && echo "--push" || echo "--load")

docker buildx build \
  --platform "$PLATFORM" \
  --cache-from "type=registry,ref=$imageName:buildcache" \
  --cache-to "type=registry,ref=$imageName:buildcache,mode=max" \
  -t "$imageName:$TAG_NAME" \
  -t "$imageName:latest" \
  $OUTPUT_FLAG \
  "$scriptDir/../dashboard"

