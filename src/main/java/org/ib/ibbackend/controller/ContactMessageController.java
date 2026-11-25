package org.ib.ibbackend.controller;

import jakarta.validation.Valid;
import org.ib.ibbackend.dto.ContactMessageRequest;
import org.ib.ibbackend.service.ContactMessageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/contact")
public class ContactMessageController {
    private final ContactMessageService contactMessageService;

    public ContactMessageController(ContactMessageService contactMessageService) {
        this.contactMessageService = contactMessageService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> submit(@Valid @RequestBody ContactMessageRequest request) {
        if (request.getWebsite() != null && !request.getWebsite().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message could not be accepted");
        }

        contactMessageService.send(request);
        return ResponseEntity.accepted().body(Map.of(
                "success", true,
                "message", "Your message has been received. We'll reply to the email address you provided."
        ));
    }
}
