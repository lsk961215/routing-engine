package com.lsk.routing.api.controller;

import com.lsk.routing.api.service.RoutingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RouteController {

    private final RoutingService routingService;

    public RouteController(RoutingService routingService) {
        this.routingService = routingService;
    }

    @GetMapping("/api/compare")
    public com.lsk.routing.api.dto.ComparisonResponse compare(
            @RequestParam double startLon,
            @RequestParam double startLat,
            @RequestParam double endLon,
            @RequestParam double endLat) {
        return routingService.compare(startLon,startLat,endLon,endLat);
    }

    @GetMapping({"/route", "/api/route"})
    public com.lsk.routing.api.dto.RouteResponse route(
            @RequestParam double startLon,
            @RequestParam double startLat,
            @RequestParam double endLon,
            @RequestParam double endLat,
            @RequestParam(defaultValue = "dijkstra") String algorithm) {

        return routingService.route(
                startLon, startLat,
                endLon, endLat, algorithm
        );
    }
}
