package com.lsk.routing.api.service;

import com.lsk.routing.api.dto.RouteResponse;
import com.lsk.routing.api.dto.ComparisonResponse;
import com.lsk.routing.core.graph.CoordinateRouter;
import com.lsk.routing.core.graph.RoutingAlgorithm;
import com.lsk.routing.core.graph.RoutingGraph;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

@Service
public class RoutingService {
    private final CoordinateRouter router;
    public RoutingService(ObjectProvider<RoutingGraph> graphs) {
        var graph=graphs.getIfAvailable();
        router=graph==null?null:new CoordinateRouter(graph);
    }
    public RouteResponse route(double startLon,double startLat,double endLon,double endLat) {
        return route(startLon,startLat,endLon,endLat,"dijkstra");
    }
    public RouteResponse route(double startLon,double startLat,double endLon,double endLat,String algorithm) {
        RoutingAlgorithm strategy=switch(algorithm) {
            case "dijkstra" -> RoutingAlgorithm.DIJKSTRA;
            case "astar" -> RoutingAlgorithm.ASTAR;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown routing algorithm");
        };
        validate(startLon,startLat);validate(endLon,endLat);
        if(router==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Graph not configured");
        try {
            var search=router.search(startLon,startLat,endLon,endLat,strategy);
            var result=search.route()
                    .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"No directed route"));
            return new RouteResponse("Ok",List.of(routeData(result)),
                    List.of(waypoint(result.start()),waypoint(result.end())),
                    new RouteResponse.Metrics(algorithm,search.snapMillis(),search.searchMillis(),search.expandedStates()));
        } catch(CoordinateRouter.OutsideGraphException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,e.getMessage());
        }
    }
    public ComparisonResponse compare(double startLon,double startLat,double endLon,double endLat) {
        validate(startLon,startLat);validate(endLon,endLat);
        if(router==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Graph not configured");
        try {
            var comparison=router.compare(startLon,startLat,endLon,endLat);
            var results=comparison.results().stream().map(search->new ComparisonResponse.Result(
                    search.algorithm()==RoutingAlgorithm.DIJKSTRA?"dijkstra":"astar",
                    search.route().isPresent()?"Ok":"NoRoute",
                    search.route().map(result->List.of(routeData(result))).orElseGet(List::of),
                    new ComparisonResponse.Metrics(search.searchMillis(),search.expandedStates()))).toList();
            return new ComparisonResponse("Ok",List.of(waypoint(comparison.start()),waypoint(comparison.end())),
                    comparison.snapMillis(),results);
        } catch(CoordinateRouter.OutsideGraphException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,e.getMessage());
        }
    }
    private static RouteResponse.Route routeData(CoordinateRouter.Route result) {
        var geometry=result.geometry().stream().map(p->List.of(p.longitude(),p.latitude())).toList();
        return new RouteResponse.Route(new RouteResponse.Geometry("LineString",geometry),result.distanceMetres(),null);
    }
    private static void validate(double lon,double lat) {
        if(!Double.isFinite(lon)||!Double.isFinite(lat)||Math.abs(lon)>180||Math.abs(lat)>90)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid coordinate");
    }
    private static RouteResponse.Waypoint waypoint(CoordinateRouter.Waypoint point) {
        return new RouteResponse.Waypoint("",List.of(point.point().longitude(),point.point().latitude()),point.distanceMetres());
    }
}
