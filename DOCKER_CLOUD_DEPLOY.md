# Docker and Cloud Deployment

## Deploy to Railway

The Railway configuration builds the existing `Dockerfile`. The container
activates the Spring `railway` profile when it detects Railway runtime
environment variables; local Docker runs keep the default profile. Add a
PostgreSQL service to the Railway project, then
reference its `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, and `PGPASSWORD`
variables in the backend service (use Railway variable references if the
database is a separate service). The profile assembles the JDBC URL from these
values.

Set these backend service variables in Railway:

- `JWT_ACCESS_SECRET` and `JWT_REFRESH_SECRET`: independent, randomly generated
  secrets of at least 32 bytes.
- `APP_CORS_ALLOWED_ORIGINS`: comma-separated frontend origins, without paths or
  trailing slashes.
- `APP_FRONTEND_BASE_URL`: the deployed frontend URL.
- `TENANT_DOCUMENT_ENCRYPTION_KEY`: a stable, backed-up base64-encoded 32-byte
  key if tenant document encryption is used. Payment screenshots use this
  server-side key and are encrypted in PostgreSQL; cash-only ticket submissions
  do not require it. Keep the same key across backend instances and deployments.
- `APP_BOOTSTRAP_DEMO_USER_EMAIL` and `APP_BOOTSTRAP_DEMO_USER_PASSWORD`.
- `APP_BOOTSTRAP_SYSTEM_ADMIN_ONE_EMAIL` and
  `APP_BOOTSTRAP_SYSTEM_ADMIN_ONE_PASSWORD`.
- `APP_BOOTSTRAP_SYSTEM_ADMIN_TWO_EMAIL` and
  `APP_BOOTSTRAP_SYSTEM_ADMIN_TWO_PASSWORD`.

Set any mail, Firebase, or login-encryption variables required by the features
you enable. Keep credentials and key values in Railway's variable manager, not
in source control. The Railway profile enables bootstrap-account provisioning
by default and requires all six bootstrap variables above. After verifying the
accounts were created, set `APP_BOOTSTRAP_ENABLED=false` in Railway to disable
provisioning on later deployments.

Local development continues to use `application.properties` and the local
PostgreSQL default (`localhost:5432/inventory_billing`). To use another local
database, set `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and
`SPRING_DATASOURCE_PASSWORD`.

## Build local image
```powershell
docker build -t ibbackend:1.0.0 .
```

## Run local container (safer defaults)
```powershell
docker run --rm -p 8080:8080 `
  --read-only `
  --tmpfs /tmp:rw,noexec,nosuid,size=64m `
  --cap-drop=ALL `
  --security-opt=no-new-privileges:true `
  --env-file .env.local `
  ibbackend:1.0.0
```

Use a local `.env.local` file (do not commit it):
```env
SPRING_DATASOURCE_URL=jdbc:postgresql://<db-host>:5432/inventory_billing
SPRING_DATASOURCE_USERNAME=<db-user>
SPRING_DATASOURCE_PASSWORD=<db-password>
JWT_ACCESS_SECRET=<long-random-access-secret>
JWT_REFRESH_SECRET=<long-random-refresh-secret>
TENANT_DOCUMENT_ENCRYPTION_KEY=<base64-encoded-32-byte-key>
```

Generate `TENANT_DOCUMENT_ENCRYPTION_KEY` with a secure random generator (for example, `openssl rand -base64 32`) and keep it in a secret manager. The key must remain stable and backed up; tenant documents and payment proofs cannot be decrypted without the key. An absent or invalid key returns `503` with code `DOCUMENT_STORAGE_NOT_CONFIGURED` when a proof is uploaded or retrieved.

## Push to Docker Hub
```powershell
docker tag ibbackend:1.0.0 <dockerhub-user>/ibbackend:1.0.0
docker push <dockerhub-user>/ibbackend:1.0.0
```

## Deploy to Google Cloud Run with Secret Manager
Create secrets once:
```powershell
gcloud secrets create spring-datasource-url --replication-policy="automatic"
gcloud secrets create spring-datasource-username --replication-policy="automatic"
gcloud secrets create spring-datasource-password --replication-policy="automatic"
gcloud secrets create jwt-access-secret --replication-policy="automatic"
gcloud secrets create jwt-refresh-secret --replication-policy="automatic"
gcloud secrets create tenant-document-encryption-key --replication-policy="automatic"
```
[secure_otp_implementation_report.md](../../../../Users/uddha/Downloads/secure_otp_implementation_report.md)
Deploy using secret references (no inline secret values):
```powershell
gcloud run deploy ibbackend `
  --image docker.io/<dockerhub-user>/ibbackend:1.0.0 `
  --platform managed `
  --region asia-south1 `
  --port 8080 `
  --no-allow-unauthenticated `
  --set-secrets SPRING_DATASOURCE_URL=spring-datasource-url:latest,SPRING_DATASOURCE_USERNAME=spring-datasource-username:latest,SPRING_DATASOURCE_PASSWORD=spring-datasource-password:latest,JWT_ACCESS_SECRET=jwt-access-secret:latest,JWT_REFRESH_SECRET=jwt-refresh-secret:latest,TENANT_DOCUMENT_ENCRYPTION_KEY=tenant-document-encryption-key:latest
```

## Notes
- Grant minimal IAM roles to the Cloud Run service account (`roles/secretmanager.secretAccessor` only as needed).
- Keep PostgreSQL private (private IP/VPC connector or restricted network path), never open it publicly.
- Rotate JWT and DB secrets regularly and roll new versions in Secret Manager.
- Optionally set `JAVA_TOOL_OPTIONS` for memory tuning (for example `-Xms256m -Xmx512m`).
- Run the pre-ship checklist in `SECURITY_CONTAINER_CHECKLIST.md` before every production rollout.
- For cross-site frontend/backend deployments, keep `APP_REFRESH_COOKIE_SECURE=true` and `APP_REFRESH_COOKIE_SAME_SITE=None`. Set `APP_CORS_ALLOWED_ORIGINS` to the exact deployed frontend origin(s), without paths or wildcard domains.
- Refresh-token family schema changes are applied by Flyway. The refresh cookie is HttpOnly and scoped to `/api/auth`; the service also expires the legacy root-scoped cookie during rollout.

## Optional first-deployment bootstrap data

The application can create a `Demo Org` tenant (code `DEMO`), a `store_admin` user (`demo.admin`), and two `system_admin` users (`system.admin.one` and `system.admin.two`) during startup. The bootstrap is disabled by default. It is idempotent: existing accounts are left unchanged, while conflicting identities or incomplete configuration stop startup with an error.
All three seeded accounts are associated with `Demo Org`, which avoids creating extra tenants when the application initializes tenantless users during login.

For the first deployment to a fresh database, create Secret Manager secrets for the six email/password values below. Use unique email addresses and passwords of at least 12 characters. Do not put password values in source control or directly in deployment commands.

```powershell
gcloud secrets create bootstrap-demo-user-email --replication-policy="automatic"
gcloud secrets create bootstrap-demo-user-password --replication-policy="automatic"
gcloud secrets create bootstrap-system-admin-one-email --replication-policy="automatic"
gcloud secrets create bootstrap-system-admin-one-password --replication-policy="automatic"
gcloud secrets create bootstrap-system-admin-two-email --replication-policy="automatic"
gcloud secrets create bootstrap-system-admin-two-password --replication-policy="automatic"
```

Add a secret version containing a value to each secret using the Secret Manager console or your approved secret-injection process before updating the service.

After the initial deployment, attach the bootstrap secrets and enable one-time provisioning:

```powershell
gcloud run services update ibbackend `
  --region asia-south1 `
  --update-env-vars APP_BOOTSTRAP_ENABLED=true `
  --update-secrets APP_BOOTSTRAP_DEMO_USER_EMAIL=bootstrap-demo-user-email:latest,APP_BOOTSTRAP_DEMO_USER_PASSWORD=bootstrap-demo-user-password:latest,APP_BOOTSTRAP_SYSTEM_ADMIN_ONE_EMAIL=bootstrap-system-admin-one-email:latest,APP_BOOTSTRAP_SYSTEM_ADMIN_ONE_PASSWORD=bootstrap-system-admin-one-password:latest,APP_BOOTSTRAP_SYSTEM_ADMIN_TWO_EMAIL=bootstrap-system-admin-two-email:latest,APP_BOOTSTRAP_SYSTEM_ADMIN_TWO_PASSWORD=bootstrap-system-admin-two-password:latest
```

After confirming the bootstrap accounts were created, disable provisioning so future deployments do not attempt bootstrap setup:

```powershell
gcloud run services update ibbackend `
  --region asia-south1 `
  --update-env-vars APP_BOOTSTRAP_ENABLED=false
```

The database stores password hashes, so changing a Secret Manager value does not reset an existing account's password. Keep the bootstrap credentials protected until each user changes their password through the application's supported process, then remove the bootstrap secret mappings.
