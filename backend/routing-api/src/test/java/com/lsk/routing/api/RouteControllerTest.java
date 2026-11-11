package com.lsk.routing.api;

import com.lsk.routing.api.controller.RouteController;
import com.lsk.routing.api.service.RoutingService;
import com.lsk.routing.core.graph.RoutingGraph;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RouteControllerTest {
    private MockMvc api() {
        var factory=new StaticListableBeanFactory();
        factory.addBean("graph",new RoutingGraph(new long[]{1,2},
                new double[]{37,37.001},new double[]{127,127},new int[]{0,1,1},
                new int[]{1},new long[]{1},new double[]{111},new long[0]));
        return MockMvcBuilders.standaloneSetup(new RouteController(new RoutingService(factory.getBeanProvider(RoutingGraph.class)))).build();
    }
    @Test void serializesBothAlgorithmsAndSharedSnapping() throws Exception {
        api().perform(get("/api/compare?startLon=127&startLat=37&endLon=127&endLat=37.001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waypoints.length()").value(2))
                .andExpect(jsonPath("$.snapMillis").isNumber())
                .andExpect(jsonPath("$.results.length()").value(2))
                .andExpect(jsonPath("$.results[0].algorithm").value("dijkstra"))
                .andExpect(jsonPath("$.results[1].algorithm").value("astar"))
                .andExpect(jsonPath("$.results[1].routes[0].distance").value(111))
                .andExpect(jsonPath("$.results[1].metrics.expandedStates").isNumber())
                .andExpect(jsonPath("$.results[0].metrics.inspectedEdges").isNumber())
                .andExpect(jsonPath("$.results[1].metrics.maxQueueSize").isNumber())
                .andExpect(jsonPath("$.results[0].metrics.allocatedBytes").isNumber())
                .andExpect(jsonPath("$.results[1].metrics.allocatedBytes").isNumber());
    }
    @Test void unreachableComparisonIsAResultWhileSingleRouteKeeps404() throws Exception {
        var query="?startLon=127&startLat=37.001&endLon=127&endLat=37";
        api().perform(get("/api/compare"+query)).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].code").value("NoRoute"))
                .andExpect(jsonPath("$.results[1].routes").isEmpty())
                .andExpect(jsonPath("$.results[1].metrics.allocatedBytes").isNumber());
        api().perform(get("/api/route"+query)).andExpect(status().isNotFound());
    }
    @Test void rejectsMissingOrInvalidCoordinates() throws Exception {
        api().perform(get("/api/compare")).andExpect(status().isBadRequest());
        api().perform(get("/api/compare?startLon=NaN&startLat=37&endLon=127&endLat=37"))
                .andExpect(status().isBadRequest());
        api().perform(get("/api/compare?startLon=128&startLat=38&endLon=127&endLat=37"))
                .andExpect(status().isUnprocessableContent());
    }
    @Test void altCanBeSelectedAndComparedWithoutChangingTheDefaultPair() throws Exception {
        var mvc=api();var query="?startLon=127&startLat=37&endLon=127&endLat=37.001&algorithm=alt";
        mvc.perform(get("/api/route"+query)).andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics.algorithm").value("alt"))
                .andExpect(jsonPath("$.routes[0].distance").value(111));
        mvc.perform(get("/api/compare"+query)).andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(2))
                .andExpect(jsonPath("$.results[0].algorithm").value("dijkstra"))
                .andExpect(jsonPath("$.results[1].algorithm").value("alt"))
                .andExpect(jsonPath("$.results[1].routes[0].distance").value(111))
                .andExpect(jsonPath("$.results[1].metrics.allocatedBytes").isNumber());
        mvc.perform(get("/api/compare"+query.replace("algorithm=alt","algorithm=dijkstra"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/route"+query.replace("startLat=37&", "startLat=37.001&").replace("endLat=37.001&", "endLat=37&")))
                .andExpect(status().isNotFound());
    }
    @Test void bidirectionalSupportsSinglePairedAndUnreachableRequests() throws Exception {
        var mvc=api();var query="?startLon=127&startLat=37&endLon=127&endLat=37.001&algorithm=bidirectional";
        mvc.perform(get("/api/route"+query)).andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics.algorithm").value("bidirectional"))
                .andExpect(jsonPath("$.routes[0].distance").value(111));
        mvc.perform(get("/api/compare"+query)).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].algorithm").value("dijkstra"))
                .andExpect(jsonPath("$.results[1].algorithm").value("bidirectional"))
                .andExpect(jsonPath("$.results[1].routes[0].distance").value(111))
                .andExpect(jsonPath("$.results[1].metrics.inspectedEdges").isNumber())
                .andExpect(jsonPath("$.results[1].metrics.maxQueueSize").isNumber());
        mvc.perform(get("/api/compare"+query.replace("startLat=37&","startLat=37.001&").replace("endLat=37.001&","endLat=37&")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.results[1].code").value("NoRoute"));
    }
    @Test void singleRouteIncludesAllocationAndVirtualThreadSerializesUnavailableAsNull() throws Exception {
        var path="/api/route?startLon=127&startLat=37&endLon=127&endLat=37.001";
        var mvc=api();
        mvc.perform(get(path)).andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics.allocatedBytes").isNumber())
                .andExpect(jsonPath("$.metrics.inspectedEdges").isNumber())
                .andExpect(jsonPath("$.metrics.maxQueueSize").isNumber());
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(()->mvc.perform(get(path)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.metrics.allocatedBytes").value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("\"allocatedBytes\":null")))).get();
        }
    }
}
