package com.lsk.routing.api.benchmark;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.lsk.routing.core.graph.*;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Records visualization separately from benchmark timings; stores first real expansion per spatial cell. */
public final class ExportDemoTraces {
    private static final ObjectMapper JSON=new ObjectMapper();
    static final int CELL_METRES=120;
    @JsonIgnoreProperties(ignoreUnknown=true)
    public record Input(Source source,List<Example> examples) {}
    @JsonIgnoreProperties(ignoreUnknown=true)
    public record Source(String graphSha256,String queriesSha256) {}
    @JsonIgnoreProperties(ignoreUnknown=true)
    public record Example(String id,List<Double> start,List<Double> end,List<DemoRoute> routes) {}
    @JsonIgnoreProperties(ignoreUnknown=true)
    public record DemoRoute(String algorithm,double distance,long expandedStates,Geometry geometry) {}
    public record Geometry(String type,List<List<Double>> coordinates) {}
    public record Trace(String algorithm,long expandedStates,List<List<Number>> points,List<Double> bounds) {}
    public record Output(int version,String exampleId,String generatedAt,String graphSha256,String demoSha256,
                         int cellMetres,List<String> pointFields,List<Trace> traces) {}
    static final class Sampler implements CoordinateRouter.ExpansionObserver {
        final Set<Long> cells=new HashSet<>();
        final List<List<Number>> points=new ArrayList<>();
        double west=Double.POSITIVE_INFINITY,south=west,east=-west,north=-west;
        @Override public void expanded(double longitude,double latitude,long ordinal) {
            west=Math.min(west,longitude);east=Math.max(east,longitude);
            south=Math.min(south,latitude);north=Math.max(north,latitude);
            // Fixed local projection for the Seoul dataset (reference latitude 37.5 degrees).
            int x=(int)Math.floor(longitude*111320*Math.cos(Math.toRadians(37.5))/CELL_METRES);
            int y=(int)Math.floor(latitude*111320/CELL_METRES);
            long key=((long)x<<32)|(y&0xffffffffL);
            if(cells.add(key))points.add(List.of(Math.round(longitude*1e6)/1e6,Math.round(latitude*1e6)/1e6,ordinal));
        }
        List<Double> bounds() {return points.isEmpty()?List.of():List.of(west,south,east,north);}
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=4)throw new IllegalArgumentException("graph queries demo-data output-directory");
        var graphPath=Path.of(args[0]);var queries=Path.of(args[1]);var input=Path.of(args[2]);var output=Path.of(args[3]);
        var demo=JSON.readValue(input.toFile(),Input.class);
        String graphHash=hash(graphPath),demoHash=hash(input);
        if(!graphHash.equals(demo.source.graphSha256) || !hash(queries).equals(demo.source.queriesSha256))
            throw new IllegalStateException("Trace inputs differ from demo benchmark inputs");
        var router=new CoordinateRouter(RoutingGraph.read(graphPath));
        var outputs=new LinkedHashMap<String,Output>();
        for(var example:demo.examples) {
            if(!example.id.matches("[a-z0-9-]+"))throw new IllegalArgumentException("Invalid example id");
            var traces=new ArrayList<Trace>();
            for(var expected:example.routes) {
                var algorithm=RoutingAlgorithm.valueOf(expected.algorithm.toUpperCase(Locale.ROOT));
                var sampler=new Sampler();
                var result=router.trace(example.start.get(0),example.start.get(1),example.end.get(0),example.end.get(1),algorithm,sampler);
                var route=result.route().orElseThrow();
                var coordinates=route.geometry().stream().map(p->List.of(p.longitude(),p.latitude())).toList();
                if(Math.abs(route.distanceMetres()-expected.distance)>1e-6 || result.expandedStates()!=expected.expandedStates
                        || !coordinates.equals(expected.geometry.coordinates))
                    throw new IllegalStateException("Trace differs from demo result: "+example.id+" "+algorithm);
                traces.add(new Trace(expected.algorithm,result.expandedStates(),sampler.points,sampler.bounds()));
                System.out.printf("%s %s: %,d expanded states → %,d sampled locations%n",example.id,expected.algorithm,result.expandedStates(),sampler.points.size());
            }
            outputs.put(example.id,new Output(3,example.id,Instant.now().toString(),graphHash,demoHash,CELL_METRES,
                    List.of("longitude","latitude","expansionOrdinal"),traces));
        }
        // Only write after all route geometries, distances and counts agree with the published examples.
        Files.createDirectories(output);
        for(var entry:outputs.entrySet())JSON.writeValue(output.resolve(entry.getKey()+".json").toFile(),entry.getValue());
    }
    private static String hash(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
