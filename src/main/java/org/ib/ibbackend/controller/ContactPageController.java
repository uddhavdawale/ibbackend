package org.ib.ibbackend.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ContactPageController {
    @GetMapping("/contact-us")
    public String contactPage() {
        return "redirect:/contact-us.html";
    }
}
