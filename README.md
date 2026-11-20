# KotharWorks

Java 25 LTS modular monorepo (`com.kotharworks`) — sister repository to `ptah`, built for pure Data-Oriented Java libraries (`libs/`) and lightweight, Virtual-Thread applications (`apps/`).

- **Language:** Pure Java 25 LTS (`-XX:+UseCompactObjectHeaders`, `-Werror`)
- **Build:** Apache Maven multi-module reactor (`./mvnw` included; `mvnd` compatible)
- **Inventory:** See [PROJECTS.md](PROJECTS.md)
- **Agent & Ticket Workflow:** See [AGENTS.md](AGENTS.md)
- **Planning Docs:** See [planning/README.md](planning/README.md)

## Quickstart

```bash
# Run all tests across every library and application
./mvnw test

# Run the Ironwood Wilds server hello-world scaffold
./mvnw -q compile exec:java -pl apps/wilds-server -am

# Test a single module (and any upstream library dependencies)
./mvnw test -pl libs/wilds -am
```

*(If you have SDKMAN installed, `.sdkmanrc` pins `java=25.0.3-tem`, and `./mvnw` automatically selects it even in non-interactive shells. Installing `mvnd` via `sdk install mvnd` drops warm incremental test runs below half a second.)*

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
