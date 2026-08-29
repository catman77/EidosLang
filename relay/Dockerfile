FROM debian:bookworm-slim
# Distro packages rather than pip: same trust level as the rest of the host's software.
RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 python3-libtorrent python3-cryptography \
 && rm -rf /var/lib/apt/lists/*
COPY relay.py /opt/relay.py
ENV STATE_DIR=/var/lib/eidolang-relay
VOLUME ["/var/lib/eidolang-relay"]
EXPOSE 6881/tcp 6881/udp 6882/tcp
CMD ["python3", "-u", "/opt/relay.py"]
