import com.tibbo.aggregate.common.context.*;
import com.tibbo.aggregate.common.datatable.*;
import com.tibbo.aggregate.common.server.*;

import com.tibbo.linkserver.*;
import com.tibbo.linkserver.context.*;

import java.util.Date;

/**
 * Totals of the reviews of a period: count, failures, tokens, cost, and cost per 100 changed
 * lines over the reviews whose PR size is known.
 *
 * @author Alexander Sidorov
 *     <p>(co-authored with Claude Opus 5.5)
 */
public class %ScriptClassNamePattern% implements FunctionImplementation {
  public DataTable execute(Context con, FunctionDefinition def, CallerController caller,
      RequestController request, DataTable parameters) throws ContextException {
    DataRecord in = parameters.rec();
    Date from = in.getDate("fromDate");
    Date to = in.getDate("toDate");
    int reviews = 0;
    int failed = 0;
    long tokens = 0;
    long output = 0;
    long lines = 0;
    double cost = 0;
    double sizedCost = 0;
    for (DataRecord review : con.callFunction("usageHistory", caller, from, to)) {
      reviews++;
      if ("FAILED".equals(review.getString("status"))) {
        failed++;
      }
      tokens += review.getLong("totalTokens");
      output += review.getLong("outputTokens");
      cost += review.getDouble("costUsd");
      if (review.getInt("linesChanged") > 0) {
        lines += review.getInt("linesChanged");
        sizedCost += review.getDouble("costUsd");
      }
    }
    return new SimpleDataTable(def.getOutputFormat(), reviews, failed, tokens, output, cost,
        reviews == 0 ? 0.0 : cost / reviews, lines, lines == 0 ? 0.0 : sizedCost * 100 / lines);
  }
}
