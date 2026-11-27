#!/usr/bin/env bash
# Publishes dist/ as a GitHub release (release.yml, publish job; GH_TOKEN from the job).
#
# - `edge` and `dev`: a pre-release whose tag moves to the built commit (BUILD_SHA). A build with a
#   lower versionCode than the one the release already has (a re-run of an old run) publishes nothing;
#   neither does an edge build of a commit that does not come after the commit of edge's live build
#   (a run that kept its own, older commit: edge-commit.py). The release stays up while its files are
#   replaced; the update feed and SHA256SUMS go last, so a feed never names a file that is not there
#   yet. Only then the release's target commit, title and notes name this build, and only then the
#   files of older builds are removed: after a complete publish its target commit is the commit of the
#   live feed (the tag may have failed to move, below), which is what the edge check above and
#   edge-commit.py compare with. A run whose edit that names this build fails is red, and the release
#   names the build before and keeps its files until the next publish.
# - `v<x.y.z>`: the release of that existing tag, made as a draft, filled, then published, so
#   releases/latest/download/update.json never points at a release without its feed. GitHub picks
#   which release is "latest" by version (a backport does not take it over).
#
# GitHub refuses to let a job's own token move a tag across commits that change workflow files. Only
# then the tag is deleted and made again at this commit; if that is refused too, it is put back and
# the run only warns (the release's files are still this build's; its title names it).
#
# Uploads, `release edit` calls and the v* publish are tried again when GitHub fails them (each gives
# the same release when made twice); one that still fails after five tries fails the run. Making the
# release and deleting old files are single calls: a second try after a lost answer would fail on the
# release or file that the first one made or deleted.
#
# In GitHub Actions it writes `published=true` (or `false` when it published nothing) to the step's
# outputs, so the job that installs the published APK runs only when there is one.
#
# Usage: BUILD_SHA=<the commit dist/ was built from> publish.sh <tag> <title> <notes file> <dist dir> <feed file name>
# (an edge run builds main's head, which can be newer than the run's own commit: edge-commit.py)
set -euo pipefail
tag=$1 title=$2 notes=$3 dist=$4 feed=$5
repo=$GITHUB_REPOSITORY
sha=${BUILD_SHA:?the commit dist/ was built from}

# A change on GitHub (`gh <args>`), tried five times. Notes go to stderr, so a caller can drop the
# answer.
change_gh() {
  local i
  for i in 1 2 3 4 5; do
    gh "$@" && return 0
    if [ "$i" -lt 5 ]; then
      echo "::warning::gh $* failed (attempt $i); trying again" >&2
      sleep $((i * 15))
    fi
  done
  echo "::error::gh $* failed five times" >&2
  return 1
}

upload() {
  change_gh release upload "$tag" "$@" --clobber -R "$repo"
}

# A read from GitHub (`gh <args>`), tried three times; it fails the run when GitHub does not answer.
# Only the answer of the attempt that worked is printed: a failed `gh api` prints GitHub's error
# body on stdout.
read_gh() {
  local out i
  for i in 1 2 3; do
    if out=$(gh "$@"); then
      printf '%s\n' "$out"
      return 0
    fi
    echo "::warning::gh $1 $2 failed (attempt $i); trying again" >&2
    sleep $((i * 10))
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
        [ -z "${GITHUB_OUTPUT:-}" ] || echo "published=false" >> "$GITHUB_OUTPUT"
        exit 0
      fi
      if [ "$tag" = edge ]; then
        # Separate assignments, so a failed call fails the run instead of comparing an empty answer.
        # (gh release view also finds the release while a failed tag move left it a draft.)
        at=$(read_gh release view "$tag" -R "$repo" --json targetCommitish -q .targetCommitish)
        status=$(read_gh api "repos/$repo/compare/$at...$sha" --jq .status)
        if [ "$status" != ahead ] && [ "$status" != identical ]; then
          echo "::warning::the $tag release has a build of ${at::7}, and ${sha::7} does not come after it ($status); nothing published"
          [ -z "${GITHUB_OUTPUT:-}" ] || echo "published=false" >> "$GITHUB_OUTPUT"
          exit 0
        fi
      fi
      move_tag
      # A release whose tag was gone for a moment is a draft again: publish it.
      change_gh release edit "$tag" -R "$repo" --prerelease --latest=false --draft=false
    else
      gh release create "$tag" -R "$repo" --prerelease --latest=false --target "$sha" --title "$title" --notes-file "$notes"
    fi
    mapfile -t files < <(find "$dist" -maxdepth 1 -type f ! -name "$feed" ! -name SHA256SUMS | sort)
    upload "${files[@]}"
    upload "$dist/$feed" "$dist/SHA256SUMS"
    change_gh release edit "$tag" -R "$repo" --title "$title" --notes-file "$notes" --target "$sha"
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
    # From the list: GitHub's releases/tags/<tag> finds only published releases, not this draft.
    ids=$(read_gh api "repos/$repo/releases?per_page=100" --jq ".[] | select(.tag_name == \"$tag\") | .id")
    id=${ids%%$'\n'*}
    if [ -z "$id" ]; then
      echo "::error::GitHub lists no release of $tag" >&2
      exit 1
    fi
    change_gh api -X PATCH "repos/$repo/releases/$id" -F draft=false -F prerelease=false -f make_latest=legacy > /dev/null
    ;;
  *)
    echo "::error::publish: no release channel for tag $tag" >&2
    exit 1
    ;;
esac
gh release view "$tag" -R "$repo" --json assets -q '.assets[] | "\(.name) \(.size)"'
[ -z "${GITHUB_OUTPUT:-}" ] || echo "published=true" >> "$GITHUB_OUTPUT"
