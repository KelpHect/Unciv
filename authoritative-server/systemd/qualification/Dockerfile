FROM ubuntu@sha256:4fbb8e6a8395de5a7550b33509421a2bafbc0aab6c06ba2cef9ebffbc7092d90

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
    && apt-get install --yes --no-install-recommends \
        iproute2 \
        jq \
        openjdk-21-jre-headless \
        procps \
        python3 \
        systemd \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --system unciv-authoritative \
    && useradd --system --gid unciv-authoritative \
        --home-dir /nonexistent --shell /usr/sbin/nologin unciv-worker \
    && install -d -o root -g root -m 0755 \
        /opt/unciv-authoritative/docs \
        /opt/unciv-authoritative/releases/qualification/worker \
        /opt/unciv-authoritative/rulesets/versions/qualification \
    && install -d -o root -g unciv-authoritative -m 0750 \
        /etc/unciv-authoritative

COPY --chmod=0444 UncivAuthoritativeWorker.jar \
    /opt/unciv-authoritative/releases/qualification/worker/UncivAuthoritativeWorker.jar
COPY --chmod=0444 authoritative-worker-systemd.md \
    /opt/unciv-authoritative/docs/authoritative-worker-systemd.md
COPY assets/ /opt/unciv-authoritative/rulesets/versions/qualification/
COPY --chmod=0644 unciv-authoritative-worker.service \
    /etc/systemd/system/unciv-authoritative-worker.service
COPY --chmod=0755 qualify-worker.sh /usr/local/libexec/unciv/qualify-worker.sh
COPY --chmod=0755 worker-probe.py /usr/local/libexec/unciv/worker-probe.py

RUN find /opt/unciv-authoritative/rulesets/versions/qualification \
        -type d -exec chmod 0555 {} + \
    && find /opt/unciv-authoritative/rulesets/versions/qualification \
        -type f -exec chmod 0444 {} + \
    && ln -s versions/qualification /opt/unciv-authoritative/rulesets/active \
    && ln -s qualification /opt/unciv-authoritative/releases/current \
    && printf '%s\n' \
        'UNCIV_ENGINE_WORKER_PORT=43170' \
        'UNCIV_ENGINE_WORKER_SECRET=5555555555555555555555555555555555555555555555555555555555555555' \
        'UNCIV_ENGINE_WORKER_SOCKET_TIMEOUT_MS=5000' \
        'UNCIV_ENGINE_WORKER_COMMAND_TIMEOUT_MS=30000' \
        'UNCIV_V3_RELEASE_BUNDLE_ID=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
        > /etc/unciv-authoritative/worker.env \
    && chown root:unciv-authoritative /etc/unciv-authoritative/worker.env \
    && chmod 0640 /etc/unciv-authoritative/worker.env \
    && systemctl enable unciv-authoritative-worker.service

STOPSIGNAL SIGRTMIN+3
CMD ["/usr/lib/systemd/systemd"]
