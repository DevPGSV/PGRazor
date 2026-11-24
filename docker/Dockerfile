
FROM node:22-bookworm-slim AS node

FROM ubuntu:24.04

ARG DEBIAN_FRONTEND=noninteractive

ARG ANDROID_CMDLINE_TOOLS=13114758
ARG ANDROID_CMDLINE_SHA256=7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee

ARG ANDROID_PLATFORM=android-36
ARG ANDROID_BUILD_TOOLS=36.0.0
ARG ANDROID_NDK_GAMEPLAY=27.3.13750724
ARG ANDROID_NDK_ART=29.0.14206865

ARG CMAKE_VERSION=4.4.4
ARG NINJA_VERSION=1.13.2

# Base system dependencies
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        bash \
        ca-certificates \
        curl \
        git \
        unzip \
        zip \
        file \
        jq \
        build-essential \
        python3 \
        python3-venv \
        python3-pip \
        openjdk-17-jdk-headless \
        libatomic1 \
        libstdc++6 \
        zlib1g \
        libncurses6 \
    && rm -rf /var/lib/apt/lists/*

# Node.js
COPY --from=node /usr/local/bin/node /usr/local/bin/node

# Python build tools
RUN python3 -m venv /opt/venv \
    && /opt/venv/bin/pip install --no-cache-dir \
        "cmake==${CMAKE_VERSION}" \
        "ninja==${NINJA_VERSION}"

# Android SDK environment
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk

ENV PGRAZOR_ANDROID_PLATFORM=${ANDROID_PLATFORM}
ENV PGRAZOR_BUILD_TOOLS=${ANDROID_BUILD_TOOLS}

ENV ANDROID_NDK_HOME=/opt/android-sdk/ndk/${ANDROID_NDK_GAMEPLAY}
ENV ANDROID_NDK_ROOT=${ANDROID_NDK_HOME}
ENV PGRAZOR_ART_NDK=/opt/android-sdk/ndk/${ANDROID_NDK_ART}

ENV PGRAZOR_NATIVE_DEPS=/cache/native-deps

ENV PATH="/opt/venv/bin:${JAVA_HOME}/bin:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/build-tools/${ANDROID_BUILD_TOOLS}:${PATH}"

# Android command-line tools
RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" \
    && curl -fSL \
        "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS}_latest.zip" \
        -o /tmp/android-tools.zip \
    && echo "${ANDROID_CMDLINE_SHA256}  /tmp/android-tools.zip" \
        | sha256sum -c - \
    && unzip -q /tmp/android-tools.zip -d /tmp/android-tools \
    && mv /tmp/android-tools/cmdline-tools \
        "${ANDROID_HOME}/cmdline-tools/latest" \
    && rm -rf /tmp/android-tools /tmp/android-tools.zip

# Android SDK licenses
RUN yes | sdkmanager --sdk_root="${ANDROID_HOME}" --licenses

# Install pinned Android SDK and NDK packages
RUN sdkmanager --sdk_root="${ANDROID_HOME}" \
        "platform-tools" \
        "platforms;${ANDROID_PLATFORM}" \
        "build-tools;${ANDROID_BUILD_TOOLS}" \
        "sources;${ANDROID_PLATFORM}" \
        "ndk;${ANDROID_NDK_GAMEPLAY}" \
        "ndk;${ANDROID_NDK_ART}"

# Runtime environment
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8
ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1
ENV HOME=/tmp/pgrazor-home

RUN mkdir -p /workspace /cache /tmp/pgrazor-home \
    && chmod 1777 /tmp/pgrazor-home

WORKDIR /workspace

CMD ["/bin/bash"]
