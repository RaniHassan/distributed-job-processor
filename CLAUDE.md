# Claude Code Project Instructions

This file contains mandatory development instructions for this repository.

## Source of Truth

- `docs/SPECIFICATION.md` is the authoritative technical specification for this project.
- Read the entire specification before starting any task.
- Follow its architecture, requirements, technology decisions, database design, API contracts and implementation priorities.
- Approved deviations from the specification are listed in `docs/ARCHITECTURE.md`.
- `README.md` is the reviewer-facing documentation of the delivered system; keep it accurate.
- Never silently change or ignore a requirement.
- Never introduce an unapproved technology or architectural change.

## Development Workflow

- Work incrementally, following the implementation phases in docs/SPECIFICATION.md.
- Implement only the currently approved phase.
- Before making changes, explain the implementation plan.
- After implementation, run relevant tests and report the results.
- Do not proceed to the next phase without approval.
- Do not claim functionality is implemented unless it has been verified.
- Keep documentation synchronized with the actual implementation.

## Engineering Rules

- Maintain clear separation of responsibilities.
- Follow clean code and SOLID principles where appropriate.
- Use PostgreSQL as the source of truth.
- Preserve atomic job claiming, lease ownership and fencing-token validation.
- Route job lifecycle changes through the Job Management Service.
- Enforce authentication, authorization and resource ownership.
- Do not introduce Redis, Kafka or RabbitMQ into the initial implementation without approval.
- Do not expose JPA entities directly through REST APIs.
- Do not leave critical features as placeholders or TODOs.

## Decision Management

If a requirement is ambiguous or a technical problem is discovered:
1. Explain the issue.
2. Explain its impact.
3. Propose a solution.
4. Wait for approval if it changes architecture, scope, security or data integrity.

## Git Rules

- Make focused, meaningful commits.
- Never force push.
- Never perform destructive Git operations without approval.
- Never merge or push to a remote repository without approval.
- Never commit secrets or credentials.

## Communication

At the end of every implementation phase, report:
- What was implemented.
- Which files were created or modified.
- Important design decisions.
- Tests executed.
- Test results.
- Known limitations.
- What is planned for the next phase.

Always prioritize correctness, reliability, maintainability and compliance with docs/SPECIFICATION.md over implementation speed.