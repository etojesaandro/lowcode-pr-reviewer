import com.tibbo.aggregate.common.context.*;
import com.tibbo.aggregate.common.datatable.*;
import com.tibbo.aggregate.common.server.*;

import com.tibbo.linkserver.*;
import com.tibbo.linkserver.context.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.regex.Pattern;

/**
 * Measures a pull request from its Bitbucket diffstat: changed lines (added plus removed) and
 * changed files, leaving out the files that match the configured exclusion pattern, such as
 * lock files and generated sources.
 *
 * @author Alexander Sidorov
 *     <p>(co-authored with Claude Opus 5.5)
 */
public class %ScriptClassNamePattern% implements FunctionImplementation {
  private static final String BITBUCKET = "users.admin.devices.bitbucket";
  private static final String API = "https://api.bitbucket.org";
  private static final int MAX_PAGES = 20;

  public DataTable execute(Context con, FunctionDefinition def, CallerController caller,
      RequestController request, DataTable parameters) throws ContextException {
    int id = parameters.rec().getInt("prId");
    DataRecord cfg = con.getVariable("config", caller).rec();
    String exclude = cfg.getString("sizeExcludeRegex");
    Pattern excluded = Pattern.compile(exclude == null || exclude.isEmpty() ? "(?!)" : exclude);
    String next = "/2.0/repositories/" + cfg.getString("workspace") + "/" + cfg.getString("repo")
        + "/pullrequests/" + id + "/diffstat?pagelen=500&fields=next,values.lines_added,"
        + "values.lines_removed,values.old.path,values.new.path";
    ObjectMapper json = new ObjectMapper();
    int lines = 0;
    int files = 0;
    int skipped = 0;
    int pages = 0;
    while (next != null) {
      if (++pages > MAX_PAGES) {
        throw new ContextException("Diffstat of PR " + id + " exceeded " + MAX_PAGES + " pages");
      }
      DataRecord reply = con.callFunction("request", caller, BITBUCKET, "GET", next, null).rec();
      if (reply.getInt("replyCode") != 200) {
        throw new ContextException("Bitbucket replied " + reply.getInt("replyCode")
            + " for the diffstat of PR " + id + ": " + reply.getString("reply"));
      }
      JsonNode root;
      try {
        root = json.readTree(reply.getString("reply"));
      } catch (Exception ex) {
        throw new ContextException("Unparseable diffstat of PR " + id + ": " + ex.getMessage(), ex);
      }
      for (JsonNode file : root.path("values")) {
        String path = file.path("new").path("path").asText(file.path("old").path("path").asText());
        if (excluded.matcher(path).find()) {
          skipped++;
          continue;
        }
        files++;
        lines += file.path("lines_added").asInt() + file.path("lines_removed").asInt();
      }
      JsonNode link = root.path("next");
      next = link.isTextual() ? link.asText().replace(API, "") : null;
    }
    return new SimpleDataTable(def.getOutputFormat(), lines, files, skipped);
  }
}
