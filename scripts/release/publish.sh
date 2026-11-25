#!/usr/bin/env bash
# Publishes dist/ as a GitHub release (release.yml, publish job; GH_TOKEN from the job).
#
# - `edge` and `dev`: a pre-release whose tag moves to this commit. A build older than the one the
#   release already has (a re-run of an old run) publishes nothing. The release stays up while its
#   files are replaced; the update feed and SHA256SUMS go last, so a feed never names a file that is
#   not there yet, and files of older builds are removed only after the new feed is up.
# - `v<x.y.z>`: the release of that existing tag, made as a draft, filled, then published, so
#   releases/latest/download/update.json never points at a release without its feed. GitHub picks
#   which release is "latest" by version (a backport does not take it over).
#
# GitHub refuses to let a job's own token move a tag across commits that change workflow files. Only
# then the tag is deleted and made again at this commit; if that is refused too, it is put back and
# the run only warns (the release's files are still this build's; its title names it).
#
# Usage: publish.sh <tag> <title> <notes file> <dist dir> <feed file name>
set -euo pipefail
tag=$1 title=$2 notes=$3 dist=$4 feed=$5
repo=$GITHUB_REPOSITORY
sha=$GITHUB_SHA

upload() {
  for i in 1 2 3 4 5; do
    gh release upload "$tag" "$@" --clobber -R "$repo" && return 0
    echo "::warning::upload failed (attempt $i); trying again"
    sleep $((i * 15))
  done
  return 1
}

feed_version_code() {
  python3 -I -c 'import json, sys; print(json.loads(json.load(sys.stdin)["feed"])["versionCode"])'
}

move_tag() {
  local ref="repos/$repo/git/refs/tags/$tag" err old
  if err=$(gh api -X PATCH "$ref" -f sha="$sha" -F force=true 2>&1 > /dev/null); then
    return 0
  fi
  echo "GitHub did not move the $tag tag: $err"
  if ! grep -qi "workflow" <<< "$err"; then
    echo "::error::the $tag tag could not move to ${sha::7}; nothing was published"
    exit 1
  fi
  old=$(gh api "repos/$repo/git/ref/tags/$tag" --jq .object.sha)
  gh api -X DELETE "$ref" > /dev/null
  if gh api -X POST "repos/$repo/git/refs" -f ref="refs/tags/$tag" -f sha="$sha" > /dev/null; then
    echo "the $tag tag was made again at ${sha::7}"
  else
    gh api -X POST "repos/$repo/git/refs" -f ref="refs/tags/$tag" -f sha="$old" > /dev/null ||
      echo "::warning::the $tag tag is gone and could not be put back at ${old::7}"
    echo "::warning::the $tag tag could not move to ${sha::7}; the files below are still this build's"
  fi
}

case $tag in
  edge | dev)
    ours=$(feed_version_code < "$dist/$feed")
    if gh release view "$tag" -R "$repo" > /dev/null 2>&1; then
      live=$(curl -fsSL --retry 3 "https://github.com/$repo/releases/download/$tag/$feed" 2> /dev/null | feed_version_code 2> /dev/null || echo 0)
      if [ "$live" -ge "$ours" ]; then
        echo "::warning::the $tag release already has versionCode $live (this build: $ours); nothing published"
        exit 0
      fi
      move_tag
      # A release whose tag was gone for a moment is a draft again: publish it, at this commit.
      gh release edit "$tag" -R "$repo" --title "$title" --notes-file "$notes" --target "$sha" \
        --prerelease --latest=false --draft=false
    else
      gh release create "$tag" -R "$repo" --prerelease --latest=false --target "$sha" --title "$title" --notes-file "$notes"
    fi
    mapfile -t files < <(find "$dist" -maxdepth 1 -type f ! -name "$feed" ! -name SHA256SUMS | sort)
    upload "${files[@]}"
    upload "$dist/$feed" "$dist/SHA256SUMS"
    keep=$(cd "$dist" && ls)
    for a in $(gh release view "$tag" -R "$repo" --json assets -q '.assets[].name'); do
      grep -qxF "$a" <<< "$keep" || gh release delete-asset "$tag" "$a" -R "$repo" --yes
    done
    ;;
  v*)
    if ! gh release view "$tag" -R "$repo" > /dev/null 2>&1; then
      gh release create "$tag" -R "$repo" --verify-tag --draft --title "$title" --notes-file "$notes"
    fi
    mapfile -t files < <(find "$dist" -maxdepth 1 -type f ! -name "$feed" ! -name SHA256SUMS | sort)
    upload "${files[@]}"
    upload "$dist/$feed" "$dist/SHA256SUMS"
    id=$(gh api "repos/$repo/releases/tags/$tag" --jq .id 2> /dev/null ||
      gh api "repos/$repo/releases" --jq ".[] | select(.tag_name == \"$tag\") | .id" | head -1)
    gh api -X PATCH "repos/$repo/releases/$id" -F draft=false -F prerelease=false -f make_latest=legacy > /dev/null
    ;;
  *)
    echo "::error::publish: no release channel for tag $tag" >&2
    exit 1
    ;;
esac
gh release view "$tag" -R "$repo" --json assets -q '.assets[] | "\(.name) \(.size)"'
