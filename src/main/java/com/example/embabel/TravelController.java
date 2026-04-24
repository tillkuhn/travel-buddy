package com.example.embabel;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Controller
public class TravelController {

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
        TravelResult result = travelService.plan(new TravelRequest(region, activities, additionalWishes));
        model.addAttribute("suggestion", result.suggestion());
        model.addAttribute("prompt", result.prompt());
        model.addAttribute("region", result.region());
        model.addAttribute("activities", result.activities());
        model.addAttribute("additionalWishes", result.additionalWishes());
        return "result";
    }
}
