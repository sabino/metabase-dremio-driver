# Metabase Dremio Driver

Dremio driver for Metabase, packaged as a plugin JAR with the current official
Dremio JDBC driver bundled inside it.

This fork modernizes the original `Baoqi/metabase-dremio-driver` line for:

- Metabase `v0.60.0-beta`
- Java 21 runtimes
- Dremio JDBC `26.0.5-202509091642240013-f5051a07`
- GitHub Actions build + release automation

## Why this fork exists

The upstream Dremio plugin was pinned to an older Dremio JDBC line. That older
JDBC worked for previous Metabase versions, but it is no longer a good fit for
current Dremio deployments and Metabase 60 beta.

This fork keeps the existing Dremio-specific Metabase driver behavior while
updating the bundled JDBC payload and release process.

## Compatibility target

- Dremio: `26.x`
- Metabase: `v0.60.0-beta`
- Java: `21`

## Runtime note for Java 21

When Metabase runs on Java 21, the current Dremio JDBC line requires this JVM
flag:

```bash
JAVA_TOOL_OPTIONS=--add-opens=java.base/java.nio=ALL-UNNAMED
```

Without it, the Dremio JDBC driver can fail during direct-buffer setup.

## Build

This fork keeps the original build model from the upstream repository:

- keep this repository next to a Metabase source checkout
- run `build.sh`
- let Metabase build the plugin using its own driver build task

The only intended changes are:

- newer Dremio JDBC
- Metabase `v0.60.0-beta` compatibility
- Java 21 compatibility
- GitHub Actions automation

The generated files are:

- `target/dremio.metabase-driver.jar`
- `dist/dremio.metabase-driver.jar.sha256`

### Local build

```bash
./build.sh
```

### Override versions

```bash
export METABASE_DIR=/path/to/metabase
./build.sh
```

## Install in Metabase

1. Copy `target/dremio.metabase-driver.jar` into Metabase’s `/plugins`
   directory.
2. Set:

```bash
MB_PLUGINS_DIR=/plugins
JAVA_TOOL_OPTIONS=--add-opens=java.base/java.nio=ALL-UNNAMED
```

3. Restart Metabase.

The driver will appear in Metabase as `Dremio`.

## Connection settings

Typical internal connection settings:

- Host: `srv-captain--dremio`
- Port: `31010`
- Username: your Dremio service user
- Password: your Dremio service password
- Schemas: use Metabase schema filters if you want to restrict visibility

## GitHub Actions

The workflow in `.github/workflows/build-and-release.yml`:

- installs Java 21 and the Clojure CLI
- checks out Metabase `v0.60.0-beta` next to this repository
- runs the same `build.sh` flow used locally
- validates that the jar contains the compiled plugin init class and JDBC driver
- uploads the jar and checksum as artifacts
- publishes a GitHub release on `v*` tags

## Upstream

Original project:

- `https://github.com/Baoqi/metabase-dremio-driver`

## License

Apache 2.0. See `LICENSE`.
