---
title: Workflow
parent: Development
grand_parent: Internal
nav_order: 2
---

# Development workflow

## Branches and pull requests

Work happens on feature branches. `main` is protected:

- Changes reach `main` only through a pull request.
- A PR needs at least one approval. You cannot approve your own PR.
- The "Secret scan" and "Build & test" status checks must pass.

A merge to `main` triggers a production deploy. Treat merging to main as shipping. See [CI/CD]({% link internal/cicd.md %}).

## What happens on your branch

Every push that touches anything outside `docs/`, on any branch, runs the secret scan and the build-and-test job, and rebuilds the judge sandbox image if you touched `kt-judge/sandbox/`. Feature branches do not deploy.

Changes confined to `docs/` skip that pipeline entirely (`ci.yml` sets `paths-ignore: ['docs/**']`, so a docs typo cannot restart production) and instead run the `Docs` workflow, which builds the Jekyll site and, on `main`, publishes it to GitHub Pages.

If you want to try your branch's build on a real machine, download the `cs30-<sha>` jar artifact from the branch's Actions run and run it yourself, rather than deploying.

## Before you push

- Run `./gradlew test` locally. The same tests gate the merge.
- Do not commit secrets. The secret scan runs over full history, so a committed secret is a problem even if you remove it in a later commit. If you leak one, rotate it, do not just delete the commit.
- Keep `deploy/application.properties` free of real secret values. It uses `${...}` placeholders that are filled from the environment in production. See [configuration]({% link internal/deployment/configuration.md %}).

## Touching the judge sandbox

The sandbox image is only rebuilt when files under `kt-judge/sandbox/` change, and only pushed to GHCR from `main`. If you change how the sandbox is built (the Dockerfile, the entry script, the baked tool versions), expect the `docker-judge` job to build it. On a feature branch it builds to validate but does not push; on `main` it pushes `judge-sandbox:latest` and `:<sha>`, and the next deploy pulls `:latest` — that's the image the judge runs (`judge.image`).

## Changing the database schema

There is no migration tool. The schema is whatever Hibernate derives from the entities, applied with `ddl-auto=update`. That makes adding columns easy but makes rollbacks risky: an older jar may not match a newer schema. If you change an entity in a way that changes the schema, call it out in the PR, because it affects whether a deploy can be safely rolled back.
