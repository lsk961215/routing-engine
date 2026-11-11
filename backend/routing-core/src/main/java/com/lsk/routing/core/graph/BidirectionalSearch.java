package com.lsk.routing.core.graph;

import java.util.*;
import java.util.function.IntPredicate;

/** Dijkstra in both directions on the SAME (incoming edge, restriction history) state graph. */
final class BidirectionalSearch {
    private record State(int edge,int history) {}
    // Immutable links retain the exact candidate path even if another label later improves.
    private record Label(State state,double cost,Label link) {}
    private static final Comparator<Label> ORDER=Comparator.comparingDouble(Label::cost)
            .thenComparingInt(l->l.state.edge).thenComparingInt(l->l.state.history);
    private static final class Frontier {
        final Map<State,Label> best=new HashMap<>();
        final PriorityQueue<Label> queue=new PriorityQueue<>(ORDER);
        Label offer(State state,double cost,Label link) {
            var previous=best.get(state);
            if(previous!=null && cost>=previous.cost)return null;
            var label=new Label(state,cost,link);best.put(state,label);queue.add(label);return label;
        }
        Label head() {
            while(!queue.isEmpty() && best.get(queue.peek().state)!=queue.peek())queue.remove();
            return queue.peek();
        }
    }
    private final RoutingGraph graph;
    private final ReverseSearchIndex reverse;
    private final int[] sources;
    private final IntPredicate allowed;
    private final HistorySearch.Metrics metrics;
    private final Frontier forward=new Frontier(),backward=new Frontier();
    private double winner;
    private Label winnerForward,winnerBackward;

    private BidirectionalSearch(RoutingGraph graph,ReverseSearchIndex reverse,int[] sources,
                                IntPredicate allowed,HistorySearch.Metrics metrics,double direct) {
        this.graph=graph;this.reverse=reverse;this.sources=sources;this.allowed=allowed;this.metrics=metrics;winner=direct;
    }
    static Optional<HistorySearch.Result> route(RoutingGraph graph,ReverseSearchIndex reverse,int[] sources,
                                                List<HistorySearch.Seed> seeds,int endNode,Map<Integer,Double> partialEnds,
                                                double direct,IntPredicate allowed,HistorySearch.Metrics metrics) {
        return new BidirectionalSearch(graph,reverse,sources,allowed,metrics,direct).run(seeds,endNode,partialEnds);
    }
    private void offer(boolean fromStart,State state,double cost,Label link) {
        var own=fromStart?forward:backward;var other=fromStart?backward:forward;
        var label=own.offer(state,cost,link);
        if(label==null)return;
        metrics.maxQueueSize=Math.max(metrics.maxQueueSize,(long)forward.queue.size()+backward.queue.size());
        var opposite=other.best.get(state);
        if(opposite!=null) {
            double candidate=add(cost,opposite.cost);
            if(candidate<winner) {
                winner=candidate;winnerForward=fromStart?label:opposite;winnerBackward=fromStart?opposite:label;
            }
        }
    }
    private Optional<HistorySearch.Result> run(List<HistorySearch.Seed> seeds,int endNode,Map<Integer,Double> partialEnds) {
        for(var seed:seeds) {
            if(!allowed.test(seed.edge()))continue;
            int history=graph.restrictionState(0,seed.edge());
            if(history>=0)offer(true,new State(seed.edge(),history),seed.cost(),null);
        }
        if(endNode>=0) {
            for(int p=reverse.start(endNode);p<reverse.end(endNode);p++) {
                int edge=reverse.edge(p);
                if(allowed.test(edge))for(int history:reverse.histories(edge))offer(false,new State(edge,history),0,null);
            }
        } else {
            // A partial destination is a virtual terminal transition. Keep its fractional cost
            // but do not append that full edge to the reconstructed geometry.
            for(int terminal:partialEnds.keySet().stream().sorted().toList()) {
                if(!allowed.test(terminal))continue;
                int node=sources[terminal];
                for(int p=reverse.start(node);p<reverse.end(node);p++) {
                    int edge=reverse.edge(p);
                    if(!allowed.test(edge)||!graph.turnAllowed(edge,terminal))continue;
                    for(int history:reverse.histories(edge))if(graph.restrictionState(history,terminal)>=0)
                        offer(false,new State(edge,history),partialEnds.get(terminal),null);
                }
            }
        }
        boolean tieForward=true;
        while(true) {
            var first=forward.head();var last=backward.head();
            if(first==null || last==null)break;
            // A meeting is only an upper bound. Stop once neither frontier can improve it.
            if(Double.isFinite(winner) && first.cost+last.cost>=winner)break;
            boolean fromStart=first.cost<last.cost || (first.cost==last.cost && tieForward);
            if(first.cost==last.cost)tieForward=!tieForward;
            var label=(fromStart?forward:backward).queue.remove();
            metrics.expandedStates++;
            if(metrics.observer!=null)metrics.observer.expanded(graph.target(label.state.edge),metrics.expandedStates);
            if(fromStart)expandForward(label);else expandBackward(label);
        }
        if(!Double.isFinite(winner))return Optional.empty();
        var edges=new ArrayList<Integer>();
        for(var label=winnerForward;label!=null;label=label.link)edges.add(label.state.edge);
        Collections.reverse(edges);
        // The meeting state is already in the forward half; append only its successors.
        for(var label=winnerBackward==null?null:winnerBackward.link;label!=null;label=label.link)edges.add(label.state.edge);
        return Optional.of(new HistorySearch.Result(winner,List.copyOf(edges)));
    }
    private void expandForward(Label label) {
        int node=graph.target(label.state.edge);
        for(int edge=graph.edgeStart(node);edge<graph.edgeEnd(node);edge++) {
            metrics.inspectedEdges++;
            if(!allowed.test(edge)||!graph.turnAllowed(label.state.edge,edge))continue;
            int history=graph.restrictionState(label.state.history,edge);
            if(history<0)continue;
            double cost=add(label.cost,graph.distanceMetres(edge));
            if(cost<winner)offer(true,new State(edge,history),cost,label);
        }
    }
    private void expandBackward(Label label) {
        int outgoing=label.state.edge,node=sources[outgoing];
        for(int p=reverse.start(node);p<reverse.end(node);p++) {
            int incoming=reverse.edge(p);metrics.inspectedEdges++;
            if(!allowed.test(incoming)||!graph.turnAllowed(incoming,outgoing))continue;
            // Reverse the original transition, NOT the direction of the restriction rules.
            for(int history:reverse.histories(incoming))if(graph.restrictionState(history,outgoing)==label.state.history) {
                double cost=add(label.cost,graph.distanceMetres(outgoing));
                if(cost<winner)offer(false,new State(incoming,history),cost,label);
            }
        }
    }
    private static double add(double a,double b) {
        double value=a+b;
        if(!Double.isFinite(value))throw new ArithmeticException("Route distance overflow");
        return value;
    }
}
