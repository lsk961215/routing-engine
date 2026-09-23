package com.lsk.routing.api.service;

import com.lsk.routing.api.dto.RouteResponse;
import com.lsk.routing.core.graph.CoordinateRouter;
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
        validate(startLon,startLat);validate(endLon,endLat);
        if(router==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Graph not configured");
        try {
            var result=router.route(startLon,startLat,endLon,endLat)
                    .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"No directed route"));
            var geometry=result.geometry().stream().map(p->List.of(p.longitude(),p.latitude())).toList();
            return new RouteResponse("Ok",List.of(new RouteResponse.Route(
                    new RouteResponse.Geometry("LineString",geometry),result.distanceMetres(),null)),
                    List.of(waypoint(result.start()),waypoint(result.end())));
        } catch(CoordinateRouter.OutsideGraphException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,e.getMessage());
        }
    }
    private static void validate(double lon,double lat) {
        if(!Double.isFinite(lon)||!Double.isFinite(lat)||Math.abs(lon)>180||Math.abs(lat)>90)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid coordinate");
    }
    private static RouteResponse.Waypoint waypoint(CoordinateRouter.Waypoint point) {
        return new RouteResponse.Waypoint("",List.of(point.point().longitude(),point.point().latitude()),point.distanceMetres());
    }
}
