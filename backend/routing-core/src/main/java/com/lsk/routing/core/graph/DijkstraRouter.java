package com.lsk.routing.core.graph;

import java.util.*;
import java.time.Instant;
import java.util.function.IntPredicate;

/** Distance routing with one state per incoming edge. Query state is never shared. */
public final class DijkstraRouter {
    public record Route(double distanceMetres, List<Integer> nodes, List<Integer> edges) {
        public Route { nodes = List.copyOf(nodes); edges = List.copyOf(edges); }
    }
    private record Label(int edge, double distance) {}
    private final RoutingGraph graph;

    public DijkstraRouter(RoutingGraph graph) { this.graph = Objects.requireNonNull(graph);
 }

    /** Node arguments are graph indices, not OSM IDs. Empty means no legal directed path. */
    public Optional<Route> route(int start, int end) { return route(start,end,EdgeAvailability.staticOnly(graph)); }
    public Optional<Route> route(int start,int end,Instant snapshotAt) { return route(start,end,EdgeAvailability.at(graph,snapshotAt)); }
    private Optional<Route> route(int start,int end,IntPredicate allowed) {
        Objects.checkIndex(start, graph.nodeCount());
        Objects.checkIndex(end, graph.nodeCount());
        if (start == end) return Optional.of(new Route(0, List.of(start), List.of()));
        if(!graph.sequenceRestrictions().isEmpty()) {
            var seeds=new ArrayList<HistorySearch.Seed>();
            for(int edge=graph.edgeStart(start);edge<graph.edgeEnd(start);edge++)if(allowed.test(edge))seeds.add(new HistorySearch.Seed(edge,graph.distanceMetres(edge)));
            return HistorySearch.route(graph,seeds,end,Map.of(),Double.POSITIVE_INFINITY,allowed).map(path->{
                var nodes=new ArrayList<Integer>();nodes.add(start);
                for(int edge:path.edges())nodes.add(graph.target(edge));
                return new Route(path.distance(),nodes,path.edges());
            });
        }
        double[] best = new double[graph.edgeCount()];
        int[] previous = new int[graph.edgeCount()];
        Arrays.fill(best, Double.POSITIVE_INFINITY);
        Arrays.fill(previous, -1);
        var queue = new PriorityQueue<Label>(Comparator.comparingDouble(Label::distance).thenComparingInt(Label::edge));
        // At the starting node there is no prior incoming edge or turn constraint.
        for (int edge = graph.edgeStart(start); edge < graph.edgeEnd(start); edge++) {
            if(!allowed.test(edge))continue;
            best[edge] = graph.distanceMetres(edge);
            queue.add(new Label(edge, best[edge]));
        }
        while (!queue.isEmpty()) {
            var label = queue.remove();
            int incoming = label.edge();
            if (label.distance() != best[incoming]) continue;
            int node = graph.target(incoming);
            if (node == end) return Optional.of(restore(start, incoming, best[incoming], previous));
            for (int outgoing = graph.edgeStart(node); outgoing < graph.edgeEnd(node); outgoing++) {
                if (!allowed.test(outgoing) || !graph.turnAllowed(incoming, outgoing)) continue;
                double distance = label.distance() + graph.distanceMetres(outgoing);
                if (!Double.isFinite(distance)) throw new ArithmeticException("Route distance overflow");
                if (distance >= best[outgoing]) continue;
                best[outgoing] = distance;
                previous[outgoing] = incoming;
                queue.add(new Label(outgoing, distance));
            }
        }
        return Optional.empty();
    }

    private Route restore(int start, int last, double distance, int[] previous) {
        var edges = new ArrayList<Integer>();
        for (int edge = last; edge != -1; edge = previous[edge]) edges.add(edge);
        Collections.reverse(edges);
        var nodes = new ArrayList<Integer>();
        nodes.add(start);
        for (int edge : edges) nodes.add(graph.target(edge));
        return new Route(distance, nodes, edges);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3 && args.length != 4) throw new IllegalArgumentException("Expected graph path, start node index, end node index, optional offset timestamp");
        var graph = RoutingGraph.read(java.nio.file.Path.of(args[0]));
        var router=new DijkstraRouter(graph);int start=Integer.parseInt(args[1]),end=Integer.parseInt(args[2]);
        var result = args.length==4?router.route(start,end,java.time.OffsetDateTime.parse(args[3]).toInstant()):router.route(start,end);
        System.out.println(result.map(Object::toString).orElse("NO_ROUTE"));
    }
}
