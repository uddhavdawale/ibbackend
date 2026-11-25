package org.ib.ibbackend.service;

import org.ib.ibbackend.dto.CustomerDirectoryPageResponse;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.CustomerDirectoryRepository;
import org.ib.ibbackend.repository.CustomerDirectoryRepository.DirectoryPage;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CustomerDirectoryService {
    public static final int DEFAULT_PAGE_SIZE = 25;
    public static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_SEARCH_LENGTH = 100;

    private final CustomerDirectoryRepository repository;

    public CustomerDirectoryService(CustomerDirectoryRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public CustomerDirectoryPageResponse getPage(int page, int requestedSize, String search) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new AccessDeniedException("Access denied");
        }
        if (page < 0 || requestedSize < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page must be non-negative and size positive");
        }
        if (search != null && search.length() > MAX_SEARCH_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Search is too long");
        }

        int size = Math.min(requestedSize, MAX_PAGE_SIZE);
        String normalizedSearch = search == null || search.isBlank() ? null : search.trim();
        DirectoryPage result = repository.findPage(tenantId, page, size, normalizedSearch);
        int totalPages = result.totalElements() == 0
                ? 0
                : (int) Math.min(Integer.MAX_VALUE, ((result.totalElements() - 1) / size) + 1);
        return new CustomerDirectoryPageResponse(
                result.content(),
                new CustomerDirectoryPageResponse.PageMetadata(
                        page, size, result.totalElements(), totalPages, result.dueCustomerCount()));
    }
}
