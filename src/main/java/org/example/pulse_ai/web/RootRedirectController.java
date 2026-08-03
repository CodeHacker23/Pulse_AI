package org.example.pulse_ai.web;

import org.example.pulse_ai.config.PulseAdminProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class RootRedirectController {

    private final PulseAdminProperties adminProperties;

    public RootRedirectController(PulseAdminProperties adminProperties) {
        this.adminProperties = adminProperties;
    }

    @GetMapping("/")
    public String root() {
        if (!adminProperties.isWebEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return "redirect:/admin/";
    }

    @GetMapping({"/admin", "/admin/"})
    public String admin() {
        if (!adminProperties.isWebEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return "forward:/admin/index.html";
    }
}
