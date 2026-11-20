# KotharWorks

Java 25 LTS modular monorepo (`com.kotharworks`) — sister repository to `ptah`, built for pure Data-Oriented Java libraries (`libs/`) and lightweight, Virtual-Thread applications (`apps/`).

- **Language:** Pure Java 25 LTS (`-XX:+UseCompactObjectHeaders`, `-Werror`)
- **Build:** Apache Maven Daemon (`mvnd`, configured via `.mvn/mvnd.properties`)
- **Inventory:** See [PROJECTS.md](PROJECTS.md)
- **Agent & Ticket Workflow:** See [AGENTS.md](AGENTS.md)
- **Planning Docs:** See [planning/README.md](planning/README.md)

## Quickstart

```bash
# Run all tests across every library and application
mvnd test

# Run the Ironwood Wilds server hello-world scaffold
mvnd -q -Dmvnd.rawStreams=true compile exec:java -pl apps/wilds-server -am

# Test a single module (and any upstream library dependencies)
mvnd test -pl libs/wilds -am
```

*(Note: `.mvn/mvnd.properties` pins the KotharWorks daemon to JDK `25.0.3-tem` with a 512 MB max heap, so `mvnd` runs on Java 25 automatically without changing your global SDKMAN default Java version.)*

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
