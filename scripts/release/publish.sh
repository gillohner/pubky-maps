#!/usr/bin/env bash
# Publishes dist/ as a GitHub release (release.yml, publish job; GH_TOKEN from the job).
#
# - `edge` and `dev`: a pre-release whose tag moves to this commit. The release stays up while its
#   files are replaced; the update feed and SHA256SUMS go last, so a feed never names a file that is
#   not there yet, and files of older builds are removed only after the new feed is up.
# - `v<x.y.z>`: the release of that existing tag, marked latest.
#
# GitHub may refuse to let a job's own token move a tag across commits that change workflow files.
# Then the tag is deleted and made again at this commit; if that is refused too, it is put back where
# it was and the run only warns (the release's files are still this build's; its title names it).
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

case $tag in
  edge | dev)
    if gh release view "$tag" -R "$repo" > /dev/null 2>&1; then
      ref="repos/$repo/git/refs/tags/$tag"
      if ! gh api -X PATCH "$ref" -f sha="$sha" -F force=true > /dev/null; then
        old=$(gh api "repos/$repo/git/ref/tags/$tag" --jq .object.sha 2> /dev/null || true)
        [ -z "$old" ] || gh api -X DELETE "$ref" > /dev/null || true
        if gh api -X POST "repos/$repo/git/refs" -f ref="refs/tags/$tag" -f sha="$sha" > /dev/null; then
          echo "the $tag tag was made again at ${sha::7}"
        else
          if [ -n "$old" ] && ! gh api "repos/$repo/git/ref/tags/$tag" > /dev/null 2>&1; then
            gh api -X POST "repos/$repo/git/refs" -f ref="refs/tags/$tag" -f sha="$old" > /dev/null ||
              echo "::warning::the $tag tag is gone and could not be put back at ${old::7}"
          fi
          echo "::warning::the $tag tag could not move to ${sha::7}; the files below are still this build's"
        fi
      fi
      # A release whose tag was gone for a moment is a draft again: publish it.
      gh release edit "$tag" -R "$repo" --title "$title" --notes-file "$notes" --prerelease --latest=false --draft=false
    else
      gh release create "$tag" -R "$repo" --prerelease --latest=false --target "$sha" --title "$title" --notes-file "$notes"
    fi
    mapfile -t files < <(find "$dist" -maxdepth 1 -type f ! -name "$feed" ! -name SHA256SUMS | sort)
    upload "${files[@]}"
    upload "$dist/$feed" "$dist/SHA256SUMS"
    keep=$(cd "$dist" && ls)
    for a in $(gh release view "$tag" -R "$repo" --json assets -q '.assets[].name'); do
      echo "$keep" | grep -qxF "$a" || gh release delete-asset "$tag" "$a" -R "$repo" --yes
    done
    ;;
  v*)
    if ! gh release view "$tag" -R "$repo" > /dev/null 2>&1; then
      gh release create "$tag" -R "$repo" --verify-tag --latest --title "$title" --notes-file "$notes"
    fi
    mapfile -t files < <(find "$dist" -maxdepth 1 -type f ! -name "$feed" ! -name SHA256SUMS | sort)
    upload "${files[@]}"
    upload "$dist/$feed" "$dist/SHA256SUMS"
    ;;
  *)
    echo "::error::publish: no release channel for tag $tag" >&2
    exit 1
    ;;
esac
gh release view "$tag" -R "$repo" --json assets -q '.assets[] | "\(.name) \(.size)"'
