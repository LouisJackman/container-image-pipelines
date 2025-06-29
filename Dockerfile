# syntax=docker/dockerfile:1.4.1

FROM debian:trixie-20250407

SHELL ["/bin/bash", "-o", "errexit", "-o", "pipefail", "-o", "nounset", "-c"]

ENV LANG=C.UTF-8

# Modify this in case the default UID/GIDs cause permission problems when
# working inside bind-mounted volumes.
ARG USER_UID_GID=1000

ARG JDK_VERSION=25 \
    GRAALVM_VERSION=24.0.1


#
# Install a JVM and other required dpkgs, then drop root.
#


RUN <<-EOF
    export DEBIAN_FRONTEND=noninteractive
    apt-get update
    apt-get install --yes --no-install-recommends \
        build-essential \
        ca-certificates \
        curl \
        git \
        zlib1g \
        zlib1g-dev \
        "openjdk-$JDK_VERSION"-jre-headless

    rm -fr /var/lib/apt/lists/*

    update-ca-certificates
EOF

RUN groupadd -g "$USER_UID_GID" user \
    && useradd --create-home --uid "$USER_UID_GID" --gid "$USER_UID_GID" user
USER user

RUN umask 077

RUN mkdir -p ~/.local/bin
ENV PATH=/home/user/.local/bin:$PATH

VOLUME /home/user/workspace
WORKDIR /home/user/workspace


#
# Install Clojure from the instructions here:
# https://clojure.org/guides/install_clojure
#
# While a `clojure` package exists in Debian's repositories, it is incomplete,
# e.g. missing the `clj` command.
#

RUN <<-EOF
    curl -LOSfs https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh
    chmod +x linux-install.sh
    mkdir -p ~/.local/clojure
    ./linux-install.sh --prefix "$HOME/.local/clojure"
    rm linux-install.sh
EOF

ENV PATH="$PATH:/home/user/.local/clojure/bin"


#
# Install GraalVM
#

RUN <<-EOF
    curl -LSfsO "https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-${GRAALVM_VERSION}/graalvm-community-jdk-${GRAALVM_VERSION}_linux-x64_bin.tar.gz"
    mv "graalvm-community-jdk-${GRAALVM_VERSION}_linux-x64_bin.tar.gz" ~/.local/
    cd ~/.local/
    tar -xzf "graalvm-community-jdk-${GRAALVM_VERSION}_linux-x64_bin.tar.gz"
    rm "graalvm-community-jdk-${GRAALVM_VERSION}_linux-x64_bin.tar.gz"
EOF

ENV PATH="$PATH:/home/user/.local/graalvm-community-openjdk-${GRAALVM_VERSION}+9.1/bin"


#
# Now Clojure and GraalVM are installed, build the project's uberjar and then
# compile it to native code.
#
# `--initialize-at-build-time` still seems necessary even with projects
# installing `com.github.clj-easy/graal-build-time`.
#

CMD clojure -T:build uberjar \
    && native-image \
        -jar target/container-image-pipelines.jar \
        --enable-url-protocols=https \
        --no-fallback \
        --initialize-at-build-time \
        target/container-image-pipelines

