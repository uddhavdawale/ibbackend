package org.ib.ibbackend.service;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import org.ib.ibbackend.config.FirebaseMfaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Service
public class FirebaseTokenVerifierService {
    private static final Logger log = LoggerFactory.getLogger(FirebaseTokenVerifierService.class);
    private static final String FIREBASE_APP_NAME = "ibbackend-firebase-mfa";

    private final FirebaseMfaProperties properties;
    private volatile FirebaseApp firebaseApp;

    public FirebaseTokenVerifierService(FirebaseMfaProperties properties) {
        this.properties = properties;
    }

    public boolean isEnabled() {
        return properties != null && properties.isEnabled();
    }

    public FirebaseIdentity verifyIdToken(String firebaseIdToken) throws FirebaseAuthException {
        if (!isEnabled()) {
            throw new IllegalStateException("Firebase MFA is disabled");
        }
        if (firebaseIdToken == null || firebaseIdToken.isBlank()) {
            throw new IllegalArgumentException("firebaseIdToken is required");
        }

        FirebaseToken decodedToken = firebaseAuth().verifyIdToken(firebaseIdToken, true);
        if (properties.isRequiredEmailVerified() && decodedToken.getEmail() != null && !decodedToken.isEmailVerified()) {
            throw new IllegalArgumentException("Firebase email is not verified");
        }

        Object phoneClaim = decodedToken.getClaims() == null ? null : decodedToken.getClaims().get("phone_number");
        String phoneNumber = phoneClaim == null ? null : phoneClaim.toString();

        return new FirebaseIdentity(
                decodedToken.getUid(),
                decodedToken.getEmail(),
                phoneNumber,
                decodedToken.isEmailVerified()
        );
    }

    private FirebaseAuth firebaseAuth() {
        if (firebaseApp == null) {
            synchronized (this) {
                if (firebaseApp == null) {
                    firebaseApp = initializeFirebaseApp();
                }
            }
        }
        return FirebaseAuth.getInstance(firebaseApp);
    }

    private FirebaseApp initializeFirebaseApp() {
        for (FirebaseApp app : FirebaseApp.getApps()) {
            if (FIREBASE_APP_NAME.equals(app.getName())) {
                return app;
            }
        }

        try {
            FirebaseOptions.Builder builder = FirebaseOptions.builder();
            String projectId = trimToNull(properties.getProjectId());
            if (projectId != null) {
                builder.setProjectId(projectId);
            }

            builder.setCredentials(resolveCredentials());
            log.info("Initializing Firebase app for OTP token verification");
            return FirebaseApp.initializeApp(builder.build(), FIREBASE_APP_NAME);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to initialize Firebase Admin SDK", e);
        }
    }

    private GoogleCredentials resolveCredentials() throws IOException {
        String base64Json = trimToNull(properties.getCredentialsJsonBase64());
        if (base64Json != null) {
            byte[] decoded = Base64.getDecoder().decode(base64Json.getBytes(StandardCharsets.UTF_8));
            try (InputStream stream = new ByteArrayInputStream(decoded)) {
                return GoogleCredentials.fromStream(stream);
            }
        }

        String credentialsPath = trimToNull(properties.getCredentialsPath());
        if (credentialsPath != null) {
            try (InputStream stream = new FileInputStream(credentialsPath)) {
                return GoogleCredentials.fromStream(stream);
            }
        }

        // In managed cloud environments this uses workload identity/application default credentials.
        return GoogleCredentials.getApplicationDefault();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record FirebaseIdentity(String uid, String email, String phoneNumber, boolean emailVerified) {
    }
}
