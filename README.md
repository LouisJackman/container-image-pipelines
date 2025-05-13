# container-image-pipelines

**Build & publish a monorepo of co-dependent, multiarchitecture container
images.**

Avoid rebuilding already-built images, automatically rebuild dependencies of
descendent images if necessary, and handle multiarchitecture building
woes. Let users easily shift between remote and local
registries. Automatically cascade version increments to all descendent images
when patching a root base image.

## Usage

Assume you have a "project directory" that contains a `contexts`
subdirectory. That subdirectory, in turn, has a list of further
subdirectories, each representing a [Docker build
context](https://docs.docker.com/build/concepts/context/). Each contains a
`Dockerfile` and files that are used throughout the building of that
`Dockerfile`. They also contain an EDN file called `info.edn`:

```
{:version "1.2.3"}
```

Each context subdirectory additionally has a numeric prefix that determines
the ordering of its build; `00-debian-slim` is guaranteed to be built before
its dependent image specified in `01-base-dev`.

It ends up looking like this:

```
.
├── contexts
│   ├── 00-debian-slim
│   │   ├── Dockerfile
│   │   └── info.edn
│   ├── 01-jq
│   │   ├── Dockerfile
│   │   └── info.edn
│   ├── 01-base-dev
│   │   ├── Dockerfile
│   │   ├── info.edn
│   │   ├── README.md
│   ├── 01-pass-env
│   │   ├── Dockerfile
│   │   ├── info.edn
│   │   └── README.md
│   ├── 02-java-dev
│   │   ├── additional-configuration.el
│   │   ├── Dockerfile
│   │   └── info.edn
│   ├── 03-clojure-dev
│   │   ├── additional-configuration.el
│   │   ├── Dockerfile
│   │   ├── Dockerfile.bk
│   │   └── info.edn
│   └── README.md
└── README.md
```

That ordering, combined with versioning metadata in `info.edn`, gives enough
information to fully manage multiarchitecture builds of codependent images
with versioning and automatic version-bumping on base image updates.

**This tool does all of that.**

Until native executables are produced and published, the tool must be invoked
like this after [installing
Clojure](https://clojure.org/guides/install_clojure).

```
$ clj -M -m louis-jackman.container-image-pipelines

Build & publish a monorepo of co-dependent, multiarchitecture container images.

Avoid rebuilding already-built images, automatically rebuild dependencies of
descendent images if necessary, and handle multiarchitecture building
woes. Let users easily shift between remote and local
registries. Automatically cascade version increments to all descendent
images when patching a root base image.

Usage:
        container-image-pipelines help
        container-image-pipelines build
        container-image-pipelines publish-locally
        container-image-pipelines publish
        container-image-pipelines upload-all-to-local-registry
        container-image-pipelines pull-latest-from-local-registry
        container-image-pipelines pull-latest-from-remote-registry
        container-image-pipelines cascade-version-updates

        Pass `-h` or `--help` to one of those subcommands to discover
        their options.


An error occured: invalid subcommand.
More context: valid subcommands: build, cascade-version-updates, help, publish, publish-locally, pull-latest-from-local-registry, pull-latest-from-remote-registry, upload-all-to-local-registry
```

To see the options available for a specific subcommand, pass `-h` or `--help` to it:

```
$ clj -M -m louis-jackman.container-image-pipelines pull-latest-from-remote-registry -h

container-image-pipelines pull-latest-from-remote-registry
        --project-dir [.]  — The project directory containing the `contexts` directory.
        --remote-registry — The remote registry.
```

Several operations are available. They include pulling all prebuilt
multiarchitecture images from a remote registry to your local registry,
building them all from scratch locally with additional customisations,
automatically cascading version bumps throughout derived images when a base
image is updated, and more.

## Official Mirror of the GitLab Repository

This repository is currently hosted [on
GitLab.com](https://gitlab.com/louis.jackman/container-image-pipelines). An
official mirror exists on
[GitHub](https://github.com/LouisJackman/container-image-pipelines). GitLab is
still the official hub for contributions such as PRs and issues.

## Examples

See [my dockerfiles repository](https://gitlab.com/louis.jackman/dockerfiles)
for an example project directory, against which this tool can be run.

## Details

When building images, version tags are used but `latest` is also set for
convenience.

The last path component of the image name is based off the directory structure
of the provided project directory; to illustrate, the built image of the
Dockerfile found within the `contexts/04-go-dev` subdirectory can be found at
`$REGISTRY/go-dev`. The `04-` within that path is a numeric prefix to control
build ordering, which is explained later in this document.

To build all images locally for just the current architecture and load the
result into the local Docker store, run the `build` subcommand. Run the
`publish` subcommand to emulate the same building and pushing steps taken by
a CD pipeline, building for all supported platforms and publishing to a
remote registry.

Alternatively, the images can be built locally for just the current
architecture and pushed to a local registry. Run the `publish-locally`
subcommand to do so against a local registry at `localhost:5000`.

## Image-Building Technicalities

### BuildKit and `src/louis_jackman/container_image_pipelines/image_building.clj`

The project directory's images can be built in a specific order,
e.g. `debian-slim` before its dependent
images. `src/louis_jackman/container_image_pipelines/image_building.clj`
manages such dependencies while building, and that's encapsulated behind
the `build` and `publish*` subcommands. This is implemented using numeric
ordering prefixes on each context subdirectory within a project directory,
e.g.  `debian-slim` being prefixed with `00-` to represent it being in the
first batch of images to build, and its dependent image `base-dev` coming long
after it due to having a `03-` prefix.

The `publish-locally` subcommand also solves the problem of [BuildKit having
poor support for dependencies on images stored in the local Docker images
store](https://github.com/moby/buildkit/issues/2343) by relying on a local
registry rather than the local store.

### Multiarchiture Support, at Least for ARM64 and AMD64

The default configuration will attempt builds on both AMD64 and ARM64, but
requires a modern BuildKit-supporting Docker installation and QEMU binaries
for both architectures. If that isn't available, use the
`--only-local-platform` argument, or override the default platforms with
`--default-platforms`.

### Setting up BuildKit and Using a Local Registry

To deploy to a local registry, first spin up a local Docker registry:

```sh
docker run -d -e REGISTRY_STORAGE_DELETE_ENABLED=true --restart=always --name registry -p 5000:5000 registry:2.8.3
```

That sets up a registry that auto-restarts when it ends, and allows deletions
when necessary. It exposed the expected 5000 port. For manipulating the
registry locally, such as listing and deleting images, consider [the `reg`
tool](https://github.com/genuinetools/reg).

**That command spins up an unauthenticated registry without TLS, with the
ability to erase and replace images for anyone who can reach the port. That is
only secure if the port is firewalled for just localhost or the local network
has other mitigating controls.**

Then set up a builder instance:

```sh
docker buildx create --platform=linux/arm64,linux/amd64 --driver-opt=network=host --use
```

Doing it with these additional flags ensures it can cross-compile to both ARM64
and AMD64, while putting the builder on the host network. Without that
networking change, it can't see the registry service that was just spun up. A
more elegant solution would be to [give them both the same dedicated Docker
network](https://docs.docker.com/network/network-tutorial-standalone/#use-user-defined-bridge-networks).

Ensure the QEMU binaries for all desired platforms exist by invoking this
one-shot container. This may not work on all architectures, meaning some will
only be capable of doing "local" image builds rather than building for multiple
architectures.

```sh
docker run --rm --privileged multiarch/qemu-user-static --reset -p yes
```

Finally, build the images and push them to the local registry:

```sh
clj -M -m louis-jackman.container-image-pipelines publish-locally
```

See the `publish-locally` subcommand's `--help` for more details.

### Future Improvements

Potential future improvements are enumerated in [the TODO document](doc/TODO.md).
