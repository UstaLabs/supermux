# Where the WPE runtime must work on its own: Ubuntu 24.04 with no WPE, what a desktop has (GLib, Mesa, GStreamer,
# fontconfig, bubblewrap), a JRE, Xvfb, openbox and xdotool. The runtime is mounted at /opt/wpe-runtime; the sample's
# jars at /app/lib.
FROM ubuntu:24.04
ENV DEBIAN_FRONTEND=noninteractive LANG=C.UTF-8
RUN apt-get update && apt-get install -y --no-install-recommends \
      openjdk-21-jre xvfb xauth x11-utils openbox xdotool procps \
      libgl1 libglx-mesa0 libgl1-mesa-dri libegl1 libegl-mesa0 libgles2 libgbm1 \
      libglib2.0-0t64 glib-networking libgstreamer1.0-0 libgstreamer-plugins-base1.0-0 gstreamer1.0-plugins-base \
      gstreamer1.0-plugins-good gstreamer1.0-gl libseccomp2 bubblewrap xdg-dbus-proxy \
      fontconfig fonts-dejavu-core fonts-ipafont-gothic \
 && rm -rf /var/lib/apt/lists/*
