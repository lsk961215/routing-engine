package com.lsk.routing.api.controller;

import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Immutable diagnostic overlay, loaded once; never changes routing topology. */
@RestController
public class ExcludedRoadController {
    public record Properties(String wayId, String name, String category, List<String> reasons,
                             List<String> nodeIds, List<String> relationIds) {}
    public record Geometry(String type, List<List<Double>> coordinates) {}
    public record Feature(String type, Geometry geometry, Properties properties) {}
    public record Collection(String type, List<Feature> features) {}
    private record Indexed(Feature feature, double west, double south, double east, double north) {
        boolean intersects(double w,double s,double e,double n) {
            return west<=e && east>=w && south<=n && north>=s;
        }
    }
    private final List<Indexed> roads;

    public ExcludedRoadController(ObjectMapper mapper, @Value("${routing.exclusions.path:}") String path) {
        if(path.isBlank()){roads=null;return;}
        var data=mapper.readValue(Path.of(path).toFile(),Collection.class);
        if(!"FeatureCollection".equals(data.type()))throw new IllegalArgumentException("Invalid exclusions GeoJSON");
        roads=data.features().stream().map(f->{
            if(!"Feature".equals(f.type()) || !"LineString".equals(f.geometry().type()) || f.geometry().coordinates().size()<2)
                throw new IllegalArgumentException("Invalid excluded road geometry");
            double w=180,s=90,e=-180,n=-90;
            for(var c:f.geometry().coordinates()) {
                if(c.size()!=2 || !valid(c.get(0),c.get(1)))throw new IllegalArgumentException("Invalid excluded road coordinate");
                w=Math.min(w,c.get(0));e=Math.max(e,c.get(0));s=Math.min(s,c.get(1));n=Math.max(n,c.get(1));
            }
            return new Indexed(f,w,s,e,n);
        }).toList();
    }

    @GetMapping("/api/excluded-roads")
    public Collection query(@RequestParam double west,@RequestParam double south,
                            @RequestParam double east,@RequestParam double north) {
        if(!valid(west,south)||!valid(east,north)||west>=east||south>=north)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid bounding box");
        if(roads==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Exclusion overlay not configured");
        return new Collection("FeatureCollection",roads.stream().filter(r->r.intersects(west,south,east,north)).map(Indexed::feature).toList());
    }
    private static boolean valid(double lon,double lat) {
        return Double.isFinite(lon)&&Double.isFinite(lat)&&Math.abs(lon)<=180&&Math.abs(lat)<=90;
    }
}
