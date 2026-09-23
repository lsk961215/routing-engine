package com.lsk.routing.core.osm;

import java.nio.file.Path;

/** Run separately from tests: ./gradlew :routing-core:analyzeOsm -Ppbf=... */
public final class OsmAnalysis {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected one PBF path");
        long started = System.nanoTime();
        var result = new OsmPbfReader().read(Path.of(args[0]));
        System.out.println("Nodes: " + result.nodeCount());
        System.out.println("Ways: " + result.wayCount());
        System.out.println("Relations: " + result.relationCount());
        System.out.println("Highway ways: " + result.highwayWayCount());
        System.out.println("Bounds: " + result.bounds());
        System.out.println("Highway node references: " + result.highwayNodeReferences());
        System.out.println("Unique highway node IDs: " + result.uniqueHighwayNodeIds());
        System.out.println("Highway adjacent pairs: " + result.highwaySegments());
        System.out.println("Highway ways with <2 nodes: " + result.shortHighwayWays());
        System.out.println("Restriction relations: " + result.restrictionRelations());
        result.highwayTags().forEach((key, counts) -> System.out.println("road." + key + ": " + counts));
        result.restrictionTags().forEach((key, counts) -> System.out.println("relation." + key + ": " + counts));
        System.out.println("Restriction member roles/types: " + result.restrictionMemberTypes());
        var car = new CarDataAnalysis().read(Path.of(args[0]));
        System.out.println("Car highway decisions: " + car.decisions());
        System.out.println("Car accepted ways: " + car.acceptedWays());
        System.out.println("Car unique referenced nodes: " + car.uniqueNodes());
        System.out.println("Car directed segments: " + car.directedSegments());
        System.out.println("Car restriction candidates (node via): " + car.candidateNodeVia());
        System.out.println("Car connected via-way restrictions (application deferred): " + car.connectedViaWay());
        System.out.println("Car restriction issues (overlapping): " + car.issues());
        System.out.println("Car restriction issue examples: " + car.examples());
        System.out.printf("Analysis seconds: %.3f%n", (System.nanoTime() - started) / 1e9);
    }
}
