import com.tibbo.aggregate.common.context.*;
import com.tibbo.aggregate.common.datatable.*;
import com.tibbo.aggregate.common.server.*;

import com.tibbo.linkserver.*;
import com.tibbo.linkserver.context.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Sends a JSON request through an HTTP device. Unlike httpExecuteRequest alone, it keeps the
 * device's own headers and sends its username/password preemptively as Basic auth, since Jira
 * and Bitbucket Cloud answer 404 rather than 401 to anonymous requests.
 *
 * @author Alexander Sidorov
 *     <p>(co-authored with Claude Opus 5.5)
 */
public class %ScriptClassNamePattern% implements FunctionImplementation {
  private static final String HEADER_NAME = "headerName";
  private static final String HEADER_VALUE = "headerValue";

  public DataTable execute(Context con, FunctionDefinition def, CallerController caller,
      RequestController request, DataTable parameters) throws ContextException {
    DataRecord in = parameters.rec();
    String path = in.getString("device");
    String method = in.getString("method");
    String body = in.getString("body");
    Context device = con.getContextManager().get(path, caller);
    if (device == null) {
      throw new ContextException("HTTP device not found: " + path);
    }
    DataRecord props = device.getVariable("connectionProperties", caller).rec();
    DataTable stored = props.getDataTable("additionalHeaders");
    DataTable headers = stored.clone();
    boolean basic = Boolean.TRUE.equals(props.getBoolean("enableAuthentication"))
        && props.getString("userName") != null && !props.getString("userName").isEmpty();
    for (int i = headers.getRecordCount() - 1; i >= 0; i--) {
      String name = headers.getRecord(i).getString(HEADER_NAME);
      if ("Content-Type".equalsIgnoreCase(name) || "Accept".equalsIgnoreCase(name)
          || (basic && "Authorization".equalsIgnoreCase(name))) {
        headers.removeRecord(i);
      }
    }
    headers.addRecord().setValue(HEADER_NAME, "Content-Type")
        .setValue(HEADER_VALUE, "application/json");
    headers.addRecord().setValue(HEADER_NAME, "Accept").setValue(HEADER_VALUE, "application/json");
    if (basic) {
      String pair = props.getString("userName") + ":" + props.getString("password");
      headers.addRecord().setValue(HEADER_NAME, "Authorization").setValue(HEADER_VALUE,
          "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8)));
    }
    DataRecord reply = device.callFunction("httpExecuteRequest", caller, in.getString("url"),
        method, "POST".equals(method) ? body : null, headers, "UTF-8",
        "PUT".equals(method) ? body : null).rec();
    if (!Boolean.TRUE.equals(reply.getBoolean("successful"))) {
      throw new ContextException(path + " " + method + " " + in.getString("url") + " failed: "
          + reply.getString("error"));
    }
    return new SimpleDataTable(def.getOutputFormat(), reply.getInt("replyCode"),
        reply.getString("reply"));
  }
}
