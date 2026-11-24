FROM ubuntu:24.04@sha256:9a04f13b3f80ef056361e30868593b1cfd2279fe19e4081cb93a90bf56819d6a
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl python3 unzip openjdk-17-jdk-headless libicu74 libssl3t64 \
 && rm -rf /var/lib/apt/lists/*
RUN set -eux; \
    mkdir -p /opt/research/jadx; \
    curl --fail --location --retry 2 --header 'Accept: application/octet-stream' \
      https://api.github.com/repos/SamboyCoding/Cpp2IL/releases/assets/360212254 -o /opt/research/Cpp2IL; \
    echo '526998e593c52c029c5a6215c5c6c9f9d963706bfc409fc9ff80a95c4c500349  /opt/research/Cpp2IL' | sha256sum -c -; \
    chmod 0755 /opt/research/Cpp2IL; \
    curl --fail --location --retry 2 --header 'Accept: application/octet-stream' \
      https://api.github.com/repos/skylot/jadx/releases/assets/764892001 -o /tmp/jadx.zip; \
    echo '1755e4242ade71a077438723689f404079592ec7764a3a0572f98fef94ebcbf3  /tmp/jadx.zip' | sha256sum -c -; \
    unzip -q /tmp/jadx.zip -d /opt/research/jadx; \
    rm /tmp/jadx.zip
ENV HOME=/tmp DOTNET_CLI_TELEMETRY_OPTOUT=1
WORKDIR /tmp
