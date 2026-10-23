import com.tibbo.aggregate.common.context.*;
import com.tibbo.aggregate.common.datatable.*;
import com.tibbo.aggregate.common.server.*;

import com.tibbo.linkserver.*;
import com.tibbo.linkserver.context.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sums the review usage of a period per server-local day, as chart rows (series, category,
 * value). Metric "cost" gives one series in USD; "tokens" gives one series per token kind.
 * Days without reviews are present with zeros, so the bars keep an even time scale.
 *
 * @author Alexander Sidorov
 *     <p>(co-authored with Claude Opus 5.5)
 */
public class %ScriptClassNamePattern% implements FunctionImplementation {
  private static final int MAX_DAYS = 400;
  private static final String[][] TOKENS = {
    {"inputTokens", "Input"},
    {"outputTokens", "Output"},
    {"cacheReadTokens", "Cache read"},
    {"cacheCreationTokens", "Cache write"}
  };

  public DataTable execute(Context con, FunctionDefinition def, CallerController caller,
      RequestController request, DataTable parameters) throws ContextException {
    DataRecord in = parameters.rec();
    Date from = in.getDate("fromDate");
    Date to = in.getDate("toDate");
    String metric = in.getString("metric");
    boolean cost = "cost".equals(metric);
    if (!cost && !"tokens".equals(metric)) {
      throw new ContextException("Metric must be cost or tokens, not " + metric);
    }
    ZoneId zone = ZoneId.systemDefault();
    LocalDate first = from.toInstant().atZone(zone).toLocalDate();
    LocalDate last = to.toInstant().atZone(zone).toLocalDate();
    if (first.plusDays(MAX_DAYS).isBefore(last)) {
      throw new ContextException("Period longer than " + MAX_DAYS + " days");
    }
    Map<LocalDate, double[]> days = new LinkedHashMap<>();
    for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
      days.put(day, new double[TOKENS.length + 1]);
    }
    for (DataRecord review : con.callFunction("usageHistory", caller, from, to)) {
      double[] sums =
          days.get(review.getDate("time").toInstant().atZone(zone).toLocalDate());
      if (sums == null) {
        continue;
      }
      sums[TOKENS.length] += review.getDouble("costUsd");
      for (int i = 0; i < TOKENS.length; i++) {
        sums[i] += review.getLong(TOKENS[i][0]);
      }
    }
    DataTable result = new SimpleDataTable(def.getOutputFormat());
    for (Map.Entry<LocalDate, double[]> day : days.entrySet()) {
      String category = day.getKey().toString();
      if (cost) {
        result.addRecord("Cost, USD", category, day.getValue()[TOKENS.length], null);
      } else {
        for (int i = 0; i < TOKENS.length; i++) {
          result.addRecord(TOKENS[i][1], category, day.getValue()[i], null);
        }
      }
    }
    return result;
  }
}
