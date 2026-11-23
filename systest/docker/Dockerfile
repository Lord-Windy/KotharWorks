# KotharWorks systest toolbox image.
#
# One image for every role in the system-test stack: building the reactor
# (Maven + JDK 25), running the unit/integration suite (Testcontainers via the
# host Docker socket), hosting wilds-server, and driving the live suite
# (headless Chromium for emulated humans).
#
# Deliberately classic-builder compatible (no BuildKit-only syntax): the Maven
# repository is cached in a named volume at run time instead of in layers.

# JDK pinned to the project toolchain (.sdkmanrc java=25.0.3-tem). Patch levels
# matter: e.g. 25.0.4's jdk.httpserver answers 400 (not 404) for "//path" request
# targets, which AuthSecurityGateTest pins. ./systest.sh warns if these drift.
ARG JDK_IMAGE=eclipse-temurin:25.0.3_9-jdk-noble
FROM ${JDK_IMAGE}

ARG MAVEN_VERSION=3.9.16
# Must match <playwright.version> in systest/pom.xml (./systest.sh passes it in),
# so the bundled Chromium is exactly the build the Java driver expects.
ARG PLAYWRIGHT_VERSION=1.65.0

# curl: healthchecks. libatomic1: the Node runtime bundled inside the Playwright driver.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates libatomic1 \
    && curl -fsSL -o /tmp/maven.tgz \
        "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
    && curl -fsSL -o /tmp/maven.tgz.sha512 \
        "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz.sha512" \
    && echo "$(cut -d' ' -f1 /tmp/maven.tgz.sha512)  /tmp/maven.tgz" | sha512sum -c - \
    && mkdir -p /opt/maven && tar -xzf /tmp/maven.tgz -C /opt/maven --strip-components=1 \
    && ln -s /opt/maven/bin/mvn /usr/local/bin/mvn \
    && rm -f /tmp/maven.tgz /tmp/maven.tgz.sha512
ENV MAVEN_HOME=/opt/maven

ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
COPY playwright/pom.xml /tmp/pw/pom.xml
RUN mvn -B -ntp -q -f /tmp/pw/pom.xml -Dmaven.repo.local=/tmp/pw/repo \
        -Dplaywright.version=${PLAYWRIGHT_VERSION} \
        dependency:copy-dependencies -DoutputDirectory=/tmp/pw/lib \
    && java -cp '/tmp/pw/lib/*' com.microsoft.playwright.CLI install --with-deps chromium \
    && rm -rf /tmp/pw /var/lib/apt/lists/* /root/.m2

# Browsers are baked in above; never download at test time.
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1

LABEL org.opencontainers.image.title="kotharworks-systest-toolbox" \
      com.kotharworks.playwright.version="${PLAYWRIGHT_VERSION}"
