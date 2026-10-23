#!/bin/sh
# Run from the repository root with the isolated kind kubeconfig selected.
set -eu
umask 077
. ./.env
: "${DB_USERNAME:?Required in .env}"
: "${DB_PASSWORD:?Required in .env}"
: "${MYSQL_ROOT_PASSWORD:?Required in .env}"
secret_dir=$(mktemp -d)
trap 'rm -rf "$secret_dir"' EXIT HUP INT TERM
printf '%s' "$DB_USERNAME" > "$secret_dir/DB_USERNAME"
printf '%s' "$DB_PASSWORD" > "$secret_dir/DB_PASSWORD"
printf '%s' "$MYSQL_ROOT_PASSWORD" > "$secret_dir/MYSQL_ROOT_PASSWORD"
# No secret values in command arguments, terminal output, or committed manifests.
kubectl --context kind-country-service -n country-service create secret generic country-db \
  --from-file="$secret_dir/DB_USERNAME" \
  --from-file="$secret_dir/DB_PASSWORD" \
  --from-file="$secret_dir/MYSQL_ROOT_PASSWORD"
