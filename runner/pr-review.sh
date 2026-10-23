#!/usr/bin/env bash
# Runs one Claude Code review for the IoT Platform model users.admin.models.prReviewer.
#
# Called by the server as:
#   sudo -n -u reviewer pr-review.sh <prId> <commit> <destination> <model> <maxTurns> <effort>
# stdin:  line 1 = base64 system prompt, the rest = task prompt
# stdout: Claude Code's JSON result (last line); git and diagnostics go to stderr.
#
# Install as /usr/local/bin/pr-review.sh, owned by root, mode 0755.
set -euo pipefail

# Optional per-user settings (sudo resets the environment): ANTHROPIC_API_KEY,
# PR_REVIEW_CLONE, PR_REVIEW_WORKTREES, PR_REVIEW_CLAUDE.
if [[ -f "$HOME/.pr-review.env" ]]; then
  set -a
  # shellcheck source=/dev/null
  . "$HOME/.pr-review.env"
  set +a
fi

CLONE="${PR_REVIEW_CLONE:-$HOME/repo}"
WORKTREES="${PR_REVIEW_WORKTREES:-$HOME/worktrees}"
CLAUDE="${PR_REVIEW_CLAUDE:-$HOME/.local/bin/claude}"

if [[ $# -ne 6 ]]; then
  echo "usage: $0 <prId> <commit> <destination> <model> <maxTurns> <effort>" >&2
  exit 2
fi
PR="$1" SHA="$2" DEST="$3" MODEL="$4" TURNS="$5" EFFORT="$6"
if ! [[ $PR =~ ^[0-9]+$ && $SHA =~ ^[0-9a-f]{7,40}$ && $DEST =~ ^[A-Za-z0-9._/-]+$ \
    && $DEST != *..* && $MODEL =~ ^[A-Za-z0-9._-]+$ && $TURNS =~ ^[0-9]+$ \
    && $EFFORT =~ ^(low|medium|high|xhigh|max)$ ]]; then
  echo "invalid arguments" >&2
  exit 2
fi

IFS= read -r SYSTEM_B64
SYSTEM="$(printf '%s' "$SYSTEM_B64" | base64 -d)"

git -C "$CLONE" fetch --quiet --prune origin >&2
FULL="$(git -C "$CLONE" rev-parse --verify --quiet "$SHA^{commit}")" || {
  echo "commit $SHA not found in $CLONE after fetch" >&2
  exit 3
}

# The review reads code only; LFS binaries would just slow every checkout down.
export GIT_LFS_SKIP_SMUDGE=1
mkdir -p "$WORKTREES"
WT="$WORKTREES/pr-$PR-$SHA"
cleanup() {
  git -C "$CLONE" worktree remove --force "$WT" >/dev/null 2>&1 || true
}
trap cleanup EXIT
cleanup
git -C "$CLONE" worktree add --quiet --detach "$WT" "$FULL" >&2
cd "$WT"

# The rest of stdin is the task prompt. Only read-only tools are allowed; in print mode
# anything else is denied rather than prompted.
"$CLAUDE" -p --output-format json --model "$MODEL" --effort "$EFFORT" --max-turns "$TURNS" \
  --append-system-prompt "$SYSTEM" \
  --allowedTools "Read" "Grep" "Glob" "Bash(git diff:*)" "Bash(git log:*)" "Bash(git show:*)" \
  --disallowedTools "Edit" "Write" "NotebookEdit" "WebFetch" "WebSearch"
