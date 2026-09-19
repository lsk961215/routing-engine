package com.lsk.routing.api.service;

import org.springframework.stereotype.Service;

@Service
public class RoutingService {
    public String route(
            double startLon,
            double startLat,
            double endLon,
            double endLat) {

        // TODO: 이후 A*/Dijkstra 호출
        return "hello";
    }
}

