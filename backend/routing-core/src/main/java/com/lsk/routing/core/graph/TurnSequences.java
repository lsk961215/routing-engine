package com.lsk.routing.core.graph;

import java.util.*;

/** Immutable prefix/failure automaton over edge IDs, including overlapping restrictions. */
final class TurnSequences {
    private static final class Node {
        final int lastEdge;
        Node(int lastEdge) { this.lastEdge=lastEdge; }
        final Map<Integer,Integer> next=new HashMap<>();
        final Map<Integer,Set<Integer>> required=new HashMap<>();
        int failure;
        boolean forbidden;
    }
    private final List<Node> nodes=new ArrayList<>();
    TurnSequences(List<RoutingGraph.SequenceRestriction> rules) {
        nodes.add(new Node(-1));
        for(int group=0;group<rules.size();group++) {
            var rule=rules.get(group);
            for(var path:rule.paths()) {
                int state=0;
                for(int i=0;i<path.size();i++) {
                    int edge=path.get(i);
                    if(rule.only() && i>0)nodes.get(state).required.computeIfAbsent(group,k->new HashSet<>()).add(edge);
                    var current=nodes.get(state);
                    Integer child=current.next.get(edge);
                    if(child==null){child=nodes.size();nodes.add(new Node(edge));current.next.put(edge,child);}
                    state=child;
                }
                if(!rule.only())nodes.get(state).forbidden=true;
            }
        }
        var queue=new ArrayDeque<Integer>();queue.addAll(nodes.getFirst().next.values());
        while(!queue.isEmpty()) {
            int state=queue.remove();var node=nodes.get(state);var failure=nodes.get(node.failure);
            node.forbidden|=failure.forbidden;
            failure.required.forEach((group,allowed)->node.required.computeIfAbsent(group,k->new HashSet<>()).addAll(allowed));
            for(var transition:node.next.entrySet()) {
                int f=node.failure;
                while(f!=0 && !nodes.get(f).next.containsKey(transition.getKey()))f=nodes.get(f).failure;
                nodes.get(transition.getValue()).failure=nodes.get(f).next.getOrDefault(transition.getKey(),0);
                queue.add(transition.getValue());
            }
        }
    }
    int advance(int state,int edge) {
        for(var allowed:nodes.get(state).required.values())if(!allowed.contains(edge))return -1;
        while(state!=0 && !nodes.get(state).next.containsKey(edge))state=nodes.get(state).failure;
        int next=nodes.get(state).next.getOrDefault(edge,0);
        return nodes.get(next).forbidden?-1:next;
    }
    /** Every nonzero state is a matched prefix and therefore has one fixed last edge. */
    Map<Integer,int[]> historiesByLastEdge() {
        var grouped=new HashMap<Integer,List<Integer>>();
        for(int state=1;state<nodes.size();state++) {
            var node=nodes.get(state);
            if(!node.forbidden)grouped.computeIfAbsent(node.lastEdge,e->new ArrayList<>(List.of(0))).add(state);
        }
        var result=new HashMap<Integer,int[]>();
        grouped.forEach((edge,states)->result.put(edge,states.stream().mapToInt(Integer::intValue).toArray()));
        return Map.copyOf(result);
    }
}
