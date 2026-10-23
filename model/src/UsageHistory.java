import com.tibbo.aggregate.common.context.*;
import com.tibbo.aggregate.common.datatable.*;
import com.tibbo.aggregate.common.server.*;

import com.tibbo.linkserver.*;
import com.tibbo.linkserver.context.*;

import java.util.Date;

/**
 * Lists the reviews of a period, oldest first, with their token usage, cost and PR size, as
 * recorded by the reviewCompleted event history. Fields that older events lack read as zero or
 * empty.
 *
 * @author Alexander Sidorov
 *     <p>(co-authored with Claude Opus 5.5)
 */
public class %ScriptClassNamePattern% implements FunctionImplementation {
  private static final int MAX_EVENTS = 100000;

  public DataTable execute(Context con, FunctionDefinition def, CallerController caller,
      RequestController request, DataTable parameters) throws ContextException {
    DataRecord in = parameters.rec();
    Date from = in.getDate("fromDate");
    Date to = in.getDate("toDate");
    if (from == null || to == null || from.after(to)) {
      throw new ContextException("Expected a period with fromDate not after toDate");
    }
    Context events = con.getContextManager().get("events", caller);
    DataTable query =
        new SimpleDataTable(events.getFunctionDefinition("get", caller).getInputFormat());
    query.addRecord().setValue("mask", con.getPath()).setValue("event", "reviewCompleted")
        .setValue("fromDate", from).setValue("toDate", to).setValue("dataAsTable", false)
        .setValue("sortField", "creationtime").setValue("sortOrder", true)
        .setValue("limit", MAX_EVENTS);
    DataTable result = new SimpleDataTable(def.getOutputFormat());
    for (DataRecord review : events.callFunction("get", caller, query)) {
      long input = count(review, "inputTokens");
      long output = count(review, "outputTokens");
      long cacheRead = count(review, "cacheReadTokens");
      long cacheCreation = count(review, "cacheCreationTokens");
      long lines = count(review, "linesChanged");
      double cost = review.getDouble("costUsd");
      result.addRecord(review.getDate("eCreationtime"), review.getInt("prId"),
          text(review, "jiraKey"), text(review, "status"), flag(review, "changesRequested"),
          text(review, "model"),
          text(review, "modelId"), text(review, "effort"), (int) lines,
          (int) count(review, "filesChanged"), input, output, count(review, "thinkingTokens"),
          cacheRead, cacheCreation, input + output + cacheRead + cacheCreation, cost,
          lines == 0 ? 0.0 : cost * 100 / lines, (int) count(review, "numTurns"),
          count(review, "durationMs") / 1000.0);
    }
    return result;
  }

  private static long count(DataRecord review, String field) {
    if (!review.getFormat().hasField(field)) {
      return 0;
    }
    Object value = review.getValue(field);
    return value instanceof Number ? ((Number) value).longValue() : 0;
  }

  private static boolean flag(DataRecord review, String field) {
    return review.getFormat().hasField(field) && Boolean.TRUE.equals(review.getValue(field));
  }

  private static String text(DataRecord review, String field) {
    return review.getFormat().hasField(field) ? review.getString(field) : null;
  }
}
