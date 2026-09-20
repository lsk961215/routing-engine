package com.lsk.routing.core.osm;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

class OsmPbfReaderTest {

    @Test
    void loadPbf() throws IOException {

        Path pbfPath = Path.of(
                "../data/processed/seoul-routing.osm.pbf"
        );

        OsmPbfReader reader = new OsmPbfReader();

        OsmLoadResult result = reader.read(pbfPath);

        System.out.println("Nodes         : " + result.nodeCount());
        System.out.println("Ways          : " + result.wayCount());
        System.out.println("Relations     : " + result.relationCount());
        System.out.println("Highway Ways  : " + result.highwayWayCount());
    }
}