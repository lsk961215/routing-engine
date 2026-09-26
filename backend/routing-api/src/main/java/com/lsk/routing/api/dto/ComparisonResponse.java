package com.lsk.routing.api.dto;

import java.util.List;

/** Shared snapping is reported once; an unreachable route retains its search metrics. */
public record ComparisonResponse(String code,List<RouteResponse.Waypoint> waypoints,double snapMillis,List<Result> results) {
    public record Result(String algorithm,String code,List<RouteResponse.Route> routes,Metrics metrics) {}
    public record Metrics(double searchMillis,long expandedStates) {}
}
