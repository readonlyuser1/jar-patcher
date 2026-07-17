# Stage 1: fetch ASM and compile the bytecode-patching tools (see tools/*.java).
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /build
RUN wget -q -O asm.jar https://repo1.maven.org/maven2/org/ow2/asm/asm/9.10.1/asm-9.10.1.jar \
  && wget -q -O asm-tree.jar https://repo1.maven.org/maven2/org/ow2/asm/asm-tree/9.10.1/asm-tree-9.10.1.jar

COPY tools/LogLevelPatcher.java tools/ReturnValuePatcher.java .
RUN javac --release 17 -cp "asm.jar:asm-tree.jar" -d classes LogLevelPatcher.java ReturnValuePatcher.java

# Stage 2: the actual CI toolbox image.
FROM alpine:3.20

# General-purpose CI toolbox for pipelines that need to fetch, modify and
# re-publish a jar on a runner without a Docker build step of its own:
# - curl: download the jar, call REST APIs
# - openssh-client: deploy the jar to a remote host over SSH
# - jq: parse JSON API responses
# - unzip/zip: repackage the jar after modifying its contents
# - openjdk21-jre-headless: run LogLevelPatcher/ReturnValuePatcher (need ASM at runtime too)
# - bash, grep, coreutils: shell scripting
RUN apk add --no-cache \
    bash \
    curl \
    openssh-client \
    jq \
    grep \
    coreutils \
    unzip \
    zip \
    ca-certificates \
    openjdk21-jre-headless \
  && update-ca-certificates

COPY --from=build /build/asm.jar /build/asm-tree.jar /opt/tools/
COPY --from=build /build/classes /opt/tools/classes

WORKDIR /work
