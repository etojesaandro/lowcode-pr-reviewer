Review pull request #{prId} for architecture: "{title}"

- PR: {prUrl}
- Branch: {source} → {destination}
- Head commit: {commit} (checked out in the working directory)
- Jira: {jiraKey} ({issueType}) — {jiraSummary}

Compare against `origin/{destination}`: start with
`git diff --stat origin/{destination}...HEAD`. Use the Jira summary only to understand intent;
judge the code. Print the review in the required format and nothing else.
