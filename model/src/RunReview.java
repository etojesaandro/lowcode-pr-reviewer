import com.tibbo.aggregate.common.context.*;
import com.tibbo.aggregate.common.datatable.*;
import com.tibbo.aggregate.common.server.*;

import com.tibbo.linkserver.*;
import com.tibbo.linkserver.context.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs one headless Claude Code review through the runner script, as the unprivileged reviewer
 * user, and returns the review text with its exact token usage and the models it ran on. The
 * runner reads the system prompt (base64, first stdin line) and the task prompt (rest of stdin)
 * and prints Claude's JSON result.
 *
 * @author Alexander Sidorov
 *     <p>(co-authored with Claude Opus 5.5)
 */
public class %ScriptClassNamePattern% implements FunctionImplementation {
  private static final int MAX_STDERR = 4000;

  public DataTable execute(Context con, FunctionDefinition def, CallerController caller,
      RequestController request, DataTable parameters) throws ContextException {
    DataRecord in = parameters.rec();
    DataRecord cfg = con.getVariable("config", caller).rec();
    String commit = in.getString("commit");
    String destination = in.getString("destination");
    if (!commit.matches("[0-9a-f]{7,40}")) {
      throw new ContextException("Not a commit hash: " + commit);
    }
    if (!destination.matches("[A-Za-z0-9._/-]+") || destination.contains("..")) {
      throw new ContextException("Unsafe branch name: " + destination);
    }
    List<String> command = Arrays.asList("sudo", "-n", "-u", cfg.getString("runAsUser"),
        cfg.getString("runnerScript"), String.valueOf(in.getInt("prId")), commit, destination,
        cfg.getString("model"), String.valueOf(cfg.getInt("maxTurns")), cfg.getString("effort"));
    DataTable result = new SimpleDataTable(def.getOutputFormat(), true);
    DataRecord out = result.rec();
    out.setValue("successful", false);
    Process process = null;
    try {
      process = new ProcessBuilder(command).start();
      ByteArrayOutputStream stdout = new ByteArrayOutputStream();
      ByteArrayOutputStream stderr = new ByteArrayOutputStream();
      Thread outReader = drain(process.getInputStream(), stdout);
      Thread errReader = drain(process.getErrorStream(), stderr);
      try (OutputStream stdin = process.getOutputStream()) {
        String system = Base64.getEncoder()
            .encodeToString(in.getString("system").getBytes(StandardCharsets.UTF_8));
        stdin.write((system + System.lineSeparator() + in.getString("task"))
            .getBytes(StandardCharsets.UTF_8));
      }
      if (!process.waitFor(cfg.getInt("timeoutMinutes"), TimeUnit.MINUTES)) {
        out.setValue("error", "Timed out after " + cfg.getInt("timeoutMinutes") + " min");
        return result;
      }
      outReader.join(10000);
      errReader.join(10000);
      JsonNode reply = lastJson(stdout.toString(StandardCharsets.UTF_8));
      if (reply == null) {
        out.setValue("error", "Exit " + process.exitValue() + ", no result: "
            + tail(stderr.toString(StandardCharsets.UTF_8)));
        return result;
      }
      out.setValue("review", reply.path("result").asText(null));
      usage(reply, out);
      out.setValue("costUsd", reply.path("total_cost_usd").asDouble());
      out.setValue("numTurns", reply.path("num_turns").asInt());
      out.setValue("durationMs", reply.path("duration_ms").asLong());
      out.setValue("apiDurationMs", reply.path("duration_api_ms").asLong());
      out.setValue("permissionDenials", reply.path("permission_denials").size());
      out.setValue("sessionId", reply.path("session_id").asText(null));
      boolean ok = process.exitValue() == 0 && !reply.path("is_error").asBoolean()
          && "success".equals(reply.path("subtype").asText());
      out.setValue("successful", ok);
      if (!ok) {
        out.setValue("error", "Claude ended with " + reply.path("subtype").asText() + ": "
            + reply.path("result").asText("") + " "
            + tail(stderr.toString(StandardCharsets.UTF_8)));
      }
      return result;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new ContextException("Review interrupted", ex);
    } catch (Exception ex) {
      out.setValue("error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
      return result;
    } finally {
      if (process != null && process.isAlive()) {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
      }
    }
  }

  /**
   * Fills the token counts from the per-model usage, which covers every model the session
   * called, and names those models by their exact ids, the costliest first. Falls back to the
   * session-level usage when the per-model breakdown is missing.
   */
  private static void usage(JsonNode reply, DataRecord out) {
    JsonNode models = reply.path("modelUsage");
    if (models.size() == 0) {
      JsonNode usage = reply.path("usage");
      out.setValue("inputTokens", usage.path("input_tokens").asLong());
      out.setValue("outputTokens", usage.path("output_tokens").asLong());
      out.setValue("cacheReadTokens", usage.path("cache_read_input_tokens").asLong());
      out.setValue("cacheCreationTokens", usage.path("cache_creation_input_tokens").asLong());
      out.setValue("thinkingTokens",
          usage.path("output_tokens_details").path("thinking_tokens").asLong());
      return;
    }
    long input = 0;
    long output = 0;
    long cacheRead = 0;
    long cacheCreation = 0;
    long thinking = 0;
    List<Map.Entry<String, JsonNode>> byCost = new ArrayList<>();
    models.fields().forEachRemaining(byCost::add);
    byCost.sort(Comparator.comparingDouble(
        (Map.Entry<String, JsonNode> model) -> model.getValue().path("costUSD").asDouble())
        .reversed());
    List<String> ids = new ArrayList<>();
    for (Map.Entry<String, JsonNode> model : byCost) {
      JsonNode use = model.getValue();
      ids.add(model.getKey());
      input += use.path("inputTokens").asLong();
      output += use.path("outputTokens").asLong();
      cacheRead += use.path("cacheReadInputTokens").asLong();
      cacheCreation += use.path("cacheCreationInputTokens").asLong();
      thinking += use.path("thinkingTokens").asLong();
    }
    out.setValue("inputTokens", input);
    out.setValue("outputTokens", output);
    out.setValue("cacheReadTokens", cacheRead);
    out.setValue("cacheCreationTokens", cacheCreation);
    out.setValue("thinkingTokens", thinking);
    out.setValue("modelId", String.join(", ", ids));
  }

  private static Thread drain(InputStream source, ByteArrayOutputStream target) {
    Thread thread = new Thread(() -> {
      try (InputStream stream = source) {
        stream.transferTo(target);
      } catch (Exception ignored) {
        // The process is gone; whatever was read so far is kept.
      }
    }, "pr-review-drain");
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  private static JsonNode lastJson(String stdout) {
    List<String> lines = stdout.lines().toList();
    for (int i = lines.size() - 1; i >= 0; i--) {
      String line = lines.get(i).trim();
      if (line.startsWith("{")) {
        try {
          return new ObjectMapper().readTree(line);
        } catch (Exception ignored) {
          return null;
        }
      }
    }
    return null;
  }

  private static String tail(String text) {
    return text.length() > MAX_STDERR ? text.substring(text.length() - MAX_STDERR) : text;
  }
}
