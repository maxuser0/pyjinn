#!/bin/bash

set -e

dir=$(basename $(pwd))
if [[ $dir != "pyjinn" ]]; then
  echo "Error: $0 must be run from the 'pyjinn' project dir." >&2
  exit 1
fi

current_version=$(grep '^version = ' interpreter/build.gradle |sed 's/^version = '\''\(.*\)'\''/\1/')

if [[ -z $1 ]]; then
  echo $current_version
  exit 0
fi

function usage() {
    echo "Usage:" >&2
    echo "  $0  # print current version" >&2
    echo "  $0 --update|-u <new_version>  # update version" >&2
}

function update_version() {
  new_version=$1
  if [[ -z $new_version ]]; then
    usage
    exit 1
  fi
  echo "Update Pyjinn version from $current_version to $new_version? [y/N]" >&2
  read answer
  if [[ $answer == "y" ]]; then
    # Escape dots with backslash and wrap string in escaped angle brackets to match word boundaries.
    current_version_re="\\<${current_version//./\\.}\\>"
    git ls-files |xargs grep -l "$current_version_re" |grep 'README\.md\|build\.gradle$' |xargs sed -i '' "s/$current_version/$new_version/g"
    exit 0
  else
    echo "Cancelled." >&2
    exit 0
  fi
}

case $1 in
  --update|-u)
    shift
    update_version $1
    ;;
  *)
    usage
    exit 1
    ;;
esac
