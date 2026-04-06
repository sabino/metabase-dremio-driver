#!/usr/bin/env bash

set -euo pipefail

cd "$(dirname "$0")"
DRIVER_PATH="$(pwd)"
METABASE_DIR="${METABASE_DIR:-$(cd ../metabase && pwd)}"

if [[ ! -f "${METABASE_DIR}/deps.edn" ]]; then
  echo "Metabase source checkout not found at ${METABASE_DIR}"
  echo "Set METABASE_DIR or place this repo next to a metabase checkout."
  exit 1
fi

cd "${METABASE_DIR}"

clojure \
  -Sdeps "{:mvn/repos {\"dremio-free\" {:url \"https://maven.dremio.com/free/\"}} :aliases {:dremio {:extra-deps {com.metabase/dremio-driver {:local/root \"${DRIVER_PATH}\"}}}}}" \
  -X:build:dremio \
  build-drivers.build-driver/build-driver\! \
  "{:driver :dremio, :project-dir \"${DRIVER_PATH}\", :target-dir \"${DRIVER_PATH}/target\"}"

cd "${DRIVER_PATH}"
