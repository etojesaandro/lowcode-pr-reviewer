import com.tibbo.aggregate.common.context.*;
import com.tibbo.aggregate.common.datatable.*;
import com.tibbo.aggregate.common.server.*;

import com.tibbo.linkserver.*;
import com.tibbo.linkserver.context.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Lists open pull requests of the configured Bitbucket Cloud repository, following pagination.
 *
 * @author Alexander Sidorov
 *     <p>(co-authored with Claude Opus 5.5)
 */
public class %ScriptClassNamePattern% implements FunctionImplementation {
  private static final String BITBUCKET = "users.admin.devices.bitbucket";
  private static final String API = "https://api.bitbucket.org";
  private static final int MAX_PAGES = 40;

  public DataTable execute(Context con, FunctionDefinition def, CallerController caller,
      RequestController request, DataTable parameters) throws ContextException {
    DataRecord cfg = con.getVariable("config", caller).rec();
    String next = "/2.0/repositories/" + cfg.getString("workspace") + "/" + cfg.getString("repo")
        + "/pullrequests?state=OPEN&pagelen=50&fields=next,values.id,values.title,"
        + "values.source.branch.name,values.source.commit.hash,"
        + "values.destination.branch.name,values.links.html.href";
    DataTable result = new SimpleDataTable(def.getOutputFormat());
    ObjectMapper json = new ObjectMapper();
    int pages = 0;
    while (next != null) {
      if (++pages > MAX_PAGES) {
        throw new ContextException("Bitbucket pagination exceeded " + MAX_PAGES + " pages");
      }
      DataRecord reply = con.callFunction("request", caller, BITBUCKET, "GET", next, null).rec();
      if (reply.getInt("replyCode") != 200) {
        throw new ContextException("Bitbucket replied " + reply.getInt("replyCode") + ": "
            + reply.getString("reply"));
      }
      JsonNode root;
      try {
        root = json.readTree(reply.getString("reply"));
      } catch (Exception ex) {
        throw new ContextException("Unparseable Bitbucket reply: " + ex.getMessage(), ex);
      }
      for (JsonNode pr : root.path("values")) {
        result.addRecord(pr.path("id").asInt(), pr.path("title").asText(),
            pr.path("source").path("branch").path("name").asText(),
            pr.path("destination").path("branch").path("name").asText(),
            pr.path("source").path("commit").path("hash").asText(),
            pr.path("links").path("html").path("href").asText());
      }
      JsonNode link = root.path("next");
      next = link.isTextual() ? link.asText().replace(API, "") : null;
    }
    return result;
  }
}
