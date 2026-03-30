# AGENTS.md

This file provides guidance to AI coding tools when working with code in this repository.

## Project Overview

Clojure CLI tool that orchestrates building and publishing co-dependent, multiarchitecture Docker container images in a monorepo. It handles build ordering via numeric directory prefixes, skips already-built images, supports ARM64/AMD64 via BuildKit+QEMU, and cascades semantic version bumps through derivative images.

Hosted on GitLab; GitHub is a mirror. Contributions (PRs, issues) go to GitLab.

## Build Commands

```sh
# Run from source
clj -M -m louis-jackman.container-image-pipelines <subcommand> [options]

# Build uberjar (outputs target/container-image-pipelines.jar)
clojure -T:build uberjar

# Clean build artifacts
clojure -T:build clean

# Native executable (requires GraalVM with native-image)
native-image \
    -jar target/container-image-pipelines.jar \
    --enable-url-protocols=http,https \
    --no-fallback \
    --initialize-at-build-time \
    target/container-image-pipelines

# Full Docker-based build (produces JAR + native Linux binary)
docker buildx build -t container-image-pipelines-builder --load .
docker run --rm -v "$PWD:/home/user/workspace" container-image-pipelines-builder
```

There are no tests or linting configured in this project.

## Architecture

All source lives in `src/louis_jackman/container_image_pipelines/`. Key modules:

- **container_image_pipelines.clj** -- Entry point. CLI option parsing (`tools.cli`), subcommand dispatch, structured error handling.
- **subcommands.clj** -- Implements the 8 user-facing subcommands: `build`, `publish`, `publish-locally`, `pull-latest-from-{local,remote}-registry`, `upload-all-to-local-registry`, `cascade-version-updates`, `help`.
- **contexts.clj** -- Discovers context directories under `contexts/`, validates each has `Dockerfile` + `info.edn`, sorts by numeric prefix for build order, extracts image name by stripping prefix.
- **image_building.clj** -- Drives `docker buildx build`. Detects provenance support (memoised). Conditionally skips already-built images.
- **image_existence.clj** -- Dual-path checks: HTTP API against registry `/v2/tags/list`, with `docker manifest inspect` as fallback.
- **build_specs.clj** -- `BuildSpec` record combining context metadata with build flags. Generates `docker buildx` CLI arguments.
- **version_update_cascading.clj** -- Regex-based surgical Dockerfile rewriting of `FROM` lines. Bumps semver patch versions and cascades through the dependency chain. Backs up originals with `.bk` extension.
- **context_metadata.clj** -- Reads/writes `info.edn` (EDN format) for version and platform metadata.
- **images.clj** -- `ImageRef` record (`{:registry, :image-name, :tag}`). Converts to Docker-compatible strings.
- **semver.clj** -- Semantic versioning parsing and patch-increment logic.
- **utils.clj** -- Process management wrappers for `docker`/`git` commands, HTTP utilities, dynamic `*http-client*` binding for thread safety.

### Key Records

- **ImageRef** -- `{:registry :image-name :tag}`, the canonical reference to a Docker image.
- **BuildSpec** -- Full build instructions: Dockerfile path, context dir, image ref, platforms, provenance, push flag.
- **ImageExistenceChecker** -- Configuration for registry existence checks (secure vs insecure).

### Design Patterns

- All namespaces enable `(set! *warn-on-reflection* true)`.
- HTTP client lifecycle managed via dynamic binding (`*http-client*`).
- Two error modes: stop-on-first (default) or accumulate-all.
- External process execution always checks exit status; non-zero throws.

## User Project Layout

The tool operates on a "project directory" containing:

```
contexts/
  00-debian-slim/    # Dockerfile + info.edn
  01-base-dev/       # Dockerfile + info.edn
  02-java-dev/       # Dockerfile + info.edn
```

Numeric prefix controls build order. Image name is derived by stripping the prefix (e.g. `01-base-dev` becomes `base-dev`). `info.edn` contains `{:version "1.2.3"}` and optionally `{:platforms ["linux/arm64" "linux/amd64"]}`.

## CI/CD

GitLab CI (`.gitlab-ci.yml`): single `build` stage using `docker:28.2.2` with DinD. Produces two artifacts: the JAR and a native Linux x86_64 executable.

## Dependencies

- **Runtime**: Clojure, Docker with BuildKit, QEMU (for multiarch)
- **Build-time**: JDK 25, GraalVM CE 24.0.1 (for native compilation)
- **Clojure deps** (`deps.edn`): `data.json`, `tools.cli`, `graal-build-time`; build alias uses `tools.build`
