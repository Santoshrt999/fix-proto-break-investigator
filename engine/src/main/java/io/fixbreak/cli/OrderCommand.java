package io.fixbreak.cli;

import io.fixbreak.cli.Args.UsageException;
import io.fixbreak.dict.FixDictionary;
import io.fixbreak.engine.EvidenceResolver;
import io.fixbreak.engine.ScanConfig;
import io.fixbreak.engine.ScanEngine;
import io.fixbreak.order.OrderState;
import io.fixbreak.rules.Describe;
import io.fixbreak.rules.Finding;
import io.fixbreak.rules.Rules;
import io.fixbreak.util.Times;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code fixscan order}: the full lifecycle of one order, including its amend/cancel chain. */
final class OrderCommand {
    private static final int MAX_MATCHES = 5;

    static Map<String, Object> run(Cli cli) throws IOException {
        if (cli.args.positional.isEmpty()) throw new UsageException("Usage: fixscan order <ClOrdID|OrderID> <files...>");
        String id = cli.args.positional.get(0);
        List<Path> files = cli.files(1);
        ScanConfig config = cli.config(false);
        int limit = Math.max(2, cli.args.getInt("limit", 200));
        ScanEngine engine = new ScanEngine(config, Rules.defaults());
        engine.scan(files);

        Map<String, Object> out = cli.result("order");
        out.put("query", id);
        List<OrderState> matches = engine.book.find(id);
        out.put("matches", matches.size());
        if (matches.isEmpty()) {
            out.put("hint", "No order with that ClOrdID(11) or OrderID(37) in these files. Check the id and the log's time range "
                    + "(fixscan stats), or search other tags with: fixscan grep 11=" + id + " <files>");
            return out;
        }
        Render render = new Render(files, config);
        List<Object> orders = new ArrayList<>();
        for (OrderState o : matches.subList(0, Math.min(MAX_MATCHES, matches.size()))) {
            orders.add(order(o, engine, render, config, limit));
        }
        out.put("orders", orders);
        return out;
    }

    private static Map<String, Object> order(OrderState o, ScanEngine engine, Render render, ScanConfig config, int limit)
            throws IOException {
        FixDictionary dict = config.dict;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("session", o.session.name);
        m.put("clOrdIds", o.chain());
        m.put("currentClOrdId", o.currentClOrdId);
        m.put("orderId", o.orderId);
        m.put("terms", Describe.terms(o, dict));
        m.put("status", o.status == 0 ? "no ExecutionReport seen" : dict.describe(39, String.valueOf(o.status)));
        m.put("orderQty", num(o.orderQty));
        m.put("cumQty", num(o.cumQty));
        m.put("leavesQty", num(o.leavesQty));
        m.put("avgPx", num(o.avgPx));
        m.put("sumOfFills", num(o.sumLastQty));
        m.put("newOrderSeen", o.seenNew());
        if (o.placeholder) m.put("note", "The log never shows a message with this ClOrdID(11); it is only referenced as OrigClOrdID(41).");
        else if (!o.seenNew()) m.put("note", "No NewOrderSingle for this order in these files; the log may start after it was sent.");
        if (o.rejectedBeforeAck) m.put("rejectedBeforeAck", true);
        if (o.newTime != Times.UNKNOWN && o.firstExecTime != Times.UNKNOWN) m.put("ackLatencyMs", o.firstExecTime - o.newTime);
        if (!o.pending().isEmpty()) {
            List<Object> pending = new ArrayList<>();
            for (OrderState.PendingRequest p : o.pending()) {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("clOrdId", p.clOrdId);
                pm.put("type", p.type == 'F' ? "OrderCancelRequest" : "OrderCancelReplaceRequest");
                pm.put("at", render.at(p.ref));
                pm.put("time", Times.iso(p.time));
                pending.add(pm);
            }
            m.put("unansweredRequests", pending);
        }

        long[] refs = o.refs();
        List<Long> wanted = new ArrayList<>();
        int head = refs.length <= limit ? refs.length : limit / 2;
        for (int i = 0; i < head; i++) wanted.add(refs[i]);
        for (int i = Math.max(head, refs.length - (limit - head)); i < refs.length; i++) wanted.add(refs[i]);
        render.resolver.load(wanted);
        List<Object> timeline = new ArrayList<>();
        for (long ref : wanted) {
            EvidenceResolver.Line line = render.resolver.get(ref);
            if (line != null) timeline.add(render.timelineEntry(line));
        }
        m.put("timeline", timeline);
        if (refs.length > wanted.size()) {
            m.put("timelineOmitted", refs.length - wanted.size());
            m.put("timelineNote", "Showing the first " + head + " and last " + (wanted.size() - head) + " messages; raise --limit for all.");
        }

        List<Object> findings = new ArrayList<>();
        for (Finding f : engine.findings.sorted()) {
            if (o.session.name.equals(f.session) && o.rootClOrdId().equals(f.rootClOrdId())) findings.add(render.finding(f, false));
        }
        m.put("findings", findings);
        return m;
    }

    private static Object num(double d) {
        return Double.isNaN(d) ? null : (Object) (d == Math.rint(d) ? (Object) (long) d : (Object) d);
    }
}
