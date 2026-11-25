package org.ib.ibbackend.service;

import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.CustomerDirectoryRepository;
import org.ib.ibbackend.repository.CustomerDirectoryRepository.DirectoryPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerDirectoryServiceTest {
    private final CustomerDirectoryRepository repository = mock(CustomerDirectoryRepository.class);
    private final CustomerDirectoryService service = new CustomerDirectoryService(repository);

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void usesAuthenticatedTenantAndCapsPageSize() {
        TenantContext.setCurrentTenant(73L);
        when(repository.findPage(73L, 2, CustomerDirectoryService.MAX_PAGE_SIZE, "smith"))
                .thenReturn(new DirectoryPage(List.of(), 101, 4));

        var response = service.getPage(2, 500, " smith ");

        assertEquals(2, response.page().number());
        assertEquals(CustomerDirectoryService.MAX_PAGE_SIZE, response.page().size());
        assertEquals(101, response.page().totalElements());
        assertEquals(3, response.page().totalPages());
        assertEquals(4, response.page().dueCustomerCount());
        verify(repository).findPage(73L, 2, CustomerDirectoryService.MAX_PAGE_SIZE, "smith");
    }

    @Test
    void rejectsInvalidPagingAndMissingTenant() {
        TenantContext.setCurrentTenant(73L);
        assertThrows(ResponseStatusException.class, () -> service.getPage(-1, 25, null));
        assertThrows(ResponseStatusException.class, () -> service.getPage(0, 0, null));

        TenantContext.clear();
        assertThrows(AccessDeniedException.class, () -> service.getPage(0, 25, null));
    }
}
