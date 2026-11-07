# Builds webview-compose's WPE runtime: WPE WebKit 2.54.2 on Ubuntu 24.04, the oldest base WebKit 2.54 supports
# (docs.webkit.org DependenciesPolicy), so the runtime runs on 24.04 and newer. libdav1d-dev and libyuv-dev are for the
# runtime's own libavif, which decodes with dav1d only (build-runtime.sh, avif).
FROM ubuntu:24.04
ENV DEBIAN_FRONTEND=noninteractive LANG=C.UTF-8
RUN apt-get update && apt-get install -y --no-install-recommends \
      build-essential cmake ninja-build ccache pkg-config perl python3 ruby gperf unifdef patch curl ca-certificates \
      xz-utils zstd patchelf file binutils openjdk-17-jdk-headless \
      libglib2.0-dev libsoup-3.0-dev libicu-dev libxml2-dev libxslt1-dev libsqlite3-dev \
      libfontconfig-dev libfreetype-dev libharfbuzz-dev libjpeg-dev libpng-dev libwebp-dev libavif-dev libwoff-dev \
      libdav1d-dev libyuv-dev \
      liblcms2-dev libopenjp2-7-dev libepoxy-dev libdrm-dev libgbm-dev libegl-dev libgles-dev libxkbcommon-dev \
      libgcrypt20-dev libtasn1-6-dev libsystemd-dev libseccomp-dev libsysprof-capture-4-dev \
      libgstreamer1.0-dev libgstreamer-plugins-base1.0-dev libgstreamer-plugins-bad1.0-dev \
      libmanette-0.2-dev libgudev-1.0-dev libevdev-dev bubblewrap xdg-dbus-proxy \
 && rm -rf /var/lib/apt/lists/*
