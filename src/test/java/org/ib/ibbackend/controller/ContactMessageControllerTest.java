package org.ib.ibbackend.controller;

import org.ib.ibbackend.dto.ContactMessageRequest;
import org.ib.ibbackend.service.ContactMessageService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ContactMessageControllerTest {
    private final ContactMessageService service = mock(ContactMessageService.class);
    private final ContactMessageController controller = new ContactMessageController(service);

    @Test
    void acceptsValidContactMessage() {
        ContactMessageRequest request = request();

        var response = controller.submit(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsEntry("success", true);
        verify(service).send(request);
    }

    @Test
    void rejectsFilledHoneypotWithoutSendingEmail() {
        ContactMessageRequest request = request();
        request.setWebsite("spam");

        assertThatThrownBy(() -> controller.submit(request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(service);
    }

    private ContactMessageRequest request() {
        ContactMessageRequest request = new ContactMessageRequest();
        request.setName("Shop Owner");
        request.setEmail("owner@example.com");
        request.setMessage("I need help.");
        return request;
    }
}
