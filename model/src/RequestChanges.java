import com.tibbo.aggregate.common.context.*;
import com.tibbo.aggregate.common.datatable.*;
import com.tibbo.aggregate.common.server.*;

import com.tibbo.linkserver.*;
import com.tibbo.linkserver.context.*;

/**
 * Marks a Bitbucket pull request as "Changes requested" on behalf of the account whose
 * credentials the Bitbucket device holds.
 *
 * @author Alexander Sidorov
 *     <p>(co-authored with Claude Opus 5.5)
 */
public class %ScriptClassNamePattern% implements FunctionImplementation {
  private static final String BITBUCKET = "users.admin.devices.bitbucket";

  public DataTable execute(Context con, FunctionDefinition def, CallerController caller,
      RequestController request, DataTable parameters) throws ContextException {
    int id = parameters.rec().getInt("prId");
    DataRecord cfg = con.getVariable("config", caller).rec();
    DataRecord reply = con.callFunction("request", caller, BITBUCKET, "POST",
        "/2.0/repositories/" + cfg.getString("workspace") + "/" + cfg.getString("repo")
            + "/pullrequests/" + id + "/request-changes", "{}").rec();
    int code = reply.getInt("replyCode");
    if (code != 200 && code != 201) {
      throw new ContextException("Bitbucket replied " + code + " to request-changes on PR " + id
          + ": " + reply.getString("reply"));
    }
    DataTable result = new SimpleDataTable(def.getOutputFormat(), true);
    result.rec().setValue("requested", true);
    return result;
  }
}
