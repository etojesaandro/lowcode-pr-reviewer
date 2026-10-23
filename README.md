# Claude PR Reviewer for an IoT Platform

An IoT Platform low-code solution that reviews Bitbucket pull requests with Claude Code:

- Every 5 minutes it finds open PRs whose Jira issue is a **Bug** or **Performance Issue** and
  whose diff is at most 500 lines in 20 files.
- It reviews each PR head commit **once**, with Claude Code (`claude-opus-5-5`, effort `medium`,
  read-only tools).
- It posts the review as a PR comment ending with a signature: agent, exact model, effort,
  every token count, cost, turns, time and PR size.
- It sets **"Changes requested"** when the review has a MAJOR or BLOCKING finding.
- It records the tokens and cost of every review (kept 1 year) and shows them on a dashboard.

How it is built: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Repository contents

| Path | What it is |
|---|---|
| `platform/prReviewer.prs` | Export of the model `users.admin.models.prReviewer`: variables, 11 functions, the event and the polling binding. Definitions only, with no data or credentials |
| `platform/prReviewCost.prs` | Export of the dashboard `users.admin.dashboards.prReviewCost` |
| `runner/pr-review.sh` | Runner script: prepares a git worktree and runs Claude Code as the `reviewer` user |
| `prompts/architecture-system.md` | Reviewer instructions (the model's `system` prompt) |
| `prompts/architecture-task.md` | Per-PR task template (the model's `task` prompt) |
| `model/src/*.java` | Sources of the model's Java functions: the same code as in the `.prs`, kept readable and diffable |
| `model/payload.py` | Builds the model's `modelFunctions` / `modelEvents` tables (JSON) from `model/src` |
| `docs/ARCHITECTURE.md` | Architecture, data model, security and observed costs |

Two things stay specific to the platform and the codebase under review:

- The `import` lines of the Java sources (and of the functions inside `prReviewer.prs`) name the
  platform's own SDK packages. The code does not compile without them, so they are kept as they are.
- `prompts/architecture-system.md` describes one Gradle monorepo. Its module and package names
  (`platform-api`, `platform-core`, `com.example.*`, …) are placeholders: replace them with the
  modules of the codebase you review.

## Requirements

- IoT Platform server on Linux, with the web UI and the HTTP device driver.
- On the server host: `git`, `sudo`, and access to the Bitbucket repository with an SSH access key.
- Claude Code CLI 2.1 or later, installed for a dedicated `reviewer` user and logged in, either
  through a Claude subscription or with an API key.
- A Bitbucket Cloud API token with **pull requests: read and write** and **repositories: read**.
  Comments and change requests appear as the token's owner, so a dedicated bot account is advisable.
- A Jira Cloud API token that can read issues.

## Installation

### 1. The `reviewer` user and the runner (on the server host, as root)

```bash
sudo useradd -m -s /bin/bash reviewer
sudo install -o root -g root -m 0755 runner/pr-review.sh /usr/local/bin/pr-review.sh

# Claude Code for reviewer, then log in once (/login, then /exit)
sudo -u reviewer -i bash -c 'curl -fsSL https://claude.ai/install.sh | bash'
sudo -u reviewer -i /home/reviewer/.local/bin/claude

# SSH key, to be added to the Bitbucket repository as a read-only access key
sudo -u reviewer -i ssh-keygen -t ed25519 -N '' -f /home/reviewer/.ssh/id_ed25519
sudo cat /home/reviewer/.ssh/id_ed25519.pub

# Clone without a checkout: every review gets its own temporary worktree
sudo -u reviewer -i git clone --no-checkout git@bitbucket.org:<workspace>/<repo>.git repo
```

Optional settings go in `/home/reviewer/.pr-review.env` (mode 600): `ANTHROPIC_API_KEY` or
`CLAUDE_CODE_OAUTH_TOKEN` instead of `/login`, and `PR_REVIEW_CLONE`, `PR_REVIEW_WORKTREES`,
`PR_REVIEW_CLAUDE` to change the default paths.

If the IoT Platform does not run as root, allow its service user to start only the runner:
`<platform-service-user> ALL=(reviewer) NOPASSWD: /usr/local/bin/pr-review.sh`.

**Test the runner** with a cheap model before going further:

```bash
SHA=<any commit of the repository>
{ printf 'You are a terse reviewer.' | base64 -w0; echo; echo 'Reply with the word OK.'; } \
  | sudo -u reviewer /usr/local/bin/pr-review.sh 1 "$SHA" master haiku 3 low
```

The last line printed should be JSON containing `"subtype":"success"`.

### 2. HTTP devices (under `users.admin.devices`)

The functions use these exact device names.

| Setting | `bitbucket` | `jira` |
|---|---|---|
| Driver | HTTP | HTTP |
| Address / protocol / port | `api.bitbucket.org` / HTTPS / 443 | `<site>.atlassian.net` / HTTPS / 443 |
| Enable Authentication | on | on |
| Username | Atlassian account email | Atlassian account email |
| Password (secret field) | Bitbucket API token | Jira API token |
| Timeout | 30000 | 30000 |

Never put a token in "Additional HTTP Headers": that field is stored and exported in plain text.

### 3. Model and dashboard

1. Import `platform/prReviewer.prs` under **Models**, keeping the name `prReviewer`.
   The functions and the dashboard refer to `users.admin.models.prReviewer`.
2. Import `platform/prReviewCost.prs` under **Dashboards**, keeping the name `prReviewCost`.
3. In the model's **prompts** variable, add two rows:
   - `system` with the contents of `prompts/architecture-system.md`;
   - `task` with the contents of `prompts/architecture-task.md`.
4. Check **config**: workspace, repo, `runAsUser`, `runnerScript`, and the model and effort.
   After an import, `enabled` and `postComments` are both off.

### 4. Dry run, then go live

1. Keep `postComments = false`, then call the model function `poll()` by hand.
   - The `ledger` fills with SKIPPED and DRY_RUN rows.
   - Each review appears in the `reviewCompleted` event history and on the dashboard; nothing is posted.
2. Read a few reviews. If you want a reviewed commit reviewed again, delete its ledger row.
3. Set `postComments = true` and `enabled = true`. The binding then runs `poll()` every 5 minutes.

## Operating it

| To… | Do this |
|---|---|
| Stop everything | `config.enabled = false` |
| Keep reviewing but stop posting | `config.postComments = false` |
| Stop requesting changes | `config.requestChanges = false` |
| Review a PR again | Delete its row in `ledger`; the next cycle reviews it |
| Change the model or depth | `config.model` (`opus`, `sonnet`, …) and `config.effort` (`low` to `max`) |
| Allow larger PRs | `config.maxChangedLines`, `config.maxChangedFiles` |
| Speed up or slow down | `config.maxReviewsPerCycle`, or the binding period |
| See costs | Dashboard **PR Review Cost and Tokens**, or the `reviewCompleted` history |
| Find out why a PR was skipped | The `error` column of its `ledger` row |

## Changing the code

The model's functions are "Java Code" model functions. `model/src` holds the same code with the
class name `%ScriptClassNamePattern%`, which the platform replaces when it compiles.

1. Edit `model/src/<Function>.java`. Do not use backslashes in the sources: uploading through
   the IoT Platform MCP server unescapes them a second time. Use `System.lineSeparator()`,
   `String.lines()` and `[.]` in regular expressions instead.
2. Compile against the server's JARs to catch errors before uploading:

   ```bash
   mkdir -p out && for f in model/src/*.java; do c=$(basename "$f" .java); \
     sed "s/%ScriptClassNamePattern%/$c/" "$f" > "out/$c.java"; done
   javac -encoding UTF-8 -d out -cp "<platform-home>/jar/*:<platform-home>/lib/*" out/*.java
   ```

3. Upload the change in one of two ways:
   - paste the source into the function's Implementation in the model's Functions editor; or
   - run `python3 model/payload.py out/` and write `out/functions.json` (and `out/events.json`)
     to the model's `modelFunctions` (and `modelEvents`) variables, for example through the
     IoT Platform MCP server.
4. Export the model again (**Export** → `prReviewer.prs`) and commit the new `.prs` file.

## Costs

The first live run reviewed 19 PRs of 45–465 changed lines with `claude-opus-5-5` at effort
`medium`:

- $8.14 in total, $0.43 per review on average ($0.26–$0.78);
- 56 s per review on average;
- about $0.22 per 100 changed lines.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#7-observed-cost-first-live-run-2026-10-23).
