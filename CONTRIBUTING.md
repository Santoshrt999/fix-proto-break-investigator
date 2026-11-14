# Contributing

Thanks for helping. This project helps people work out why a trade broke from FIX logs. A few
rules keep it safe to use and safe to contribute to.

## Ground rules

- **Public specs and synthetic data only.** Use the public FIX 4.2 / 4.4 / 5.0 SP2
  specifications. Never commit real logs, real account or client identifiers, proprietary venue
  formats or rules of engagement, or the name of a real firm. Test data comes from the generator
  (`fixscan generate`) or is built in tests with the `Fix` test helper.
- **Read-only.** The engine and the skill diagnose. Nothing may send, replay, amend or cancel
  orders, write FIX messages for someone to inject, or modify the input logs.
- **Masking stays on by default.** New output must go through `Masker` before it leaves the
  engine. If you add a field that can carry client data, add it to masking and to `MaskerTest`.
- **Zero runtime dependencies.** The engine is plain Java 21. JUnit 5 is the only dependency, in
  test scope. Do not add libraries.
- **Small output.** Every command must keep its output bounded (`--limit`, evidence capped at 5
  lines per finding) so it stays useful to a model and to a terminal.

## Build and test

```sh
cd engine
./mvnw verify                 # build, all unit tests, golden test, performance smoke test
./mvnw -DskipTests package    # just the jar: engine/target/fixscan.jar
```

Requires a JDK 21 or newer. `bin/fixscan` builds the jar on first use if it is missing.

## Adding a break detector (rule)

1. Create `engine/src/main/java/io/fixbreak/rules/<Name>Rule.java` implementing `Rule`:
   - `id()`: a stable `UPPER_SNAKE_CASE` id;
   - `description()`: one sentence (shown by `fixscan rules`);
   - `onMessage`, `onParseIssue` and/or `onEnd`. Use the decoded state in `MessageContext`
     (`ctx.update` for the order, `ctx.session` for the session) rather than re-parsing tags.
   - Report with `out.report(...)`: severity, session, order, times, a one-line summary that
     names the tags and values involved, and up to five evidence refs (the line that proves the
     break first).
2. Register it in `Rules.defaults()`.
3. Add `<Name>RuleTest` extending `RuleTest`: at least one case that fires, and one look-alike
   that must not fire (legitimate flow that resembles the break).
4. Plant one instance in `SampleGenerator`, regenerate the sample and expected findings, and
   check the golden test passes:
   ```sh
   bin/fixscan generate --out samples/day1.log --expected samples/expected-findings.json
   cd engine && ./mvnw verify
   ```
   Review the diff of `samples/` by eye: the new break should be the only change in findings.
5. Document it for the investigator agent in
   `skills/fix-break-investigator/references/break-playbook.md` (symptom, likely causes, how to
   confirm with fixscan, who to contact) and add its severity to
   `skills/fix-break-investigator/references/fixscan-output.md`.
6. Add it to the break types table in `README.md`.

## Changing the skill

The skill (`skills/fix-break-investigator/`) is instructions for an AI agent. When you change it:

- keep `SKILL.md` focused on the investigation loop and the ground rules; put detail in
  `references/`;
- every command, option and output field it mentions must exist in the engine; check against
  real output on `samples/day1.log`;
- test it end to end: install the plugin from your clone (see the README) and ask a question
  about one of the planted breaks, such as "why was ORD-000018 rejected?".

## Dictionary

Built-in tag and enum definitions live in
`engine/src/main/resources/io/fixbreak/dict/fix-dictionary.json`. Only add tags and values from
the public FIX specifications. Venue or firm custom tags belong in a user's own `--dict` file,
not in this repository.

## Maintainer notes: publishing

Things to check when publishing the repository or moving it.

- **The CI badge shows "no status" until the first workflow run.** The badge at the top of
  `README.md` points at `.github/workflows/ci.yml` on GitHub. It stays grey until that workflow
  has run once, which happens on the first push. After pushing, check it turns green in the
  Actions tab. If it fails there but passes locally, look at the JDK first: CI uses JDK 21, and
  local development may use a newer one.
- **The repository path is hardcoded.** The install instructions and links assume the repository
  lives at `Santoshrt999/fix-proto-break-investigator` on GitHub. If it is renamed,
  transferred to an organisation or forked for release, update every occurrence:
  ```sh
  git grep -n 'Santoshrt999/fix-proto-break-investigator'
  ```
  At the time of writing this finds `README.md` (badge, clone URL, `/plugin marketplace add`
  commands, team `settings.json` example) and `.claude-plugin/plugin.json` (`homepage`,
  `repository`). Then run `claude plugin validate .` and reinstall from the new path to check:
  ```sh
  claude plugin marketplace add <new-owner>/<new-repo>
  claude plugin install fix-break-investigator@fix-tools
  ```
  The marketplace name (`fix-tools`) and the plugin name (`fix-break-investigator`) do not
  depend on the repository name, so `fix-break-investigator@fix-tools` stays the same.

## Pull requests

- One logical change per PR, with tests.
- CI (`.github/workflows/ci.yml`) must pass: build, tests, wrapper smoke test, and the check
  that committed samples match the generator.
- Contributions are licensed under Apache-2.0 (see `LICENSE`).
