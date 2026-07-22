package net.timafe.travel;

import net.timafe.travel.gateway.GatewaySecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Web-layer tests for {@link TravelController} using {@code @WebMvcTest} — no real server, no
 * LLM/gateway calls; {@link TravelService} is mocked so only the controller's routing, form
 * binding, and model/view/error-handling behaviour is exercised.
 */
@WebMvcTest(TravelController.class)
@Import(GatewaySecurityConfig.class)
class TravelControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TravelService travelService;

    @Test
    void formRendersIndexWithActivityOptions() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"))
                .andExpect(model().attributeExists("activities"));
    }

    @Test
    void planRendersResultViewWithSuggestionOnSuccess() throws Exception {
        TravelResult result = new TravelResult(
                "Visit Prague!", "Europe", List.of("Culture", "Hiking"), "", "spring");
        when(travelService.plan(any())).thenReturn(result);

        mockMvc.perform(post("/plan")
                        .param("region", "Europe")
                        .param("activities", "Culture", "Hiking")
                        .param("additionalWishes", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("result"))
                .andExpect(model().attribute("suggestion", "Visit Prague!"))
                .andExpect(model().attribute("region", "Europe"))
                .andExpect(model().attribute("travelSeason", "spring"));

        verify(travelService).plan(new TravelRequest("Europe", List.of("Culture", "Hiking"), ""));
    }

    @Test
    void planRendersErrorViewWhenServiceThrows() throws Exception {
        when(travelService.plan(any())).thenThrow(new RuntimeException("gateway unreachable"));

        mockMvc.perform(post("/plan")
                        .param("region", "Antarctica")
                        .param("additionalWishes", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("error"))
                .andExpect(model().attribute("region", "Antarctica"))
                .andExpect(model().attribute("activities", List.of()));
    }

    @Test
    void planWithoutActivitiesPassesNullThrough() throws Exception {
        TravelResult result = new TravelResult("Anywhere relaxing", "Southeast Asia", List.of(), "", "");
        when(travelService.plan(any())).thenReturn(result);

        mockMvc.perform(post("/plan")
                        .param("region", "Southeast Asia")
                        .param("additionalWishes", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("result"));

        verify(travelService).plan(new TravelRequest("Southeast Asia", null, ""));
    }
}
