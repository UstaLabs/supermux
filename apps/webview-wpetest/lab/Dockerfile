# The WPE lab for apps/webview-wpetest (docs/superpowers/plans/2026-10-30-webview-linux-wpe.md): prebuilt WPE WebKit
# 2.54.2 with the WPEPlatform API, its headless platform and SHM buffers, from Debian sid frozen at a snapshot; a JDK
# for Compose Desktop; Xvfb, openbox and Mesa (llvmpipe) for skiko; gcc and the EGL/GLES headers for the JNI glue and
# the upload benchmark. Nothing of WebKit is compiled here.
FROM debian:sid-20261027-slim

ARG SNAPSHOT=20261029T000000Z
ENV DEBIAN_FRONTEND=noninteractive LANG=C.UTF-8

RUN rm -f /etc/apt/sources.list /etc/apt/sources.list.d/*.sources \
 && printf 'Types: deb\nURIs: http://snapshot.debian.org/archive/debian/%s/\nSuites: sid\nComponents: main\nSigned-By: /usr/share/keyrings/debian-archive-keyring.gpg\nCheck-Valid-Until: no\n' "$SNAPSHOT" \
      > /etc/apt/sources.list.d/snapshot.sources \
 && apt-get -o Acquire::Retries=5 update \
 && apt-get -o Acquire::Retries=5 install -y --no-install-recommends \
      libwpewebkit-2.0-1=2.54.2-1 libwpewebkit-2.0-dev=2.54.2-1 \
      gcc libc6-dev pkg-config \
      openjdk-21-jdk \
      xvfb xauth x11-utils openbox xdotool \
      libgl1 libglx-mesa0 libgl1-mesa-dri libegl1 libegl-mesa0 libgles2 libegl-dev libgles-dev \
      fontconfig fonts-dejavu-core fonts-ipafont-gothic \
      procps zstd xz-utils binutils file ca-certificates \
 && rm -rf /var/lib/apt/lists/*
