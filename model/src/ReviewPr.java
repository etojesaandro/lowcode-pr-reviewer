import com.tibbo.aggregate.common.context.*;
import com.tibbo.aggregate.common.datatable.*;
import com.tibbo.aggregate.common.server.*;

import com.tibbo.linkserver.*;
import com.tibbo.linkserver.context.*;

import java.util.Date;

/**
 * Reviews one open pull request on request, whatever its Jira issue type and size, and returns
 * its ledger row. The review runs inside poll, so the ledger keeps a single writer; a commit that
 * was already reviewed is refused unless force is set.
 *
 * @author Alexander Sidorov
 *     <p>(co-authored with Claude Opus 5.5)
 */
public class %ScriptClassNamePattern% implements FunctionImplementation {
  public DataTable execute(Context con, FunctionDefinition def, CallerController caller,
      RequestController request, DataTable parameters) throws ContextException {
    DataRecord in = parameters.rec();
    int id = in.getInt("prId");
    if (id <= 0) {
      throw new ContextException("Not a pull request id: " + id);
    }
    con.callFunction("poll", caller, id, Boolean.TRUE.equals(in.getBoolean("force")));
    DataRecord latest = null;
    for (DataRecord row : con.getVariable("ledger", caller)) {
      Date started = row.getDate("startedAt");
      if (row.getInt("prId") == id && started != null
          && (latest == null || started.after(latest.getDate("startedAt")))) {
        latest = row;
      }
    }
    if (latest == null) {
      throw new ContextException("No review of PR " + id + " was recorded");
    }
    return new SimpleDataTable(def.getOutputFormat(), id, latest.getString("commit"),
        latest.getString("status"), latest.getString("commentId"), latest.getString("error"));
  }
}
