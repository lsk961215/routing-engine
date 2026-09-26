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
                .andExpect(jsonPath("$.results[1].metrics.expandedStates").isNumber());
    }
    @Test void unreachableComparisonIsAResultWhileSingleRouteKeeps404() throws Exception {
        var query="?startLon=127&startLat=37.001&endLon=127&endLat=37";
        api().perform(get("/api/compare"+query)).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].code").value("NoRoute"))
                .andExpect(jsonPath("$.results[1].routes").isEmpty());
        api().perform(get("/api/route"+query)).andExpect(status().isNotFound());
    }
    @Test void rejectsMissingOrInvalidCoordinates() throws Exception {
        api().perform(get("/api/compare")).andExpect(status().isBadRequest());
        api().perform(get("/api/compare?startLon=NaN&startLat=37&endLon=127&endLat=37"))
                .andExpect(status().isBadRequest());
        api().perform(get("/api/compare?startLon=128&startLat=38&endLon=127&endLat=37"))
                .andExpect(status().isUnprocessableContent());
    }
}
