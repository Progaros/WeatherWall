# Android emulator + noVNC container for visual/screenshot testing of WeatherWall.
#
# Docker Desktop on Windows cannot expose /dev/kvm, so the emulator runs with
# software rendering (-no-accel -gpu swiftshader_indirect). Screenshots and
# functional behaviour are representative; frame rates are NOT.
FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive \
    ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_AVD_HOME=/root/.android/avd \
    DISPLAY=:0
ENV PATH="${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/emulator"

# Set http_proxy/https_proxy build args when building behind a corporate proxy.
ARG http_proxy
ARG https_proxy

RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl unzip wget openjdk-17-jdk-headless \
        xvfb x11vnc openbox novnc websockify \
        libgl1 libglu1-mesa libpulse0 libx11-6 libx11-xcb1 libxcb1 \
        libxcomposite1 libxcursor1 libxi6 libxrender1 libxtst6 libnss3 \
        libstdc++6 libdbus-1-3 libfontconfig1 libxkbcommon0 procps \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p "${ANDROID_HOME}/cmdline-tools/latest" \
    && curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
        -o /tmp/tools.zip \
    && unzip -q /tmp/tools.zip -d /tmp/tools \
    && mv /tmp/tools/cmdline-tools/* "${ANDROID_HOME}/cmdline-tools/latest/" \
    && rm -rf /tmp/tools /tmp/tools.zip

RUN mkdir -p /root/.android && touch /root/.android/repositories.cfg \
    && yes | sdkmanager --licenses > /dev/null 2>&1 || true

# sdkmanager/avdmanager are Java tools and ignore http_proxy env vars, so the
# proxy has to be handed to the JVM explicitly.
RUN set -eu; \
    if [ -n "${https_proxy:-}" ]; then \
        host=$(echo "$https_proxy" | sed -E 's#^[a-z]+://##; s#:.*$##'); \
        port=$(echo "$https_proxy" | sed -E 's#^.*:([0-9]+)/?$#\1#'); \
        export JAVA_TOOL_OPTIONS="-Dhttp.proxyHost=$host -Dhttp.proxyPort=$port -Dhttps.proxyHost=$host -Dhttps.proxyPort=$port"; \
    fi; \
    sdkmanager --install \
        "platform-tools" \
        "emulator" \
        "platforms;android-34" \
        "system-images;android-34;google_apis;x86_64"

RUN echo "no" | avdmanager create avd --force \
        --name wallpaper_api34 \
        --package "system-images;android-34;google_apis;x86_64" \
        --device "pixel_6_pro"

COPY start-emulator.sh /usr/local/bin/start-emulator.sh
RUN chmod +x /usr/local/bin/start-emulator.sh

WORKDIR /workspace
EXPOSE 6080 5554 5555
ENTRYPOINT ["/usr/local/bin/start-emulator.sh"]
