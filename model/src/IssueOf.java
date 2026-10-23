import com.tibbo.aggregate.common.context.*;
import com.tibbo.aggregate.common.datatable.*;
import com.tibbo.aggregate.common.server.*;

import com.tibbo.linkserver.*;
import com.tibbo.linkserver.context.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Looks up a Jira issue's type and summary; an unknown or invisible key yields found=false.
 *
 * @author Alexander Sidorov
 *     <p>(co-authored with Claude Opus 5.5)
 */
public class %ScriptClassNamePattern% implements FunctionImplementation {
  private static final String JIRA = "users.admin.devices.jira";

  public DataTable execute(Context con, FunctionDefinition def, CallerController caller,
      RequestController request, DataTable parameters) throws ContextException {
    String key = parameters.rec().getString("key");
    if (key == null || !key.matches("[A-Z][A-Z0-9]+-[0-9]+")) {
      throw new ContextException("Not a Jira key: " + key);
    }
    DataRecord reply = con.callFunction("request", caller, JIRA, "GET",
        "/rest/api/3/issue/" + key + "?fields=issuetype,summary", null).rec();
    int code = reply.getInt("replyCode");
    if (code == 404) {
      return new SimpleDataTable(def.getOutputFormat(), false, null, null);
    }
    if (code != 200) {
      throw new ContextException("Jira replied " + code + " for " + key + ": "
          + reply.getString("reply"));
    }
    try {
      JsonNode fields = new ObjectMapper().readTree(reply.getString("reply")).path("fields");
      return new SimpleDataTable(def.getOutputFormat(), true,
          fields.path("issuetype").path("name").asText(), fields.path("summary").asText());
    } catch (Exception ex) {
      throw new ContextException("Unparseable Jira reply for " + key + ": " + ex.getMessage(), ex);
    }
  }
}
