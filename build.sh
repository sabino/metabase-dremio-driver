#!/usr/bin/env bash

set -euo pipefail

cd "$(dirname "$0")"
DRIVER_PATH="$(pwd)"
METABASE_DIR="${METABASE_DIR:-$(cd ../metabase && pwd)}"
VERSIONS_FILE="${DRIVER_PATH}/.github/driver-versions.env"
DRIVER_DEPS_FILE="${DRIVER_PATH}/deps.edn"

if [[ -f "${VERSIONS_FILE}" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "${VERSIONS_FILE}"
  set +a
fi

DREMIO_JDBC_VERSION="${DREMIO_JDBC_VERSION:-26.0.5-202509091642240013-f5051a07}"

ORIGINAL_DRIVER_DEPS="$(cat "${DRIVER_DEPS_FILE}")"
restore_driver_deps() {
  printf '%s' "${ORIGINAL_DRIVER_DEPS}" > "${DRIVER_DEPS_FILE}"
}
trap restore_driver_deps EXIT

python3 - "${DRIVER_DEPS_FILE}" "${DREMIO_JDBC_VERSION}" <<'PY'
import re
import sys
from pathlib import Path

deps_path = Path(sys.argv[1])
version = sys.argv[2]
text = deps_path.read_text()
updated = re.sub(
    r'(\{com\.dremio\.distribution/dremio-jdbc-driver \{:mvn/version ")([^"]+)("\}\})',
    rf'\g<1>{version}\g<3>',
    text,
    count=1,
)
deps_path.write_text(updated)
PY

if [[ ! -f "${METABASE_DIR}/deps.edn" ]]; then
  echo "Metabase source checkout not found at ${METABASE_DIR}"
  echo "Set METABASE_DIR or place this repo next to a metabase checkout."
  exit 1
fi

cd "${METABASE_DIR}"

clojure \
  -Sdeps "{:mvn/repos {\"dremio-free\" {:url \"https://maven.dremio.com/free/\"}} :aliases {:dremio {:extra-deps {com.dremio.distribution/dremio-jdbc-driver {:mvn/version \"${DREMIO_JDBC_VERSION}\"} com.metabase/dremio-driver {:local/root \"${DRIVER_PATH}\"}}}}}" \
  -X:build:dremio \
  build-drivers.build-driver/build-driver\! \
  "{:driver :dremio, :project-dir \"${DRIVER_PATH}\", :target-dir \"${DRIVER_PATH}/target\"}"

cd "${DRIVER_PATH}"
