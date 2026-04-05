package org.ib.ibbackend.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

public class GSTController
{
    @GetMapping("/returns")
    public List<GSTRReturn> getReturns(@RequestParam String period) { /* Query DB */
        return List.of();
    }

    @PostMapping("/generate/{type}")
    public ResponseEntity<String> generate(@PathVariable String type, @RequestParam String period) {
        // Compute from invoices, save JSON, return success
        return null;
    }
}
