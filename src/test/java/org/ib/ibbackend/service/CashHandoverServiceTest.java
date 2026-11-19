package org.ib.ibbackend.service;

import org.ib.ibbackend.dto.CashHandoverConfirmationRequest;
import org.ib.ibbackend.dto.CashHandoverRequest;
import org.ib.ibbackend.dto.CashHandoverResolutionRequest;
import org.ib.ibbackend.dto.CashCollectionTicketRequest;
import org.ib.ibbackend.exception.DocumentStorageNotConfiguredException;
import org.ib.ibbackend.entity.CashCollectionPaymentProof;
import org.ib.ibbackend.entity.CashHandover;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.CashHandoverRepository;
import org.ib.ibbackend.repository.CashCollectionPaymentProofRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CashHandoverServiceTest {
    private final CashHandoverRepository handoverRepository = mock(CashHandoverRepository.class);
    private final CashCollectionPaymentProofRepository paymentProofRepository =
            mock(CashCollectionPaymentProofRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final TenantDocumentEncryptionService documentEncryptionService =
            mock(TenantDocumentEncryptionService.class);
    private final CashHandoverService service =
            new CashHandoverService(
                    handoverRepository, paymentProofRepository, userRepository,
                    auditLogService, documentEncryptionService);

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void submitsTenantScopedHandoverAndReservesDeclaredAmount() {
        User sender = user(11L, 7, "billing_manager");
        User recipient = user(12L, 7, "store_manager");
        authenticate(sender);
        when(userRepository.findTenantUserRecordByIdForUpdate(11L, 7)).thenReturn(Optional.of(sender));
        when(userRepository.findTenantUserRecordByIdForUpdate(12L, 7)).thenReturn(Optional.of(recipient));
        when(userRepository.findByIdAndTenant_Id(12L, 7)).thenReturn(Optional.of(recipient));
        when(handoverRepository.findByTenant_IdAndSender_IdAndIdempotencyKey(7, 11L, "transfer-1"))
                .thenReturn(Optional.empty());
        when(handoverRepository.getAvailableCash(7, 11L)).thenReturn(new BigDecimal("100.00"));
        when(handoverRepository.saveAndFlush(any(CashHandover.class))).thenAnswer(invocation -> {
            CashHandover handover = invocation.getArgument(0);
            handover.setId(31L);
            return handover;
        });

        var result = service.create(createRequest("transfer-1", "40.00"));

        assertEquals(31L, result.get("id"));
        assertEquals("PENDING", result.get("status"));
        assertEquals(new BigDecimal("60.00"), result.get("senderAvailableCash"));
        verify(auditLogService).success(any(), any(), any());
        verifyNoInteractions(paymentProofRepository, documentEncryptionService);
    }

    @Test
    void rejectsSubmissionThatExceedsAvailableCash() {
        User sender = user(11L, 7, "billing_manager");
        User recipient = user(12L, 7, "store_manager");
        authenticate(sender);
        when(userRepository.findTenantUserRecordByIdForUpdate(11L, 7)).thenReturn(Optional.of(sender));
        when(userRepository.findTenantUserRecordByIdForUpdate(12L, 7)).thenReturn(Optional.of(recipient));
        when(userRepository.findByIdAndTenant_Id(12L, 7)).thenReturn(Optional.of(recipient));
        when(handoverRepository.findByTenant_IdAndSender_IdAndIdempotencyKey(7, 11L, "transfer-1"))
                .thenReturn(Optional.empty());
        when(handoverRepository.getAvailableCash(7, 11L)).thenReturn(new BigDecimal("20.00"));

        assertThrows(ResponseStatusException.class,
                () -> service.create(createRequest("transfer-1", "40.00")));
        verify(handoverRepository, never()).saveAndFlush(any(CashHandover.class));
    }

    @Test
    void submitsCashCollectionTicketOnlyToStoreAdminAndReservesCash() {
        User sender = user(11L, 7, "billing_manager");
        User admin = user(12L, 7, "store_admin");
        authenticate(sender);
        when(userRepository.findTenantUserRecordByIdForUpdate(11L, 7)).thenReturn(Optional.of(sender));
        when(userRepository.findTenantUserRecordByIdForUpdate(12L, 7)).thenReturn(Optional.of(admin));
        when(handoverRepository.findByTenant_IdAndSender_IdAndIdempotencyKey(7, 11L, "transfer-1"))
                .thenReturn(Optional.empty());
        when(handoverRepository.getAvailableCash(7, 11L)).thenReturn(new BigDecimal("100.00"));
        when(handoverRepository.saveAndFlush(any(CashHandover.class))).thenAnswer(invocation -> {
            CashHandover ticket = invocation.getArgument(0);
            ticket.setId(31L);
            return ticket;
        });

        var result = service.createCollectionTicket(createRequest("transfer-1", "40.00"));

        assertEquals(31L, result.get("id"));
        assertEquals(11L, result.get("senderUserId"));
        assertEquals(12L, result.get("recipientUserId"));
        assertEquals("PENDING", result.get("status"));
        assertEquals(new BigDecimal("60.00"), result.get("senderAvailableCash"));
        verify(auditLogService).success(any(), any(), any());
        verifyNoInteractions(paymentProofRepository, documentEncryptionService);
    }

    @Test
    void resolvesNamedStoreAdminRecipientWithinTenant() {
        User sender = user(11L, 7, "billing_manager");
        User admin = user(12L, 7, "store_admin");
        authenticate(sender);
        when(userRepository.findActiveStoreAdminIdsByTenantAndIdentifier(7, "vaishali"))
                .thenReturn(List.of(12L));
        when(userRepository.findTenantUserRecordByIdForUpdate(11L, 7)).thenReturn(Optional.of(sender));
        when(userRepository.findTenantUserRecordByIdForUpdate(12L, 7)).thenReturn(Optional.of(admin));
        when(handoverRepository.findByTenant_IdAndSender_IdAndIdempotencyKey(7, 11L, "transfer-name"))
                .thenReturn(Optional.empty());
        when(handoverRepository.getAvailableCash(7, 11L)).thenReturn(new BigDecimal("100.00"));
        when(handoverRepository.saveAndFlush(any(CashHandover.class))).thenAnswer(invocation -> {
            CashHandover ticket = invocation.getArgument(0);
            ticket.setId(32L);
            return ticket;
        });

        CashCollectionTicketRequest request = new CashCollectionTicketRequest();
        request.setRecipientUserId("vaishali");
        request.setDeclaredAmount(new BigDecimal("40.00"));
        request.setIdempotencyKey("transfer-name");

        var result = service.createCollectionTicket(request);

        assertEquals(12L, result.get("recipientUserId"));
        assertEquals("PENDING", result.get("status"));
        verify(userRepository).findActiveStoreAdminIdsByTenantAndIdentifier(7, "vaishali");
    }

    @Test
    void rejectsAmbiguousOrUnknownNamedStoreAdminRecipient() {
        User sender = user(11L, 7, "billing_manager");
        authenticate(sender);
        when(userRepository.findActiveStoreAdminIdsByTenantAndIdentifier(7, "vaishali"))
                .thenReturn(List.of());

        CashCollectionTicketRequest request = new CashCollectionTicketRequest();
        request.setRecipientUserId("vaishali");
        request.setDeclaredAmount(new BigDecimal("40.00"));
        request.setIdempotencyKey("transfer-name");

        assertThrows(ResponseStatusException.class, () -> service.createCollectionTicket(request));
        verify(handoverRepository, never()).saveAndFlush(any(CashHandover.class));
    }

    @Test
    void rejectsCashCollectionTicketToNonAdmin() {
        User sender = user(11L, 7, "billing_manager");
        User recipient = user(12L, 7, "store_manager");
        authenticate(sender);
        when(userRepository.findTenantUserRecordByIdForUpdate(11L, 7)).thenReturn(Optional.of(sender));
        when(userRepository.findTenantUserRecordByIdForUpdate(12L, 7)).thenReturn(Optional.of(recipient));
        when(handoverRepository.findByTenant_IdAndSender_IdAndIdempotencyKey(7, 11L, "transfer-1"))
                .thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class,
                () -> service.createCollectionTicket(createRequest("transfer-1", "40.00")));
        verify(handoverRepository, never()).saveAndFlush(any(CashHandover.class));
    }

    @Test
    void submitsElectronicTicketWithEncryptedProofWithoutReservingCash() {
        User sender = user(11L, 7, "billing_manager");
        User admin = user(12L, 7, "store_admin");
        authenticate(sender);
        when(userRepository.findTenantUserRecordByIdForUpdate(11L, 7)).thenReturn(Optional.of(sender));
        when(userRepository.findTenantUserRecordByIdForUpdate(12L, 7)).thenReturn(Optional.of(admin));
        when(handoverRepository.findByTenant_IdAndSender_IdAndIdempotencyKey(7, 11L, "upi-1"))
                .thenReturn(Optional.empty());
        when(documentEncryptionService.encrypt(any(byte[].class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(handoverRepository.saveAndFlush(any(CashHandover.class))).thenAnswer(invocation -> {
            CashHandover ticket = invocation.getArgument(0);
            ticket.setId(41L);
            return ticket;
        });

        CashCollectionTicketRequest request = new CashCollectionTicketRequest();
        request.setRecipientUserId("12");
        request.setDeclaredAmount(new BigDecimal("40.00"));
        request.setIdempotencyKey("upi-1");
        request.setPaymentMethod("UPI");
        byte[] screenshot = new byte[] {
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01
        };
        MockMultipartFile proof = new MockMultipartFile(
                "paymentProof", "receipt.png", "application/octet-stream", screenshot);

        var result = service.createCollectionTicket(request, proof);

        assertEquals("UPI", result.get("paymentMethod"));
        assertEquals(true, result.get("paymentProofAvailable"));
        verify(handoverRepository, never()).getAvailableCash(7, 11L);
        verify(handoverRepository).saveAndFlush(org.mockito.ArgumentMatchers.argThat(ticket ->
                "UPI".equals(ticket.getPaymentMethod())
                        && ticket.getPaymentProofSha256() != null));
        verify(paymentProofRepository).save(org.mockito.ArgumentMatchers.argThat(storedProof ->
                "image/png".equals(storedProof.getContentType())
                        && storedProof.getEncryptedContent() != null
                        && storedProof.getSha256() != null));
        verify(documentEncryptionService).encrypt(screenshot);
    }

    @Test
    void requiresPaymentProofForElectronicTicket() {
        User sender = user(11L, 7, "billing_manager");
        authenticate(sender);
        CashCollectionTicketRequest request = new CashCollectionTicketRequest();
        request.setRecipientUserId("12");
        request.setDeclaredAmount(new BigDecimal("40.00"));
        request.setIdempotencyKey("upi-no-proof");
        request.setPaymentMethod("CARD");

        assertThrows(ResponseStatusException.class, () -> service.createCollectionTicket(request));
        verify(handoverRepository, never()).saveAndFlush(any(CashHandover.class));
    }

    @Test
    void rejectsPaymentProofWhoseContentDoesNotMatchAnImage() {
        User sender = user(11L, 7, "billing_manager");
        authenticate(sender);
        CashCollectionTicketRequest request = new CashCollectionTicketRequest();
        request.setRecipientUserId("12");
        request.setDeclaredAmount(new BigDecimal("40.00"));
        request.setIdempotencyKey("upi-invalid-proof");
        request.setPaymentMethod("UPI");
        MockMultipartFile proof = new MockMultipartFile(
                "paymentProof", "receipt.png", "image/png", "not an image".getBytes());

        assertThrows(ResponseStatusException.class,
                () -> service.createCollectionTicket(request, proof));
        verify(handoverRepository, never()).saveAndFlush(any(CashHandover.class));
    }

    @Test
    void rejectsForgedImageContentTypeEvenWhenTheFileSignatureIsValid() {
        User sender = user(11L, 7, "billing_manager");
        authenticate(sender);
        CashCollectionTicketRequest request = new CashCollectionTicketRequest();
        request.setRecipientUserId("12");
        request.setDeclaredAmount(new BigDecimal("40.00"));
        request.setIdempotencyKey("upi-forged-mime");
        request.setPaymentMethod("UPI");
        MockMultipartFile proof = new MockMultipartFile(
                "paymentProof", "receipt.jpg", "image/jpeg",
                new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});

        assertThrows(ResponseStatusException.class,
                () -> service.createCollectionTicket(request, proof));
        verify(handoverRepository, never()).saveAndFlush(any(CashHandover.class));
    }

    @Test
    void rejectsEmptyPaymentProof() {
        User sender = user(11L, 7, "billing_manager");
        authenticate(sender);
        CashCollectionTicketRequest request = new CashCollectionTicketRequest();
        request.setRecipientUserId("12");
        request.setDeclaredAmount(new BigDecimal("40.00"));
        request.setIdempotencyKey("upi-empty-proof");
        request.setPaymentMethod("UPI");
        MockMultipartFile proof = new MockMultipartFile(
                "paymentProof", "receipt.png", "image/png", new byte[0]);

        assertThrows(ResponseStatusException.class,
                () -> service.createCollectionTicket(request, proof));
        verify(handoverRepository, never()).saveAndFlush(any(CashHandover.class));
    }

    @Test
    void rejectsPaymentProofLargerThanFiveMebibytes() {
        User sender = user(11L, 7, "billing_manager");
        authenticate(sender);
        CashCollectionTicketRequest request = new CashCollectionTicketRequest();
        request.setRecipientUserId("12");
        request.setDeclaredAmount(new BigDecimal("40.00"));
        request.setIdempotencyKey("upi-large-proof");
        request.setPaymentMethod("UPI");
        MockMultipartFile proof = new MockMultipartFile(
                "paymentProof", "receipt.png", "image/png", new byte[5 * 1024 * 1024 + 1]);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.createCollectionTicket(request, proof));

        assertEquals(413, exception.getStatusCode().value());
        verify(handoverRepository, never()).saveAndFlush(any(CashHandover.class));
    }

    @Test
    void reportsMissingEncryptionConfigurationBeforeCreatingTicket() {
        User sender = user(11L, 7, "billing_manager");
        User admin = user(12L, 7, "store_admin");
        authenticate(sender);
        when(userRepository.findTenantUserRecordByIdForUpdate(11L, 7)).thenReturn(Optional.of(sender));
        when(userRepository.findTenantUserRecordByIdForUpdate(12L, 7)).thenReturn(Optional.of(admin));
        when(handoverRepository.findByTenant_IdAndSender_IdAndIdempotencyKey(7, 11L, "upi-no-key"))
                .thenReturn(Optional.empty());
        TenantDocumentEncryptionService unconfiguredEncryption =
                new TenantDocumentEncryptionService("");
        CashHandoverService unconfiguredService = new CashHandoverService(
                handoverRepository, paymentProofRepository, userRepository,
                auditLogService, unconfiguredEncryption);
        CashCollectionTicketRequest request = new CashCollectionTicketRequest();
        request.setRecipientUserId("12");
        request.setDeclaredAmount(new BigDecimal("40.00"));
        request.setIdempotencyKey("upi-no-key");
        request.setPaymentMethod("UPI");
        MockMultipartFile proof = new MockMultipartFile(
                "paymentProof", "receipt.png", "image/png",
                new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});

        assertThrows(DocumentStorageNotConfiguredException.class,
                () -> unconfiguredService.createCollectionTicket(request, proof));
        verify(handoverRepository, never()).saveAndFlush(any(CashHandover.class));
        verify(paymentProofRepository, never()).save(any(CashCollectionPaymentProof.class));
    }

    @Test
    void repeatedElectronicSubmissionReturnsExistingTicketWithoutEncryptingOrSavingProof() throws Exception {
        User sender = user(11L, 7, "billing_manager");
        User admin = user(12L, 7, "store_admin");
        authenticate(sender);
        byte[] screenshot = new byte[] {
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        };
        String proofHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(screenshot));
        CashHandover prior = new CashHandover();
        prior.setId(42L);
        prior.setSender(sender);
        prior.setRecipient(admin);
        prior.setDeclaredAmount(new BigDecimal("40.00"));
        prior.setIdempotencyKey("upi-retry");
        prior.setStatus("PENDING");
        prior.setPaymentMethod("UPI");
        prior.setPaymentProofSha256(proofHash);
        when(userRepository.findTenantUserRecordByIdForUpdate(11L, 7)).thenReturn(Optional.of(sender));
        when(userRepository.findTenantUserRecordByIdForUpdate(12L, 7)).thenReturn(Optional.of(admin));
        when(handoverRepository.findByTenant_IdAndSender_IdAndIdempotencyKey(7, 11L, "upi-retry"))
                .thenReturn(Optional.of(prior));

        CashCollectionTicketRequest request = new CashCollectionTicketRequest();
        request.setRecipientUserId("12");
        request.setDeclaredAmount(new BigDecimal("40.00"));
        request.setIdempotencyKey("upi-retry");
        request.setPaymentMethod("UPI");

        var result = service.createCollectionTicket(request,
                new MockMultipartFile("paymentProof", "receipt.png", "image/png", screenshot));

        assertEquals(42L, result.get("id"));
        verifyNoInteractions(paymentProofRepository, documentEncryptionService);
        verify(handoverRepository, never()).saveAndFlush(any(CashHandover.class));
    }

    @Test
    void allowsTicketParticipantToRetrievePaymentProof() {
        User admin = user(12L, 7, "store_admin");
        CashHandover ticket = handover("PENDING");
        ticket.setRecipient(admin);
        ticket.setPaymentMethod("CARD");
        ticket.setPaymentProofSha256("abc");
        CashCollectionPaymentProof storedProof = new CashCollectionPaymentProof();
        storedProof.setContentType("image/jpeg");
        storedProof.setEncryptedContent(new byte[] {1, 2, 3});
        storedProof.setSha256("abc");
        byte[] screenshot = new byte[] {4, 5, 6};
        authenticate(admin);
        when(handoverRepository.findVisibleCollectionTicketById(31L, 7, 12L, true))
                .thenReturn(Optional.of(ticket));
        when(paymentProofRepository.findById(31L)).thenReturn(Optional.of(storedProof));
        when(documentEncryptionService.decrypt(storedProof.getEncryptedContent())).thenReturn(screenshot);

        CashHandoverService.PaymentProof result = service.getCollectionTicketPaymentProof(31L);

        assertEquals("image/jpeg", result.contentType());
        assertArrayEquals(screenshot, result.content());
    }

    @Test
    void storeAdminCannotSubmitCollectionTicket() {
        User admin = user(11L, 7, "store_admin");
        User recipient = user(12L, 7, "store_admin");
        authenticate(admin);
        when(userRepository.findTenantUserRecordByIdForUpdate(11L, 7)).thenReturn(Optional.of(admin));
        when(userRepository.findTenantUserRecordByIdForUpdate(12L, 7)).thenReturn(Optional.of(recipient));

        assertThrows(AccessDeniedException.class,
                () -> service.createCollectionTicket(createRequest("transfer-1", "40.00")));
        verify(handoverRepository, never()).saveAndFlush(any(CashHandover.class));
    }

    @Test
    void cashCollectionTicketCountAddsAcceptedCashToStoreAdminBalance() {
        User admin = user(12L, 7, "store_admin");
        CashHandover ticket = handover("PENDING");
        ticket.setRecipient(admin);
        authenticate(admin);
        when(handoverRepository.findByIdAndTenantIdForUpdate(31L, 7)).thenReturn(Optional.of(ticket));
        when(handoverRepository.findByTenant_IdAndReceiverConfirmationKey(7, "confirm-1"))
                .thenReturn(Optional.empty());
        when(handoverRepository.saveAndFlush(any(CashHandover.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(handoverRepository.getAvailableCash(7, 12L)).thenReturn(new BigDecimal("140.00"));

        var result = service.countCollectionTicket(31L, confirmation("confirm-1", "40.00"));

        assertEquals("ACCEPTED", result.get("status"));
        assertEquals(new BigDecimal("140.00"), result.get("storeCashAvailable"));
        verify(handoverRepository).saveAndFlush(ticket);
    }

    @Test
    void resolvedCashCollectionTicketAddsResolvedAmountToStoreAdminBalance() {
        User admin = user(12L, 7, "store_admin");
        CashHandover ticket = handover("DISPUTED");
        ticket.setRecipient(admin);
        ticket.setReceiverCountedAmount(new BigDecimal("35.00"));
        ticket.setRecipientConfirmedAt(java.time.Instant.now());
        authenticate(admin);
        when(handoverRepository.findByIdAndTenantIdForUpdate(31L, 7)).thenReturn(Optional.of(ticket));
        when(handoverRepository.findByTenant_IdAndResolutionIdempotencyKey(7, "resolve-1"))
                .thenReturn(Optional.empty());
        when(handoverRepository.saveAndFlush(any(CashHandover.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(handoverRepository.getAvailableCash(7, 12L)).thenReturn(new BigDecimal("135.00"));

        var result = service.resolveCollectionTicket(31L, resolution("resolve-1", "35.00"));

        assertEquals("RESOLVED", result.get("status"));
        assertEquals(new BigDecimal("135.00"), result.get("storeCashAvailable"));
        verify(handoverRepository).saveAndFlush(ticket);
    }

    @Test
    void mismatchedCashCollectionTicketCountRemainsDisputedAndDoesNotAddStoreCash() {
        User admin = user(12L, 7, "store_admin");
        CashHandover ticket = handover("PENDING");
        ticket.setRecipient(admin);
        authenticate(admin);
        when(handoverRepository.findByIdAndTenantIdForUpdate(31L, 7)).thenReturn(Optional.of(ticket));
        when(handoverRepository.findByTenant_IdAndReceiverConfirmationKey(7, "confirm-2"))
                .thenReturn(Optional.empty());
        when(handoverRepository.saveAndFlush(any(CashHandover.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.countCollectionTicket(31L, confirmation("confirm-2", "35.00"));

        assertEquals("DISPUTED", result.get("status"));
        verify(handoverRepository, never()).getAvailableCash(7, 12L);
    }

    @Test
    void receiverAcceptanceIsIdempotentAndMovesHandoverToAccepted() {
        User recipient = user(12L, 7, "store_manager");
        CashHandover handover = handover("PENDING");
        authenticate(recipient);
        when(handoverRepository.findByIdAndTenantIdForUpdate(31L, 7)).thenReturn(Optional.of(handover));
        when(handoverRepository.findByTenant_IdAndReceiverConfirmationKey(7, "confirm-1"))
                .thenReturn(Optional.empty());
        when(handoverRepository.save(any(CashHandover.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.confirm(31L, confirmation("confirm-1", "40.00"));
        var repeated = service.confirm(31L, confirmation("confirm-1", "40.00"));

        assertEquals("ACCEPTED", result.get("status"));
        assertEquals("ACCEPTED", repeated.get("status"));
        assertEquals(new BigDecimal("40.00"), handover.getReceiverCountedAmount());
        verify(handoverRepository).save(handover);
    }

    @Test
    void receiverCountMismatchMovesHandoverToDisputed() {
        User recipient = user(12L, 7, "store_manager");
        CashHandover handover = handover("PENDING");
        authenticate(recipient);
        when(handoverRepository.findByIdAndTenantIdForUpdate(31L, 7)).thenReturn(Optional.of(handover));
        when(handoverRepository.findByTenant_IdAndReceiverConfirmationKey(7, "confirm-2"))
                .thenReturn(Optional.empty());
        when(handoverRepository.save(any(CashHandover.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.confirm(31L, confirmation("confirm-2", "35.00"));

        assertEquals("DISPUTED", result.get("status"));
        assertEquals(new BigDecimal("35.00"), handover.getReceiverCountedAmount());
        verify(handoverRepository).save(handover);
    }

    @Test
    void disputedHandoverRequiresAdminResolutionAndKeepsResolvedAmountBounded() {
        User admin = user(13L, 7, "store_admin");
        CashHandover handover = handover("DISPUTED");
        handover.setReceiverCountedAmount(new BigDecimal("35.00"));
        handover.setRecipientConfirmedAt(java.time.Instant.now());
        authenticate(admin);
        when(handoverRepository.findByIdAndTenantIdForUpdate(31L, 7)).thenReturn(Optional.of(handover));
        when(handoverRepository.findByTenant_IdAndResolutionIdempotencyKey(7, "resolve-1"))
                .thenReturn(Optional.empty());
        when(handoverRepository.save(any(CashHandover.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThrows(ResponseStatusException.class, () -> service.resolve(31L, resolution("resolve-1", "40.00")));

        var resolved = service.resolve(31L, resolution("resolve-1", "35.00"));
        assertEquals("RESOLVED", resolved.get("status"));
        assertEquals(new BigDecimal("35.00"), handover.getResolvedAmount());
        assertEquals(13L, handover.getResolvedByUserId());
        verify(handoverRepository).save(handover);
    }

    @Test
    void rejectsReceiverFromOtherTenant() {
        User user = user(12L, 7, "store_manager");
        CashHandover handover = handover("PENDING");
        handover.setRecipient(user(12L, 8, "store_manager"));
        stubHandoverLocks(handover);
        authenticate(user);
        when(handoverRepository.findByIdAndTenantIdForUpdate(31L, 7)).thenReturn(Optional.of(handover));

        assertThrows(AccessDeniedException.class,
                () -> service.confirm(31L, confirmation("confirm-1", "40.00")));
        verify(handoverRepository, never()).save(any(CashHandover.class));
    }

    private User user(long id, int tenantId, String role) {
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        User user = new User();
        user.setId(id);
        user.setTenant(tenant);
        user.setRole(role);
        user.setEmail("user" + id + "@example.com");
        user.setOwnerName("User " + id);
        return user;
    }

    private CashHandover handover(String status) {
        CashHandover handover = new CashHandover();
        Tenant tenant = new Tenant();
        tenant.setId(7);
        handover.setId(31L);
        handover.setTenant(tenant);
        handover.setSender(user(11L, 7, "billing_manager"));
        handover.setRecipient(user(12L, 7, "store_manager"));
        handover.setDeclaredAmount(new BigDecimal("40.00"));
        handover.setStatus(status);
        handover.setIdempotencyKey("transfer-1");
        handover.setSenderConfirmedAt(java.time.Instant.now());
        when(userRepository.findTenantUserRecordByIdForUpdate(11L, 7))
                .thenReturn(Optional.of(handover.getSender()));
        when(userRepository.findTenantUserRecordByIdForUpdate(12L, 7))
                .thenReturn(Optional.of(handover.getRecipient()));
        return handover;
    }

    private void stubHandoverLocks(CashHandover handover) {
        when(userRepository.findTenantUserRecordByIdForUpdate(11L, 7))
                .thenReturn(Optional.of(handover.getSender()));
        when(userRepository.findTenantUserRecordByIdForUpdate(12L, 7))
                .thenReturn(Optional.of(user(12L, 7, "store_manager")));
    }

    private CashHandoverRequest createRequest(String key, String amount) {
        CashHandoverRequest request = new CashHandoverRequest();
        request.setRecipientUserId(12L);
        request.setDeclaredAmount(new BigDecimal(amount));
        request.setIdempotencyKey(key);
        return request;
    }

    private CashHandoverConfirmationRequest confirmation(String key, String amount) {
        CashHandoverConfirmationRequest request = new CashHandoverConfirmationRequest();
        request.setCountedAmount(new BigDecimal(amount));
        request.setIdempotencyKey(key);
        return request;
    }

    private CashHandoverResolutionRequest resolution(String key, String amount) {
        CashHandoverResolutionRequest request = new CashHandoverResolutionRequest();
        request.setResolvedAmount(new BigDecimal(amount));
        request.setIdempotencyKey(key);
        request.setResolutionNote("Reviewed cash count");
        return request;
    }

    private void authenticate(User user) {
        TenantContext.setCurrentTenant((long) user.getTenant().getId());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.getEmail(), null, List.of()));
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
    }
}
