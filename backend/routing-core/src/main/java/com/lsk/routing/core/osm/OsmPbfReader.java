package com.lsk.routing.core.osm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import de.topobyte.osm4j.core.model.iface.EntityContainer;
import de.topobyte.osm4j.pbf.seq.PbfIterator;

public class OsmPbfReader {

    public OsmLoadResult read(Path pbfPath) throws IOException {

        if (!Files.exists(pbfPath)) {
            throw new IllegalArgumentException(
                    "PBF file not found: " + pbfPath
            );
        }

        long nodeCount = 0;
        long wayCount = 0;
        long relationCount = 0;
        long highwayWayCount = 0;

        try (InputStream input = Files.newInputStream(pbfPath)) {
            PbfIterator iterator = new PbfIterator(input, false);

            while (iterator.hasNext()) {
                EntityContainer container = iterator.next();

//                System.out.println(container.getType());
                switch (container.getType()) {
                    case Node:
                        nodeCount++;
                        break;

                    case Way:
                        wayCount++;
                        break;

                    case Relation:
                        relationCount++;
                        break;
                }
            }
        }

        return new OsmLoadResult(
                nodeCount,
                wayCount,
                relationCount,
                highwayWayCount
        );
    }
}