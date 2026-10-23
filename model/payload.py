"""Builds the modelFunctions and modelEvents values of users.admin.models.prReviewer from src/.

Usage: python3 -I payload.py <out-dir>   (writes functions.json and events.json there)
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
N = {"nullable": True}


def fmt(fields, mn=1, mx=1):
    return [{"minRecords": mn, "maxRecords": mx, "reorderable": False, "unresizable": mn == mx,
             "bindings": [], "encoded": "", "namingExpression": None, "fields": fields}]


def f(name, kind, desc, **extra):
    field = {"name": name, "type": kind, "description": desc}
    field.update(extra)
    return field


def src(cls):
    with open(os.path.join(HERE, "src", cls + ".java"), encoding="utf-8") as file:
        text = file.read()
    if "\\" in text:
        raise SystemExit(cls + ".java contains a backslash; the MCP upload would mangle it")
    return text


TOKENS = [f("inputTokens", "L", "Input Tokens", defaultValue=0),
          f("outputTokens", "L", "Output Tokens", defaultValue=0),
          f("cacheReadTokens", "L", "Cache Read Tokens", defaultValue=0),
          f("cacheCreationTokens", "L", "Cache Write Tokens", defaultValue=0)]
PERIOD = [f("fromDate", "D", "From"), f("toDate", "D", "To")]

FUNCTIONS = [
    ("request", "HTTP JSON request through a device with preemptive auth", "Request", "admin",
     fmt([f("device", "S", "Device Path"), f("method", "S", "Method"), f("url", "S", "URL"),
          f("body", "S", "Body", **N)]),
     fmt([f("replyCode", "I", "Reply Code", **N), f("reply", "S", "Reply", **N)]), True, None),
    ("openPrs", "Open pull requests of the configured repository", "OpenPrs", "admin",
     fmt([], 0, 1),
     fmt([f("prId", "I", "PR"), f("title", "S", "Title"), f("source", "S", "Source Branch"),
          f("destination", "S", "Destination Branch"), f("commit", "S", "Head Commit"),
          f("url", "S", "URL")], 0, 2147483647), True, None),
    ("issueOf", "Jira issue type and summary", "IssueOf", "admin",
     fmt([f("key", "S", "Jira Key")]),
     fmt([f("found", "B", "Found"), f("issueType", "S", "Issue Type", **N),
          f("summary", "S", "Summary", **N)]), True, None),
    ("postComment", "Post a general comment on a Bitbucket PR", "PostComment", "admin",
     fmt([f("prId", "I", "PR"), f("text", "S", "Text")]),
     fmt([f("commentId", "S", "Comment Id", **N)]), True, None),
    ("sizeOf", "Changed lines and files of a PR, without excluded files", "SizeOf", "admin",
     fmt([f("prId", "I", "PR")]),
     fmt([f("lines", "I", "Changed Lines"), f("files", "I", "Changed Files"),
          f("excludedFiles", "I", "Excluded Files")]), True, None),
    ("requestChanges", "Mark a Bitbucket PR as Changes requested", "RequestChanges", "admin",
     fmt([f("prId", "I", "PR")]), fmt([f("requested", "B", "Requested")]), True, None),
    ("runReview", "Run one Claude Code review as the reviewer user", "RunReview", "admin",
     fmt([f("prId", "I", "PR"), f("commit", "S", "Head Commit"),
          f("destination", "S", "Destination Branch"), f("system", "S", "System Prompt"),
          f("task", "S", "Task Prompt")]),
     fmt([f("successful", "B", "Successful"), f("review", "S", "Review", **N),
          f("modelId", "S", "Model Ids", **N)] + TOKENS
         + [f("thinkingTokens", "L", "Thinking Tokens", defaultValue=0),
            f("costUsd", "E", "Cost, USD", defaultValue=0),
            f("numTurns", "I", "Turns", defaultValue=0),
            f("durationMs", "L", "Duration, ms", defaultValue=0),
            f("apiDurationMs", "L", "API Duration, ms", defaultValue=0),
            f("permissionDenials", "I", "Denied Tool Calls", defaultValue=0),
            f("sessionId", "S", "Session Id", **N), f("error", "S", "Error", **N)]), True, None),
    ("poll", "One polling cycle: review new bug PRs once per head commit", "Poll", "admin",
     fmt([], 0, 1),
     fmt([f("openPrs", "I", "Open PRs"), f("reviewed", "I", "Reviewed"),
          f("skipped", "I", "Skipped"), f("failed", "I", "Failed")]), False,
     "Not concurrent: overlapping calls queue up, so the ledger has a single writer."),
    ("usageHistory", "Reviews of a period with token usage and cost", "UsageHistory",
     "observer", fmt(PERIOD),
     fmt([f("time", "D", "Time"), f("prId", "I", "PR"), f("jiraKey", "S", "Jira Key", **N),
          f("status", "S", "Status", **N), f("changesRequested", "B", "Changes Requested"),
          f("model", "S", "Model", **N),
          f("modelId", "S", "Model Ids", **N), f("effort", "S", "Effort", **N),
          f("linesChanged", "I", "Changed Lines"), f("filesChanged", "I", "Changed Files"),
          f("inputTokens", "L", "Input Tokens"), f("outputTokens", "L", "Output Tokens"),
          f("thinkingTokens", "L", "Thinking Tokens"),
          f("cacheReadTokens", "L", "Cache Read Tokens"),
          f("cacheCreationTokens", "L", "Cache Write Tokens"),
          f("totalTokens", "L", "Total Tokens"), f("costUsd", "E", "Cost, USD"),
          f("costPer100Lines", "E", "Cost per 100 Lines, USD"), f("numTurns", "I", "Turns"),
          f("durationSec", "E", "Duration, s")], 0, 2147483647), True, None),
    ("usageByDay", "Daily review cost or tokens as chart rows", "UsageByDay", "observer",
     fmt(PERIOD + [f("metric", "S", "Metric")]),
     fmt([f("series", "S", "Series"), f("category", "S", "Day"), f("value", "E", "Value"),
          f("group", "S", "Group", **N)], 0, 2147483647), True, "metric: cost or tokens"),
    ("usageSummary", "Review count, tokens and cost of a period", "UsageSummary", "observer",
     fmt(PERIOD),
     fmt([f("reviews", "I", "Reviews"), f("failed", "I", "Failed"), f("tokens", "L", "Tokens"),
          f("outputTokens", "L", "Output Tokens"), f("costUsd", "E", "Cost, USD"),
          f("avgCostUsd", "E", "Average Cost, USD"), f("linesChanged", "L", "Changed Lines"),
          f("costPer100Lines", "E", "Cost per 100 Lines, USD")]), True, None),
]

EVENT_FIELDS = (
    [f("prId", "I", "PR"), f("commit", "S", "Head Commit"), f("jiraKey", "S", "Jira Key", **N),
     f("issueType", "S", "Issue Type", **N), f("status", "S", "Status"),
     f("model", "S", "Model", **N), f("modelId", "S", "Model Ids", **N),
     f("effort", "S", "Effort", **N), f("linesChanged", "I", "Changed Lines", **N),
     f("filesChanged", "I", "Changed Files", **N)]
    + TOKENS
    + [f("thinkingTokens", "L", "Thinking Tokens", defaultValue=0),
       f("costUsd", "E", "Cost, USD", defaultValue=0), f("numTurns", "I", "Turns", defaultValue=0),
       f("durationMs", "L", "Duration, ms", defaultValue=0),
       f("apiDurationMs", "L", "API Duration, ms", defaultValue=0),
       f("permissionDenials", "I", "Denied Tool Calls", defaultValue=0),
       f("sessionId", "S", "Session Id", **N), f("commentId", "S", "Comment Id", **N),
       f("changesRequested", "B", "Changes Requested", defaultValue=False),
       f("review", "S", "Review Text", **N), f("error", "S", "Error", **N)])

EVENTS = [{"name": "reviewCompleted", "description": "PR Review Completed",
           "format": fmt(EVENT_FIELDS),
           "help": "One event per review attempt with its token usage and review text",
           "level": 2, "group": None, "permissions": "observer", "firePermissions": "admin",
           "historyStorageTime": 31536000000, "inheritedFrom": None}]


def main(out):
    functions = [{"name": name, "description": desc, "inputformat": fin, "outputformat": fout,
                  "help": hlp, "group": "prReviewer", "permissions": perm,
                  "ownerPermissions": False, "type": 0, "implementation": src(cls),
                  "expression": "", "query": "", "concurrent": conc, "plugin": None}
                 for name, desc, cls, perm, fin, fout, conc, hlp in FUNCTIONS]
    for name, value in (("functions.json", functions), ("events.json", EVENTS)):
        with open(os.path.join(out, name), "w", encoding="utf-8") as file:
            json.dump(value, file, ensure_ascii=False)
    print(len(functions), "functions,", len(EVENT_FIELDS), "event fields")


if __name__ == "__main__":
    main(sys.argv[1])
