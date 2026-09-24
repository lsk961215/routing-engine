package com.lsk.routing.api;

import com.lsk.routing.api.controller.ExcludedRoadController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ExcludedRoadControllerTest {
    @TempDir Path dir;
    private ExcludedRoadController controller() throws Exception {
        var path=dir.resolve("roads.json");
        Files.writeString(path,"""
            {"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"LineString","coordinates":[[126,37],[128,37]]},
              "properties":{"wayId":"123","name":"test","category":"direct","reasons":["MISSING_FROM"],"nodeIds":["7"],"relationIds":["8"]}}
            ]}
            """);
        return new ExcludedRoadController(new ObjectMapper(),path.toString());
    }
    @Test void viewportSelectsCrossingRoadAndPreservesReasons() throws Exception {
        var controller=controller();
        var data=controller.query(126.9,36.9,127.1,37.1);
        assertEquals(1,data.features().size());
        assertEquals("123",data.features().getFirst().properties().wayId());
        assertEquals(java.util.List.of("8"),data.features().getFirst().properties().relationIds());
        assertTrue(controller.query(129,36,130,38).features().isEmpty());
    }
    @Test void rejectsInvalidBoundsAndReportsMissingDataset() throws Exception {
        var controller=controller();
        assertEquals(400,assertThrows(ResponseStatusException.class,()->controller.query(128,37,126,38)).getStatusCode().value());
        assertEquals(400,assertThrows(ResponseStatusException.class,()->controller.query(Double.NaN,37,128,38)).getStatusCode().value());
        var absent=new ExcludedRoadController(new ObjectMapper(),"");
        assertEquals(503,assertThrows(ResponseStatusException.class,()->absent.query(126,37,128,38)).getStatusCode().value());
    }
}
