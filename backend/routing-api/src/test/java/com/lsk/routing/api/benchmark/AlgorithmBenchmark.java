package com.lsk.routing.api.benchmark;

import com.lsk.routing.core.graph.CoordinateRouter;
import com.lsk.routing.core.graph.RoutingAlgorithm;
import com.lsk.routing.core.graph.RoutingGraph;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Offline paired benchmark. Does not start Spring or send HTTP requests. */
public final class AlgorithmBenchmark {
    private static final ObjectMapper JSON = new ObjectMapper();
    public record Query(String id,String name,double startLon,double startLat,double endLon,double endLat) {}
    public record QuerySet(String id,List<Query> queries) {}
    public record Settings(int warmupRounds,int measuredRounds,int forks,long seed) {
        public Settings {
            if(warmupRounds<1 || measuredRounds<2 || measuredRounds%2!=0 || forks<1)
                throw new IllegalArgumentException("Warmup/forks must be positive; measured rounds must be even and >= 2");
        }
    }
    public record Sample(int fork,int round,int queryPosition,String queryId,String algorithm,int position,
                         String code,Double distanceMetres,double snapMillis,double searchMillis,long expandedStates) {}
    public record Distribution(int count,double min,double median,double p95,double max,double mean,double standardDeviation) {}
    public record Summary(String queryId,String algorithm,Distribution searchMillis,Distribution firstMillis,
                          Distribution secondMillis,Distribution expandedStates) {}
    public record Gc(long collections,long millis) {
        static Gc now() {
            long count=0,time=0;
            for(var bean:ManagementFactory.getGarbageCollectorMXBeans()) {
                count+=Math.max(0,bean.getCollectionCount());time+=Math.max(0,bean.getCollectionTime());
            }
            return new Gc(count,time);
        }
        Gc since(Gc earlier) { return new Gc(collections-earlier.collections,millis-earlier.millis); }
    }
    public record Measurement(List<Sample> samples,Gc measuredGc) {}
    public record Environment(String javaVersion,String vm,String os,String arch,int availableProcessors,
                              long maxHeapBytes,List<String> jvmArguments,List<String> garbageCollectors) {}
    public record ForkResult(int fork,String startedAt,String graphSha256,String queriesSha256,Environment environment,
                             double initializationMillis,Gc measuredGc,List<Summary> summaries,List<Sample> samples) {}
    public record Report(int version,String generatedAt,String gitRevision,String gitDirty,Settings settings,
                         QuerySet querySet,Map<String,String> methodology,List<ForkResult> forks,List<Summary> summaries) {}
    @FunctionalInterface interface PairRunner {
        CoordinateRouter.ComparisonResult run(Query query,RoutingAlgorithm first);
    }

    public static void main(String[] args) throws Exception {
        if(args.length==8 && args[0].equals("--worker")) {
            worker(Path.of(args[1]),Path.of(args[2]),Path.of(args[3]),
                    new Settings(Integer.parseInt(args[4]),Integer.parseInt(args[5]),1,Long.parseLong(args[7])),Integer.parseInt(args[6]));
            return;
        }
        if(args.length!=7)throw new IllegalArgumentException("graph queries output warmupRounds measuredRounds forks seed");
        var graph=Path.of(args[0]).toAbsolutePath();var queries=Path.of(args[1]).toAbsolutePath();
        var output=Path.of(args[2]).toAbsolutePath();
        var settings=new Settings(Integer.parseInt(args[3]),Integer.parseInt(args[4]),Integer.parseInt(args[5]),Long.parseLong(args[6]));
        var querySet=readQueries(queries);
        // Fail before launching any work or overwriting a previous run.
        var graphHash=sha256(graph);var queryHash=sha256(queries);
        Files.createDirectories(output.getParent());Files.createDirectory(output);
        var forks=new ArrayList<ForkResult>();
        for(int fork=1;fork<=settings.forks();fork++) {
            var raw=output.resolve("fork-"+fork+".json");
            var command=List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                    "-Xms1g","-Xmx2g","-XX:+UseG1GC","-cp",System.getProperty("java.class.path"),
                    AlgorithmBenchmark.class.getName(),"--worker",graph.toString(),queries.toString(),raw.toString(),
                    String.valueOf(settings.warmupRounds()),String.valueOf(settings.measuredRounds()),String.valueOf(fork),String.valueOf(settings.seed()));
            System.out.printf("Fork %d/%d: fresh JVM, %d warmup + %d measured rounds × %d queries%n",
                    fork,settings.forks(),settings.warmupRounds(),settings.measuredRounds(),querySet.queries().size());
            var process=new ProcessBuilder(command).inheritIO().start();
            var cleanup=new Thread(process::destroy);
            Runtime.getRuntime().addShutdownHook(cleanup);
            try {
                if(process.waitFor()!=0)throw new IllegalStateException("Benchmark fork "+fork+" failed; no aggregate report written");
            } finally {
                process.destroy();Runtime.getRuntime().removeShutdownHook(cleanup);
            }
            var result=JSON.readValue(raw.toFile(),ForkResult.class);
            if(!result.graphSha256().equals(graphHash) || !result.queriesSha256().equals(queryHash))
                throw new IllegalStateException("Input files changed between forks");
            forks.add(result);
        }
        var samples=forks.stream().flatMap(f->f.samples().stream()).toList();
        var report=new Report(1,Instant.now().toString(),git("rev-parse","HEAD"),git("status","--porcelain"),settings,querySet,
                Map.of("scope","Search and path reconstruction; snapping, graph/index loading, JSON, HTTP and warmup excluded",
                        "order","Sequential paired runs, first algorithm alternates per query each round; query order shuffled with seed + fork",
                        "statistics","Median: midpoint for even N; p95: nearest rank ceil(0.95*N); population standard deviation; no outlier removal",
                        "aggregation","Per query only, equal measured sample count per fork; first/second positions also reported",
                        "gc","Natural GC included in timings; no forced GC; measured-phase GC deltas recorded per fork",
                        "limits","Local wall-clock measurements; fixed warmup does not prove JIT convergence; OS activity and thermal effects remain"),
                forks,summarize(samples));
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("report.json").toFile(),report);
        try(var template=AlgorithmBenchmark.class.getResourceAsStream("/benchmark-report.html")) {
            if(template==null)throw new IOException("Missing benchmark report template");
            var html=new String(template.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)
                    .replace("__REPORT_JSON__",JSON.writeValueAsString(report).replace("<","\\u003c"));
            Files.writeString(output.resolve("report.html"),html);
        }
        System.out.println("Report: "+output.resolve("report.html"));
        System.out.println("Raw samples + environment: "+output.resolve("report.json"));
    }

    private static void worker(Path graphPath,Path queriesPath,Path output,Settings settings,int fork) throws Exception {
        var started=Instant.now().toString();
        var graphHash=sha256(graphPath);var queryHash=sha256(queriesPath);
        var queries=readQueries(queriesPath);
        long begin=System.nanoTime();
        var router=new CoordinateRouter(RoutingGraph.read(graphPath));
        double initMillis=(System.nanoTime()-begin)/1e6;
        var measurement=measure(queries,settings,fork,(query,first)->router.compare(
                query.startLon(),query.startLat(),query.endLon(),query.endLat(),first));
        var environment=new Environment(System.getProperty("java.version"),System.getProperty("java.vm.name"),
                System.getProperty("os.name")+" "+System.getProperty("os.version"),System.getProperty("os.arch"),
                Runtime.getRuntime().availableProcessors(),Runtime.getRuntime().maxMemory(),
                ManagementFactory.getRuntimeMXBean().getInputArguments(),
                ManagementFactory.getGarbageCollectorMXBeans().stream().map(java.lang.management.MemoryManagerMXBean::getName).toList());
        if(!graphHash.equals(sha256(graphPath)) || !queryHash.equals(sha256(queriesPath)))
            throw new IllegalStateException("Input files changed during measurement");
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),new ForkResult(fork,started,graphHash,queryHash,
                environment,initMillis,measurement.measuredGc(),summarize(measurement.samples()),measurement.samples()));
    }

    static Measurement measure(QuerySet queries,Settings settings,int fork,PairRunner runner) {
        validateQueries(queries);
        var samples=new ArrayList<Sample>();
        runRounds(queries,settings.warmupRounds(),fork,settings.seed(),runner,null);
        var gc=Gc.now();
        runRounds(queries,settings.measuredRounds(),fork,settings.seed(),runner,samples);
        return new Measurement(List.copyOf(samples),Gc.now().since(gc));
    }
    private static void runRounds(QuerySet queries,int rounds,int fork,long seed,PairRunner runner,List<Sample> samples) {
        var order=new ArrayList<Integer>();
        for(int i=0;i<queries.queries().size();i++)order.add(i);
        var random=new Random(seed+fork);
        for(int round=0;round<rounds;round++) {
            Collections.shuffle(order,random);
            for(int position=0;position<order.size();position++) {
                int queryIndex=order.get(position);
                var query=queries.queries().get(queryIndex);
                var first=(round+queryIndex+fork)%2==0?RoutingAlgorithm.DIJKSTRA:RoutingAlgorithm.ASTAR;
                var pair=runner.run(query,first);
                validatePair(pair,first);
                if(samples!=null)for(int algorithmPosition=0;algorithmPosition<2;algorithmPosition++) {
                    var result=pair.results().get(algorithmPosition);
                    samples.add(new Sample(fork,round+1,position+1,query.id(),name(result.algorithm()),algorithmPosition+1,
                            result.route().isPresent()?"Ok":"NoRoute",result.route().map(CoordinateRouter.Route::distanceMetres).orElse(null),
                            pair.snapMillis(),result.searchMillis(),result.expandedStates()));
                }
            }
            System.out.printf("  fork %d %s %d/%d%n",fork,samples==null?"warmup":"measure",round+1,rounds);
        }
    }
    static void validatePair(CoordinateRouter.ComparisonResult pair,RoutingAlgorithm first) {
        if(pair.results().size()!=2 || pair.results().get(0).algorithm()!=first || pair.results().get(1).algorithm()==first)
            throw new IllegalStateException("Invalid algorithm execution order");
        var a=pair.results().get(0).route();var b=pair.results().get(1).route();
        if(a.isPresent()!=b.isPresent())throw new IllegalStateException("Algorithms disagree on reachability");
        if(a.isPresent()) {
            var left=a.orElseThrow();var right=b.orElseThrow();
            if(Math.abs(left.distanceMetres()-right.distanceMetres())>Math.max(1e-6,Math.abs(left.distanceMetres())*1e-9)
                    || !left.start().equals(right.start()) || !left.end().equals(right.end())
                    || !pair.start().equals(left.start()) || !pair.end().equals(left.end()))
                throw new IllegalStateException("Algorithms disagree on distance or snapped waypoints");
        }
    }
    static QuerySet readQueries(Path path) throws IOException {
        var queries=JSON.readValue(path.toFile(),QuerySet.class);validateQueries(queries);return queries;
    }
    static void validateQueries(QuerySet queries) {
        if(queries==null || queries.id()==null || queries.id().isBlank() || queries.queries()==null || queries.queries().isEmpty())
            throw new IllegalArgumentException("Named, nonempty query set required");
        var ids=new HashSet<String>();
        for(var query:queries.queries()) {
            if(query==null || query.id()==null || query.id().isBlank() || !ids.add(query.id()) || query.name()==null || query.name().isBlank()
                    || !coordinate(query.startLon(),query.startLat()) || !coordinate(query.endLon(),query.endLat()))
                throw new IllegalArgumentException("Unique query IDs, names and valid coordinates required");
        }
    }
    private static boolean coordinate(double lon,double lat) {
        return Double.isFinite(lon) && Double.isFinite(lat) && Math.abs(lon)<=180 && Math.abs(lat)<=90;
    }
    static List<Summary> summarize(List<Sample> samples) {
        var summaries=new ArrayList<Summary>();
        for(var query:samples.stream().map(Sample::queryId).distinct().sorted().toList()) {
            for(var algorithm:List.of("dijkstra","astar")) {
                var selected=samples.stream().filter(s->s.queryId().equals(query) && s.algorithm().equals(algorithm)).toList();
                summaries.add(new Summary(query,algorithm,distribution(selected.stream().mapToDouble(Sample::searchMillis).toArray()),
                        distribution(selected.stream().filter(s->s.position()==1).mapToDouble(Sample::searchMillis).toArray()),
                        distribution(selected.stream().filter(s->s.position()==2).mapToDouble(Sample::searchMillis).toArray()),
                        distribution(selected.stream().mapToDouble(Sample::expandedStates).toArray())));
            }
        }
        return List.copyOf(summaries);
    }
    static Distribution distribution(double[] values) {
        if(values.length==0 || Arrays.stream(values).anyMatch(v->!Double.isFinite(v) || v<0))
            throw new IllegalArgumentException("Finite nonnegative samples required");
        var sorted=values.clone();Arrays.sort(sorted);int n=sorted.length;
        double mean=Arrays.stream(sorted).average().orElseThrow();
        double median=n%2==0?(sorted[n/2-1]+sorted[n/2])/2:sorted[n/2];
        double variance=Arrays.stream(sorted).map(value->(value-mean)*(value-mean)).average().orElseThrow();
        return new Distribution(n,sorted[0],median,sorted[(int)Math.ceil(.95*n)-1],sorted[n-1],mean,Math.sqrt(variance));
    }
    private static String name(RoutingAlgorithm algorithm) { return algorithm==RoutingAlgorithm.DIJKSTRA?"dijkstra":"astar"; }
    private static String sha256(Path path) throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");
        try(var input=Files.newInputStream(path)) {
            var buffer=new byte[65536];int read;
            while((read=input.read(buffer))!=-1)digest.update(buffer,0,read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static String git(String... arguments) {
        try {
            var command=new ArrayList<>(List.of("git"));command.addAll(List.of(arguments));
            var process=new ProcessBuilder(command).redirectErrorStream(true).start();
            var output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).trim();
            return process.waitFor()==0?output:"unavailable";
        } catch(Exception e) { return "unavailable"; }
    }
}
