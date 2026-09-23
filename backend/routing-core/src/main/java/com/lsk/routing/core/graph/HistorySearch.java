package com.lsk.routing.core.graph;

import java.util.*;

/** Dijkstra over (incoming edge, restriction prefix state); storage is query-local. */
final class HistorySearch {
    record Seed(int edge,double cost) {}
    record Result(double distance,List<Integer> edges) {}
    private record State(int edge,int history) {}
    private record Label(State state,double cost) {}
    static Optional<Result> route(RoutingGraph graph,List<Seed> seeds,int endNode,
                                  Map<Integer,Double> partialEnds,double directDistance) {
        var best=new HashMap<State,Double>();var previous=new HashMap<State,State>();
        var queue=new PriorityQueue<Label>(Comparator.comparingDouble(Label::cost)
                .thenComparingInt(l->l.state.edge).thenComparingInt(l->l.state.history));
        for(var seed:seeds) {
            int history=graph.restrictionState(0,seed.edge);
            if(history<0)continue;
            var state=new State(seed.edge,history);
            if(seed.cost<best.getOrDefault(state,Double.POSITIVE_INFINITY)) {
                best.put(state,seed.cost);queue.add(new Label(state,seed.cost));
            }
        }
        double winner=directDistance;State winnerLast=null;
        while(!queue.isEmpty()) {
            var label=queue.remove();var state=label.state;
            if(label.cost!=best.get(state))continue;
            if(label.cost>=winner)break;
            int node=graph.target(state.edge);
            if(node==endNode){winner=label.cost;winnerLast=state;break;}
            for(int outgoing=graph.edgeStart(node);outgoing<graph.edgeEnd(node);outgoing++) {
                if(!graph.turnAllowed(state.edge,outgoing))continue;
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
                best.put(next,cost);previous.put(next,state);queue.add(new Label(next,cost));
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
