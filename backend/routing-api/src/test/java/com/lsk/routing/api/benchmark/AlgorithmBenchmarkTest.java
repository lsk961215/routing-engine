package com.lsk.routing.api.benchmark;

import com.lsk.routing.core.graph.CoordinateRouter;
import com.lsk.routing.core.graph.RoutingAlgorithm;
import com.lsk.routing.core.graph.RoutingGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AlgorithmBenchmarkTest {
    @TempDir Path directory;
    private final CoordinateRouter.Waypoint point=new CoordinateRouter.Waypoint(new CoordinateRouter.Point(127,37),0);
    private AlgorithmBenchmark.Query query(String id) {
        return new AlgorithmBenchmark.Query(id,id,127,37,127,37.001);
    }
    private CoordinateRouter.ComparisonResult pair(RoutingAlgorithm first,int call) {
        var second=first==RoutingAlgorithm.DIJKSTRA?RoutingAlgorithm.ASTAR:RoutingAlgorithm.DIJKSTRA;
        return new CoordinateRouter.ComparisonResult(point,point,.25,List.of(
                new CoordinateRouter.SearchResult(Optional.empty(),first,.25,call*10,call),
                new CoordinateRouter.SearchResult(Optional.empty(),second,.25,call*10+1,call)));
    }
    @Test void computesMedianNearestRankP95AndPopulationDeviationWithoutRemovingOutliers() {
        var stats=AlgorithmBenchmark.distribution(new double[]{100,2,1,3});
        assertEquals(4,stats.count());assertEquals(2.5,stats.median());assertEquals(100,stats.p95());
        assertEquals(1,stats.min());assertEquals(100,stats.max());assertEquals(26.5,stats.mean());
        assertEquals(Math.sqrt(1801.25),stats.standardDeviation(),1e-9);
        assertEquals(3,AlgorithmBenchmark.distribution(new double[]{1,2,3,4,5}).median());
        var twenty=java.util.stream.IntStream.rangeClosed(1,20).asDoubleStream().toArray();
        assertEquals(19,AlgorithmBenchmark.distribution(twenty).p95());
        assertThrows(IllegalArgumentException.class,()->AlgorithmBenchmark.distribution(new double[0]));
        assertThrows(IllegalArgumentException.class,()->AlgorithmBenchmark.distribution(new double[]{Double.NaN}));
    }
    @Test void excludesWarmupBalancesOrderPerQueryAndReproducesSeededSchedule() {
        var queries=new AlgorithmBenchmark.QuerySet("test",List.of(query("a"),query("b"),query("c")));
        var settings=new AlgorithmBenchmark.Settings(2,4,1,123);
        var calls=new AtomicInteger();
        var first=AlgorithmBenchmark.measure(queries,settings,1,(q,algorithm)->pair(algorithm,calls.incrementAndGet()));
        assertEquals(18,calls.get()); // 3 queries × (2 warmup + 4 measured) pairs.
        assertEquals(24,first.samples().size());
        assertTrue(first.samples().stream().allMatch(s->s.searchMillis()>=70));
        for(var summary:AlgorithmBenchmark.summarize(first.samples())) {
            assertEquals(4,summary.searchMillis().count());
            assertEquals(2,summary.firstMillis().count());assertEquals(2,summary.secondMillis().count());
        }
        for(var query:queries.queries()) {
            var leaders=first.samples().stream().filter(s->s.queryId().equals(query.id()) && s.position()==1).toList();
            for(int i=1;i<leaders.size();i++)assertNotEquals(leaders.get(i-1).algorithm(),leaders.get(i).algorithm());
        }
        calls.set(0);
        var repeated=AlgorithmBenchmark.measure(queries,settings,1,(q,algorithm)->pair(algorithm,calls.incrementAndGet()));
        assertEquals(first.samples(),repeated.samples());
    }
    @Test void rejectsUnbalancedRunsInvalidQueriesAndIncorrectPairResults() {
        assertThrows(IllegalArgumentException.class,()->new AlgorithmBenchmark.Settings(0,20,3,1));
        assertThrows(IllegalArgumentException.class,()->new AlgorithmBenchmark.Settings(4,3,3,1));
        assertThrows(IllegalArgumentException.class,()->AlgorithmBenchmark.validateQueries(
                new AlgorithmBenchmark.QuerySet("duplicates",List.of(query("a"),query("a")))));
        var noRoute=pair(RoutingAlgorithm.DIJKSTRA,1);
        assertThrows(IllegalStateException.class,()->AlgorithmBenchmark.validatePair(noRoute,RoutingAlgorithm.ASTAR));
        var found=new CoordinateRouter.SearchResult(Optional.of(new CoordinateRouter.Route(1,List.of(point.point(),point.point()),point,point)),
                RoutingAlgorithm.DIJKSTRA,.25,1,1);
        assertThrows(IllegalStateException.class,()->AlgorithmBenchmark.validatePair(new CoordinateRouter.ComparisonResult(point,point,.25,
                List.of(found,noRoute.results().get(1))),RoutingAlgorithm.DIJKSTRA));
        var different=new CoordinateRouter.SearchResult(Optional.of(new CoordinateRouter.Route(2,List.of(point.point(),point.point()),point,point)),
                RoutingAlgorithm.ASTAR,.25,1,1);
        assertThrows(IllegalStateException.class,()->AlgorithmBenchmark.validatePair(new CoordinateRouter.ComparisonResult(point,point,.25,
                List.of(found,different)),RoutingAlgorithm.DIJKSTRA));
    }
    @Test void freshJvmRunWritesRawSamplesMetadataAndStandaloneReport() throws Exception {
        var graph=directory.resolve("tiny.rgraph");
        new RoutingGraph(new long[]{1,2},new double[]{37,37.001},new double[]{127,127},new int[]{0,1,1},
                new int[]{1},new long[]{1},new double[]{111},new long[0]).write(graph);
        var queries=directory.resolve("queries.json");
        var mapper=new ObjectMapper();
        mapper.writeValue(queries.toFile(),new AlgorithmBenchmark.QuerySet("tiny",List.of(query("one-way"))));
        var output=directory.resolve("report");
        AlgorithmBenchmark.main(new String[]{graph.toString(),queries.toString(),output.toString(),"1","2","1","123"});
        var report=mapper.readValue(output.resolve("report.json").toFile(),AlgorithmBenchmark.Report.class);
        assertEquals(4,report.forks().getFirst().samples().size());
        assertEquals(64,report.forks().getFirst().graphSha256().length());
        assertTrue(report.forks().getFirst().environment().jvmArguments().contains("-Xmx2g"));
        assertEquals(2,report.summaries().size());
        assertTrue(report.forks().getFirst().samples().stream().allMatch(s->s.distanceMetres()==111));
        var html=Files.readString(output.resolve("report.html"));
        assertFalse(html.contains("__REPORT_JSON__"));assertTrue(html.contains("tiny"));
        assertThrows(FileAlreadyExistsException.class,()->AlgorithmBenchmark.main(
                new String[]{graph.toString(),queries.toString(),output.toString(),"1","2","1","123"}));
    }
}
