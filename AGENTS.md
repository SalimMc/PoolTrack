# AGENTS.md

Durable rules for any AI agent working in this repository. Read this before acting.

## The app

PoolTrack — a Kotlin/Android app (`com.github.se.pooltrack`) built with Jetpack Compose and an **MVVM** architecture. It tracks pool entries:

- Displays the user's subscription (a PDF) at max screen brightness so it can be scanned at the pool entrance.
- Keeps a log of the dates the subscription was scanned.
- Only counts a scan as an entry once the user confirms the scanner accepted it; a declined scan is not counted.

Where things live:

- `model/` holds the data and repositories (on-device DataStore, Firebase auth and Firestore backup, ...).
- `ui/` holds the screens and their **ViewModels**.
- `app/src/test/` holds the JVM unit tests; shared fakes are in its `utils/`. `app/src/androidTest/` holds the instrumented tests.

## Architecture rules

- Keep the **MVVM** separation. **ViewModels never import Firebase**, a repository implementation or `Application`; they depend on repository interfaces, taken as constructor parameters that default to the matching `XRepositoryProvider.repository`. Firebase lives only in the `model/` repositories.
- Do not edit generated code.

## Dependency lock

Every resolved version, transitive ones included, is pinned in `app/gradle.lockfile` and
`settings-gradle.lockfile` (Gradle dependency locking). A build fails if it resolves something else.
After every version bump (`gradle/libs.versions.toml`, a plugin, Gradle itself), regenerate and
commit them with the change:

```
./gradlew resolveAndLockAll --write-locks
```

On a Dependabot PR, run the same command on its branch and push the result.
Review the lockfile diff like any other code. Never edit a lockfile by hand.

## Definition of done

- The feature works and matches its acceptance criteria.
- **All new code comes with unit tests.**
- `./gradlew check` is green (unit tests + lint) and `./gradlew ktfmtCheck` passes (formatting) before you submit.
- Features covered by instrumented tests (under `androidTest/`) must also pass `./gradlew connectedDebugAndroidTest`, with an Android emulator running.
- CI (`.github/workflows/CI.yml`) runs all of the above on every PR, and must be green.

## How to work

- **Never commit directly to `main`.** Work on a feature branch and open a pull request; the human reviews and merges it.
- Make one **bounded, reviewable** change per PR. If it sprawls across unrelated files, split it.
- Read the failing tests carefully and iterate until `./gradlew check` passes.
- **Commit after every change**, however small. Don't batch unrelated changes into one commit.
- Stage only the files you changed; never `git add .` or `git add -A` (it can pull in local config like `local.properties`, `.idea/`, `.gradle/`).
- Commit with an imperative subject of at most 50 characters, capitalized (e.g. `Add subscription PDF viewer`). Add a body wrapped at 72 characters when the subject is not enough.
- Acknowledge contributors: credit the AI that wrote the change with a `Co-authored-by` line.
- Push the branch and open a PR against `main` once the change is ready for review. Don't merge it yourself.

## Your role

You provide the goal, the context, the acceptance criteria and the permissions. The agent plans, acts and observes. **You review the diff, and you own every line you submit.** "The agent wrote it" is not a defence.
