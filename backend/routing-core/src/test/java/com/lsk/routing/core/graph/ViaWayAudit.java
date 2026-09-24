package com.lsk.routing.core.graph;

import com.lsk.routing.core.osm.CarDataAnalysis;
import java.nio.file.*;
import java.util.*;

/** Offline audit: isolated relation compilation, persistence, transitions and route legality. */
public final class ViaWayAudit {
    record Checks(int transitions, int routes) {}

    static boolean allowed(List<Integer> history, int edge, RoutingGraph.SequenceRestriction rule) {
        var required = new HashSet<Integer>();
        for (var path : rule.paths()) {
            if (rule.only()) {
                for (int n = 1; n < path.size(); n++)
                    if (endsWith(history, path.subList(0, n))) required.add(path.get(n));
            } else if (edge == path.getLast() && endsWith(history, path.subList(0, path.size() - 1))) return false;
        }
        return required.isEmpty() || required.contains(edge);
    }
    private static boolean endsWith(List<Integer> history, List<Integer> prefix) {
        return history.size() >= prefix.size()
                && history.subList(history.size() - prefix.size(), history.size()).equals(prefix);
    }
    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
    static Checks verify(RoutingGraph graph) {
        require(graph.sequenceRestrictions().size() == 1, "Expected exactly one relation");
        var rule = graph.sequenceRestrictions().getFirst();
        int transitions = 0, routes = 0;
        int[] sources = new int[graph.edgeCount()];
        for (int node = 0; node < graph.nodeCount(); node++)
            for (int e = graph.edgeStart(node); e < graph.edgeEnd(node); e++) sources[e] = node;
        var queries = new TreeSet<Long>();
        for (var path : rule.paths()) {
            // Include starts within via: no from history may be invented.
            for (int begin = 0; begin < path.size(); begin++) {
                int state = 0;
                var history = new ArrayList<Integer>();
                for (int i = begin; i < path.size(); i++) {
                    int edge = path.get(i);
                    boolean expected = allowed(history, edge, rule);
                    int next = graph.restrictionState(state, edge);
                    require((next >= 0) == expected, "Sequence transition mismatch");
                    transitions++;
                    if (begin == 0) require(expected == (rule.only() || i < path.size() - 1), "Wrong no/only semantics");
                    if (!expected) break;
                    state = next; history.add(edge);
                    int node = graph.target(edge);
                    for (int out = graph.edgeStart(node); out < graph.edgeEnd(node); out++) {
                        require((graph.restrictionState(state, out) >= 0) == allowed(history, out, rule), "Exit transition mismatch");
                        transitions++;
                    }
                }
                int start = sources[path.get(begin)], end = graph.target(path.getLast());
                queries.add(((long) start << 32) | (end & 0xffffffffL));
            }
        }
        for (long query : queries) {
            int start = (int) (query >>> 32), end = (int) query;
            var result = new DijkstraRouter(graph).route(start, end);
            var expected = referenceDistance(graph, start, end);
            require(result.isPresent() == Double.isFinite(expected), "Reachability mismatch");
            if (result.isPresent()) {
                require(Math.abs(result.get().distanceMetres() - expected) < 1e-6, "Shortest distance mismatch");
                var history = new ArrayList<Integer>();
                for (int edge : result.get().edges()) {
                    require(allowed(history, edge, rule), "Router returned forbidden history");
                    history.add(edge);
                }
            }
            routes++;
        }
        return new Checks(transitions, routes);
    }
    // Independent history-suffix Dijkstra, without the production prefix automaton.
    private record State(int node, List<Integer> history) {}
    private record Label(State state, double distance) {}
    static double referenceDistance(RoutingGraph graph, int start, int end) {
        var rules = graph.sequenceRestrictions();
        int keep = Math.max(1, rules.stream().flatMap(r -> r.paths().stream()).mapToInt(List::size).max().orElse(2) - 1);
        var initial = new State(start, List.of());
        var best = new HashMap<State, Double>(); best.put(initial, 0.0);
        var queue = new PriorityQueue<Label>(Comparator.comparingDouble(Label::distance));
        queue.add(new Label(initial, 0));
        while (!queue.isEmpty()) {
            var current = queue.remove();
            if (current.distance != best.get(current.state)) continue;
            if (current.state.node == end) return current.distance;
            for (int edge = graph.edgeStart(current.state.node); edge < graph.edgeEnd(current.state.node); edge++) {
                var history = current.state.history;
                if (!history.isEmpty() && !graph.turnAllowed(history.getLast(), edge)) continue;
                boolean legal = true;
                for (var rule : rules) if (!allowed(history, edge, rule)) { legal = false; break; }
                if (!legal) continue;
                var nextHistory = new ArrayList<>(history); nextHistory.add(edge);
                if (nextHistory.size() > keep) nextHistory.removeFirst();
                var next = new State(graph.target(edge), List.copyOf(nextHistory));
                double distance = current.distance + graph.distanceMetres(edge);
                if (distance >= best.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;
                best.put(next, distance); queue.add(new Label(next, distance));
                if (best.size() > 200_000) throw new IllegalStateException("Audit oracle state limit exceeded");
            }
        }
        return Double.POSITIVE_INFINITY;
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected cases directory and report path");
        var rows = new ArrayList<String>();
        rows.add("relation\tstatus\tnodes\tedges\talternatives\ttransitions\troutes\tdetail");
        var totals = new TreeMap<String, Integer>();
        try (var files = Files.list(Path.of(args[0]))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".osm.pbf")).sorted().toList()) {
                String id = file.getFileName().toString().replace(".osm.pbf", "");
                String status = "ANALYSIS_FAILED", detail = "", metrics = "0\t0\t0\t0\t0";
                try {
                    var analysis = new CarDataAnalysis().read(file);
                    if (analysis.connectedViaWay() != 1 || analysis.restrictions() != 1) {
                        status = "INELIGIBLE"; detail = analysis.issues().toString();
                    } else {
                        status = "BUILD_FAILED";
                        var graph = new GraphBuilder().build(file);
                        status = "CHECK_FAILED";
                        Path saved = Files.createTempFile("via-audit-", ".rgraph");
                        try {
                            Files.delete(saved); graph.write(saved);
                            var loaded = RoutingGraph.read(saved);
                            require(graph.sequenceRestrictions().equals(loaded.sequenceRestrictions()), "Persistence mismatch");
                            var checks = verify(loaded);
                            metrics = graph.nodeCount()+"\t"+graph.edgeCount()+"\t"+graph.sequenceRestrictions().getFirst().paths().size()+"\t"+checks.transitions+"\t"+checks.routes;
                            status = "PASS";
                        } finally { Files.deleteIfExists(saved); }
                    }
                } catch (Exception e) { detail = e.getClass().getSimpleName()+": "+e.getMessage(); }
                totals.merge(status, 1, Integer::sum);
                rows.add(id+"\t"+status+"\t"+metrics+"\t"+detail.replace('\t',' ').replace('\n',' '));
            }
        }
        Files.write(Path.of(args[1]), rows);
        System.out.println("Via-way audit: "+totals);
        if (totals.keySet().stream().anyMatch(s -> s.endsWith("FAILED")))
            throw new IllegalStateException("Audit failures; inspect report");
    }
}
