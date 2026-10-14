# NeoGold Caddy image (doc 12 Section 7.1). The official image gives /usr/bin/caddy a file
# capability (cap_net_bind_service). With cap_drop ALL the kernel refuses to run such a binary,
# and NeoGold listens on unprivileged ports anyway, so the capability is removed here.
ARG CADDY_BASE
FROM ${CADDY_BASE}
RUN setcap -r /usr/bin/caddy
USER 1000:1000
