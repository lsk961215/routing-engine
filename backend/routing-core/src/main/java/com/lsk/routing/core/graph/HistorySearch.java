package com.lsk.routing.core.graph;

import java.util.*;
import java.util.function.IntPredicate;
import java.util.function.IntToDoubleFunction;

/** Dijkstra/A* over (incoming edge, restriction prefix state); storage is query-local. */
final class HistorySearch {
    record Seed(int edge,double cost) {}
    record Result(double distance,List<Integer> edges) {}
    private record State(int edge,int history) {}
    private record Label(State state,double cost,double priority) {}
    static final class Metrics { long expandedStates; }
    static Optional<Result> route(RoutingGraph graph,List<Seed> seeds,int endNode,
                                  Map<Integer,Double> partialEnds,double directDistance) {
        return route(graph,seeds,endNode,partialEnds,directDistance,EdgeAvailability.staticOnly(graph));
    }
    static Optional<Result> route(RoutingGraph graph,List<Seed> seeds,int endNode,
                                  Map<Integer,Double> partialEnds,double directDistance,IntPredicate allowed) {
        return route(graph,seeds,endNode,partialEnds,directDistance,allowed,node->0,new Metrics());
    }
    static Optional<Result> route(RoutingGraph graph,List<Seed> seeds,int endNode,
                                  Map<Integer,Double> partialEnds,double directDistance,IntPredicate allowed,
                                  IntToDoubleFunction estimate,Metrics metrics) {
        var best=new HashMap<State,Double>();var previous=new HashMap<State,State>();
        var queue=new PriorityQueue<Label>(Comparator.comparingDouble(Label::priority).thenComparingDouble(Label::cost)
                .thenComparingInt(l->l.state.edge).thenComparingInt(l->l.state.history));
        for(var seed:seeds) {
            if(!allowed.test(seed.edge))continue;
            int history=graph.restrictionState(0,seed.edge);
            if(history<0)continue;
            var state=new State(seed.edge,history);
            if(seed.cost<best.getOrDefault(state,Double.POSITIVE_INFINITY)) {
                best.put(state,seed.cost);queue.add(new Label(state,seed.cost,seed.cost+estimate.applyAsDouble(graph.target(seed.edge))));
            }
        }
        double winner=directDistance;State winnerLast=null;
        while(!queue.isEmpty()) {
            var label=queue.remove();var state=label.state;
            if(label.cost!=best.get(state))continue;
            if(label.priority>=winner)break;
            metrics.expandedStates++;
            int node=graph.target(state.edge);
            if(node==endNode){winner=label.cost;winnerLast=state;break;}
            for(int outgoing=graph.edgeStart(node);outgoing<graph.edgeEnd(node);outgoing++) {
                if(!allowed.test(outgoing) || !graph.turnAllowed(state.edge,outgoing))continue;
                int history=graph.restrictionState(state.history,outgoing);
                if(history<0)continue;
                Double partial=partialEnds.get(outgoing);
                if(partial!=null) {
                    double cost=add(label.cost,partial);
                    if(cost<winner){winner=cost;winnerLast=state;}
                }
                double cost=add(label.cost,graph.distanceMetres(outgoing));
                var next=new State(outgoing,history);
                if(cost>=winner || cost>=best.getOrDefault(next,Double.POSITIVE_INFINITY))continue;
                best.put(next,cost);previous.put(next,state);queue.add(new Label(next,cost,cost+estimate.applyAsDouble(graph.target(outgoing))));
            }
        }
        if(!Double.isFinite(winner))return Optional.empty();
        var path=new ArrayList<Integer>();
        for(var state=winnerLast;state!=null;state=previous.get(state))path.add(state.edge);
        Collections.reverse(path);return Optional.of(new Result(winner,List.copyOf(path)));
    }
    private static double add(double a,double b) {
        double result=a+b;
        if(!Double.isFinite(result))throw new ArithmeticException("Route distance overflow");
        return result;
    }
}
