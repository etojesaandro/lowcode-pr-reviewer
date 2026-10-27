import com.tibbo.aggregate.common.context.*;
import com.tibbo.aggregate.common.datatable.*;
import com.tibbo.aggregate.common.server.*;

import com.tibbo.linkserver.*;
import com.tibbo.linkserver.context.*;

import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One polling cycle: reviews every open PR whose Jira issue has a configured bug type and whose
 * diff is within the configured size, once per PR: later pushes are not reviewed again. Called
 * with a prId (by reviewPr), it reviews only that open PR, whatever its issue type and size; a
 * commit already reviewed is refused unless force is set. The ledger row is written before the
 * review starts, so a crash never causes a second review; a leftover RUNNING row is marked
 * FAILED on the next cycle.
 * The ledger is saved as a copy each time: a stored table becomes immutable, the working one not.
 *
 * @author Alexander Sidorov
 *     <p>(co-authored with Claude Opus 5.5)
 */
public class %ScriptClassNamePattern% implements FunctionImplementation {
  public DataTable execute(Context con, FunctionDefinition def, CallerController caller,
      RequestController request, DataTable parameters) throws ContextException {
    DataRecord cfg = con.getVariable("config", caller).rec();
    DataTable ledger = con.getVariable("ledger", caller).clone();
    for (DataRecord row : ledger) {
      if ("RUNNING".equals(row.getString("status"))) {
        row.setValue("status", "FAILED");
        row.setValue("error", "Interrupted before completion");
        row.setValue("finishedAt", new Date());
      }
    }
    con.setVariable("ledger", caller, ledger.clone());
    Pattern destinations = Pattern.compile(cfg.getString("destinationRegex"));
    Pattern keys = Pattern.compile(cfg.getString("jiraKeyRegex"));
    Set<String> bugTypes = new HashSet<>();
    for (String type : cfg.getString("bugTypes").split(",")) {
      bugTypes.add(type.trim().toLowerCase(Locale.ROOT));
    }
    int open = 0;
    int reviewed = 0;
    int skipped = 0;
    int failed = 0;
    String error = null;
    DataRecord in = parameters.getRecordCount() == 0 ? null : parameters.rec();
    int requested = in == null || in.getValue("prId") == null ? 0 : in.getInt("prId");
    boolean force = in != null && Boolean.TRUE.equals(in.getBoolean("force"));
    try {
      DataTable prs = con.callFunction("openPrs", caller);
      open = prs.getRecordCount();
      if (requested > 0) {
        if (manual(con, caller, cfg, ledger, keys, prs, requested, force)) {
          reviewed++;
        } else {
          failed++;
        }
        return new SimpleDataTable(def.getOutputFormat(), open, reviewed, skipped, failed);
      }
      for (DataRecord pr : prs) {
        int id = pr.getInt("prId");
        if (!destinations.matcher(pr.getString("destination")).matches() || seen(ledger, id)) {
          continue;
        }
        String key = jiraKey(keys, pr.getString("title"), pr.getString("source"));
        if (key == null) {
          skip(con, caller, ledger, pr, null, null, "No Jira key in title or branch");
          skipped++;
          continue;
        }
        DataRecord issue = con.callFunction("issueOf", caller, key).rec();
        String type = issue.getString("issueType");
        if (!Boolean.TRUE.equals(issue.getBoolean("found"))) {
          skip(con, caller, ledger, pr, key, null, "Jira issue not found");
          skipped++;
          continue;
        }
        if (!bugTypes.contains(type.toLowerCase(Locale.ROOT))) {
          skip(con, caller, ledger, pr, key, type, null);
          skipped++;
          continue;
        }
        if (reviewed + failed >= cfg.getInt("maxReviewsPerCycle")) {
          continue;
        }
        DataRecord size = con.callFunction("sizeOf", caller, id).rec();
        if (size.getInt("lines") > cfg.getInt("maxChangedLines")
            || size.getInt("files") > cfg.getInt("maxChangedFiles")) {
          skip(con, caller, ledger, pr, key, type, "Too large: " + size.getInt("lines")
              + " lines in " + size.getInt("files") + " files");
          skipped++;
          continue;
        }
        if (review(con, caller, cfg, ledger, pr, key, type, issue.getString("summary"), size)) {
          reviewed++;
        } else {
          failed++;
        }
      }
    } catch (ContextException ex) {
      error = ex.getMessage();
      throw ex;
    } finally {
      DataTable last = con.getVariable("lastPoll", caller).clone();
      last.rec().setValue("time", new Date()).setValue("openPrs", open)
          .setValue("reviewed", reviewed).setValue("skipped", skipped)
          .setValue("failed", failed).setValue("error", error);
      con.setVariable("lastPoll", caller, last);
    }
    return new SimpleDataTable(def.getOutputFormat(), open, reviewed, skipped, failed);
  }

  /** Reviews one PR on request, skipping the issue type and size filters. */
  private static boolean manual(Context con, CallerController caller, DataRecord cfg,
      DataTable ledger, Pattern keys, DataTable prs, int id, boolean force)
      throws ContextException {
    DataRecord pr = null;
    for (DataRecord candidate : prs) {
      if (candidate.getInt("prId") == id) {
        pr = candidate;
      }
    }
    if (pr == null) {
      throw new ContextException("PR " + id + " is not open");
    }
    String commit = pr.getString("commit");
    for (int i = ledger.getRecordCount() - 1; i >= 0; i--) {
      DataRecord row = ledger.getRecord(i);
      if (row.getInt("prId") != id || !commit.equals(row.getString("commit"))) {
        continue;
      }
      String status = row.getString("status");
      if (!force && ("DONE".equals(status) || "DRY_RUN".equals(status))) {
        throw new ContextException("PR " + id + " was already reviewed at commit " + commit
            + " (" + status + "); call it with force=true to review it again");
      }
      ledger.removeRecord(i);
    }
    String key = jiraKey(keys, pr.getString("title"), pr.getString("source"));
    String type = null;
    String summary = null;
    if (key != null) {
      DataRecord issue = con.callFunction("issueOf", caller, key).rec();
      if (Boolean.TRUE.equals(issue.getBoolean("found"))) {
        type = issue.getString("issueType");
        summary = issue.getString("summary");
      }
    }
    DataRecord size = con.callFunction("sizeOf", caller, id).rec();
    return review(con, caller, cfg, ledger, pr, key, type, summary, size);
  }

  private static boolean review(Context con, CallerController caller, DataRecord cfg,
      DataTable ledger, DataRecord pr, String key, String type, String summary, DataRecord size)
      throws ContextException {
    DataRecord row = add(ledger, pr, key, type, "RUNNING", null);
    row.setValue("startedAt", new Date());
    con.setVariable("ledger", caller, ledger.clone());
    DataTable prompts = con.getVariable("prompts", caller);
    String task = prompt(prompts, "task").replace("{prId}", String.valueOf(pr.getInt("prId")))
        .replace("{title}", pr.getString("title")).replace("{source}", pr.getString("source"))
        .replace("{destination}", pr.getString("destination"))
        .replace("{commit}", pr.getString("commit")).replace("{prUrl}", pr.getString("url"))
        .replace("{jiraKey}", key == null ? "none" : key)
        .replace("{issueType}", type == null ? "unknown" : type)
        .replace("{jiraSummary}", summary == null ? "" : summary);
    DataRecord result = con.callFunction("runReview", caller, pr.getInt("prId"),
        pr.getString("commit"), pr.getString("destination"), prompt(prompts, "system"), task)
        .rec();
    boolean ok = Boolean.TRUE.equals(result.getBoolean("successful"));
    String status = ok ? "DRY_RUN" : "FAILED";
    String error = result.getString("error");
    String commentId = null;
    boolean changesRequested = false;
    if (ok && Boolean.TRUE.equals(cfg.getBoolean("postComments"))) {
      try {
        commentId = con.callFunction("postComment", caller, pr.getInt("prId"),
            result.getString("review") + signature(cfg, pr, result, size)).rec()
            .getString("commentId");
        status = "DONE";
      } catch (ContextException ex) {
        ok = false;
        status = "FAILED";
        error = "Review done but not posted: " + ex.getMessage();
      }
      if (commentId != null && Boolean.TRUE.equals(cfg.getBoolean("requestChanges"))
          && severe(result.getString("review"))) {
        try {
          con.callFunction("requestChanges", caller, pr.getInt("prId"));
          changesRequested = true;
        } catch (ContextException ex) {
          error = "Comment posted, but requesting changes failed: " + ex.getMessage();
        }
      }
    }
    row.setValue("status", status).setValue("finishedAt", new Date())
        .setValue("commentId", commentId).setValue("error", error);
    con.setVariable("ledger", caller, ledger.clone());
    DataTable totals = con.getVariable("tokenTotals", caller).clone();
    DataRecord sum = totals.rec();
    sum.setValue("reviews", sum.getLong("reviews") + 1);
    sum.setValue("failed", sum.getLong("failed") + (ok ? 0 : 1));
    for (String field : new String[] {"inputTokens", "outputTokens", "cacheReadTokens",
        "cacheCreationTokens", "durationMs"}) {
      sum.setValue(field, sum.getLong(field) + result.getLong(field));
    }
    sum.setValue("costUsd", sum.getDouble("costUsd") + result.getDouble("costUsd"));
    con.setVariable("tokenTotals", caller, totals);
    DataTable event =
        new SimpleDataTable(con.getEventDefinition("reviewCompleted").getFormat(), true);
    event.rec().setValue("prId", pr.getInt("prId")).setValue("commit", pr.getString("commit"))
        .setValue("jiraKey", key).setValue("issueType", type).setValue("status", status)
        .setValue("model", cfg.getString("model")).setValue("modelId", result.getString("modelId"))
        .setValue("effort", cfg.getString("effort"))
        .setValue("linesChanged", size.getInt("lines"))
        .setValue("filesChanged", size.getInt("files"))
        .setValue("inputTokens", result.getLong("inputTokens"))
        .setValue("outputTokens", result.getLong("outputTokens"))
        .setValue("cacheReadTokens", result.getLong("cacheReadTokens"))
        .setValue("cacheCreationTokens", result.getLong("cacheCreationTokens"))
        .setValue("thinkingTokens", result.getLong("thinkingTokens"))
        .setValue("costUsd", result.getDouble("costUsd"))
        .setValue("numTurns", result.getInt("numTurns"))
        .setValue("durationMs", result.getLong("durationMs"))
        .setValue("apiDurationMs", result.getLong("apiDurationMs"))
        .setValue("permissionDenials", result.getInt("permissionDenials"))
        .setValue("sessionId", result.getString("sessionId")).setValue("commentId", commentId)
        .setValue("changesRequested", changesRequested)
        .setValue("review", result.getString("review")).setValue("error", error);
    con.fireEvent("reviewCompleted", caller, event);
    return ok;
  }

  /** A review with a MAJOR or BLOCKING finding asks the author for changes. */
  private static boolean severe(String review) {
    return review != null && (review.contains("**[MAJOR]") || review.contains("**[BLOCKING]"));
  }

  /**
   * The footer of a posted review: who wrote it (agent, exact model, effort) and what it took
   * (every token count, cost, turns, time) for a PR of what size at which commit.
   */
  private static String signature(DataRecord cfg, DataRecord pr, DataRecord result,
      DataRecord size) {
    long input = result.getLong("inputTokens");
    long output = result.getLong("outputTokens");
    long cacheRead = result.getLong("cacheReadTokens");
    long cacheWrite = result.getLong("cacheCreationTokens");
    String nl = System.lineSeparator();
    return nl + nl + "---" + nl + nl
        + "🤖 **Automated review by the Claude Code agent** — model `"
        + result.getString("modelId") + "`, effort `" + cfg.getString("effort") + "`" + nl + nl
        + String.format(Locale.ROOT, "- Tokens: input %,d · output %,d (thinking %,d)"
            + " · cache read %,d · cache write %,d · total %,d", input, output,
            result.getLong("thinkingTokens"), cacheRead, cacheWrite,
            input + output + cacheRead + cacheWrite) + nl
        + String.format(Locale.ROOT, "- Cost $%.4f · %d turns · %.1f s (API %.1f s)",
            result.getDouble("costUsd"), result.getInt("numTurns"),
            result.getLong("durationMs") / 1000.0, result.getLong("apiDurationMs") / 1000.0) + nl
        + String.format(Locale.ROOT, "- PR size %,d lines in %d files · commit `%s`",
            size.getInt("lines"), size.getInt("files"), pr.getString("commit"));
  }

  private static void skip(Context con, CallerController caller, DataTable ledger,
      DataRecord pr, String key, String type, String reason) throws ContextException {
    add(ledger, pr, key, type, "SKIPPED", reason).setValue("finishedAt", new Date());
    con.setVariable("ledger", caller, ledger.clone());
  }

  private static DataRecord add(DataTable ledger, DataRecord pr, String key, String type,
      String status, String error) {
    return ledger.addRecord().setValue("prId", pr.getInt("prId"))
        .setValue("commit", pr.getString("commit")).setValue("title", pr.getString("title"))
        .setValue("jiraKey", key).setValue("issueType", type).setValue("status", status)
        .setValue("error", error);
  }

  /** A PR with any ledger row, at any commit, was already checked and is not looked at again. */
  private static boolean seen(DataTable ledger, int id) {
    for (DataRecord row : ledger) {
      if (row.getInt("prId") == id) {
        return true;
      }
    }
    return false;
  }

  private static String jiraKey(Pattern keys, String title, String source) {
    for (String text : new String[] {title, source}) {
      Matcher match = keys.matcher(text == null ? "" : text);
      if (match.find()) {
        return match.group();
      }
    }
    return null;
  }

  private static String prompt(DataTable prompts, String name) throws ContextException {
    DataRecord row = prompts.select("name", name);
    if (row == null || row.getString("text") == null) {
      throw new ContextException("Prompt '" + name + "' is not defined");
    }
    return row.getString("text");
  }
}
