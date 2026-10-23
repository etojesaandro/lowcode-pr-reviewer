import com.tibbo.aggregate.common.context.*;
import com.tibbo.aggregate.common.datatable.*;
import com.tibbo.aggregate.common.server.*;

import com.tibbo.linkserver.*;
import com.tibbo.linkserver.context.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Posts a Markdown text as a general comment on a Bitbucket pull request.
 *
 * @author Alexander Sidorov
 *     <p>(co-authored with Claude Opus 5.5)
 */
public class %ScriptClassNamePattern% implements FunctionImplementation {
  private static final String BITBUCKET = "users.admin.devices.bitbucket";

  public DataTable execute(Context con, FunctionDefinition def, CallerController caller,
      RequestController request, DataTable parameters) throws ContextException {
    DataRecord in = parameters.rec();
    String text = in.getString("text");
    if (text == null || text.isBlank()) {
      throw new ContextException("Refusing to post an empty comment");
    }
    DataRecord cfg = con.getVariable("config", caller).rec();
    ObjectMapper json = new ObjectMapper();
    ObjectNode body = json.createObjectNode();
    body.putObject("content").put("raw", text);
    DataRecord reply = con.callFunction("request", caller, BITBUCKET, "POST",
        "/2.0/repositories/" + cfg.getString("workspace") + "/" + cfg.getString("repo")
            + "/pullrequests/" + in.getInt("prId") + "/comments", body.toString()).rec();
    if (reply.getInt("replyCode") != 201) {
      throw new ContextException("Bitbucket replied " + reply.getInt("replyCode") + ": "
          + reply.getString("reply"));
    }
    try {
      return new SimpleDataTable(def.getOutputFormat(),
          json.readTree(reply.getString("reply")).path("id").asText());
    } catch (Exception ex) {
      throw new ContextException("Unparseable Bitbucket reply: " + ex.getMessage(), ex);
    }
  }
}
