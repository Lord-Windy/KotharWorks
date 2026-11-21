# Resolve Merge Conflict

You are resolving merge conflicts for branch: {{branch}}

## Conflict content
```
{{conflict_content}}
```

## Git conflict marker format

The conflict content uses git's standard markers:
- `<<<<<<< HEAD` ... `=======` : changes from the base branch (current HEAD)
- `=======` ... `>>>>>>> branch-name` : incoming changes from the feature branch

## Project context (KotharWorks, Java 25 LTS)

- Preserve Apache 2.0 license headers; never strip them.
- In `pom.xml` conflicts, merge `<modules>` lists and
  `<dependencyManagement>`/`<dependencies>` entries without duplication —
  keep one canonical copy of each module or dependency.
- Never resolve an `import` or dependency conflict by reintroducing
  forbidden technology (Kotlin, Lombok, Spring Boot, Hibernate/JPA,
  annotation processors) or I/O inside `libs/*`.
- Keep naming consistent: `PascalCase` types, `camelCase` members, packages
  under `com.kotharworks`.

## Instructions

1. **Combine both sides** when the changes are independent — additions on
   one side and unrelated additions on the other should both survive.
2. **Avoid duplicating** imports, declarations, or method definitions that
   appear on both sides — keep one canonical copy.
3. When there's a true logical conflict, prefer the incoming (feature
   branch) changes.
4. Output ONLY the resolved file content — no explanations, no markdown
   code fences.
5. The output must be complete, syntactically correct, and ready to use as-is.

Do not ask questions. Output the final merged file immediately.
