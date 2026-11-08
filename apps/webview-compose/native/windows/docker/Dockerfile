# The image winvm.sh's Gradle runs in on `desktop`: supermux-build (desktop:~/work/Dockerfile.supermux-build, JDK 17 and
# Temurin 21) plus what Compose's desktop tests need in a container. skiko's linux-x64 library links libGL.so.1 and
# libX11.so.6, which supermux-build lacks: without them every runComposeUiTest fails to load skiko. Fonts for skiko's
# text; Xvfb for a test that opens an AWT window. winvm.sh builds it as wvc-build:1 when desktop doesn't have it (the
# Kotlin half of the engine built the same file as wvc-kotlin-build:1).
FROM supermux-build:latest
RUN apt-get update && apt-get install -y --no-install-recommends libgl1 libx11-6 fontconfig fonts-dejavu-core xvfb xauth \
    && rm -rf /var/lib/apt/lists/*
