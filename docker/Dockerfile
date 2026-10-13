# apimgr-policy-debugger container image (Linux x86_64 hosts and CI).
# The image contains YOUR Axway runtime: build it locally and keep it in a private registry.
#
#   docker/build.sh <Axway apigateway dir | .tar.gz>      -> image apimgr-policy-debugger:<version>
#   docker run --rm -v "$PWD:/work" apimgr-policy-debugger test tests/ --config config.fed --junit junit.xml
FROM ubuntu:20.04
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ca-certificates locales \
 && locale-gen en_US.UTF-8 \
 && rm -rf /var/lib/apt/lists/*
COPY axway/ /opt/axway/apigateway/
COPY apimgr-policy-debugger/ /opt/apimgr-policy-debugger/
ENV AXWAY_HOME=/opt/axway/apigateway APD_HEAP=1024m
WORKDIR /work
ENTRYPOINT ["sh", "/opt/apimgr-policy-debugger/bin/apimgr-policy-debugger"]
CMD ["--help"]
