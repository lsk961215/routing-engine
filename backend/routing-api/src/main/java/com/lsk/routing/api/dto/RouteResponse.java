package com.lsk.routing.api.dto;

import java.util.List;

public record RouteResponse(String code, List<Route> routes, List<Waypoint> waypoints, Metrics metrics) {
    public record Metrics(String algorithm,double snapMillis,double searchMillis,long expandedStates) {}
    public record Geometry(String type, List<List<Double>> coordinates) {}
    public record Route(Geometry geometry, double distance, Double duration) {}
    public record Waypoint(String name, List<Double> location, double distance) {}
}
