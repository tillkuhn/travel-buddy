package net.timafe.travel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Controller
public class TravelController {

    private static final Logger log = LoggerFactory.getLogger(TravelController.class);

    private final TravelService travelService;

    public TravelController(TravelService travelService) {
        this.travelService = travelService;
    }

    @GetMapping("/")
    public String form(Model model) {
        model.addAttribute("activities", List.of(
                "Beaches", "Culture", "Cycling", "Diving", "Hiking", "Shopping", "Skiing", "Wildlife", "Party"
        ));
        return "index";
    }

    @PostMapping("/plan")
    public String plan(
            @RequestParam String region,
            @RequestParam(required = false) List<String> activities,
            @RequestParam(defaultValue = "") String additionalWishes,
            Model model
    ) {
        try {
            TravelResult result = travelService.plan(new TravelRequest(region, activities, additionalWishes));
            model.addAttribute("suggestion", result.suggestion());
            model.addAttribute("region", result.region());
            model.addAttribute("activities", result.activities());
            model.addAttribute("additionalWishes", result.additionalWishes());
            model.addAttribute("travelSeason", result.travelSeason());
            return "result";
        } catch (Exception e) {
            // The LLM/gateway call can fail after Embabel's own retry loop is exhausted (e.g.
            // gateway unreachable, DNS failure, timeout). Surface a friendly error instead of
            // leaving the user stuck on the loading overlay or seeing a bare stack trace.
            log.error("Failed to generate travel suggestion for region={}", region, e);
            model.addAttribute("region", region);
            model.addAttribute("activities", activities == null ? List.of() : activities);
            model.addAttribute("additionalWishes", additionalWishes);
            return "error";
        }
    }
}
