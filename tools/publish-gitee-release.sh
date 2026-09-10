#!/usr/bin/env bash
set -euo pipefail

: "${GITEE_ACCESS_TOKEN:?GITEE_ACCESS_TOKEN is required}"
: "${GITEE_OWNER:?GITEE_OWNER is required}"
: "${GITEE_REPOSITORY:?GITEE_REPOSITORY is required}"
: "${VERSION_NAME:?VERSION_NAME is required}"

tag="v${VERSION_NAME}"
asset="matchshell-${tag}-arm64.apk"
github_url="https://github.com/littletaro97-arch/matchshell/releases/download/${tag}/${asset}"
api="https://gitee.com/api/v5/repos/${GITEE_OWNER}/${GIT_REPOSITORY}"

curl --fail --location --retry 3 --retry-all-errors --connect-timeout 15 --max-time 300 \
  --output "$asset" "$github_url"
test "$(stat -c%s "$asset")" -gt 1000000

release_json="$(curl --fail --silent --show-error --retry 3 --retry-all-errors \
  --connect-timeout 15 --max-time 60 \
  --header "Authorization: Bearer $GITEE_ACCESS_TOKEN" \
  "$api/releases/tags/$tag" 2>/dev/null || true)"
if [[ -z "$release_json" ]]; then
  release_json="$(curl --fail-with-body --silent --show-error --retry 3 --retry-all-errors \
    --connect-timeout 15 --max-time 60 --request POST \
    --header "Authorization: Bearer $GITEE_ACCESS_TOKEN" \
    --data-urlencode "tag_name=$tag" --data-urlencode "name=$tag" \
    --data-urlencode "body=MatchShell ${VERSION_NAME} stable release" \
    --data-urlencode "target_commitish=$tag" "$api/releases")"
fi
release_id="$(jq -er '.id' <<<"$release_json")"

existing="$(curl --fail --silent --show-error --connect-timeout 15 --max-time 60 \
  --header "Authorization: Bearer $GITEE_ACCESS_TOKEN" \
  "$api/releases/$release_id/attach_files")"
if jq -e --arg name "$asset" '.[] | select(.name == $name)' <<<"$existing" >/dev/null; then
  echo "$asset already exists; nothing to upload"
  exit 0
fi

curl --fail-with-body --silent --show-error --retry 3 --retry-all-errors \
  --connect-timeout 15 --max-time 600 --request POST \
  --header "Authorization: Bearer $GITEE_ACCESS_TOKEN" \
  --form "file=@$asset" "$api/releases/$release_id/attach_files" | jq -e .browser_download_url
