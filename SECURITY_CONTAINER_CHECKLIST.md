# Container Security Checklist (Pre-Ship)

Use this checklist before pushing a new image or deploying to cloud.

## Release Metadata
- Date:
- Version/Tag:
- Reviewer:
- Environment (`staging` or `prod`):

## 1) Build and Image Hardening
- [ ] Base images are trusted and current (`maven` builder and Java runtime).
- [ ] Image is rebuilt from clean source (no local secrets copied into build context).
- [ ] `.dockerignore` excludes sensitive/local files (`.env*`, keys, logs, IDE files).
- [ ] Final image contains only runtime artifact (JAR), not source code or build cache.
- [ ] Image vulnerability scan completed and high/critical findings are resolved or formally accepted.

Evidence:

## 2) Container Runtime Hardening
- [ ] Container runs as non-root user (`USER` set in `Dockerfile`).
- [ ] No unnecessary Linux capabilities (`--cap-drop=ALL` or platform equivalent).
- [ ] Privilege escalation is blocked (`no-new-privileges`).
- [ ] Filesystem is read-only where supported; writable temp paths are explicit.
- [ ] Only required port is exposed (`8080`).

Evidence:

## 3) Secrets and Sensitive Config
- [ ] No plaintext credentials in Git history, image layers, or deployment command history.
- [ ] Database and JWT secrets come from secret manager at runtime.
- [ ] Secret names/versions are reviewed and mapped to correct env vars.
- [ ] Secret rotation plan exists and previous versions are disabled after rollout.

Evidence:

## 4) Cloud Deployment Controls (Cloud Run)
- [ ] Deployment avoids public exposure unless explicitly required (`--no-allow-unauthenticated`).
- [ ] Runtime service account follows least privilege.
- [ ] Service account has only required secret access (`roles/secretmanager.secretAccessor` scope minimized).
- [ ] Region and platform settings are correct for target environment.
- [ ] Rollback command/version is prepared and documented.

Evidence:

## 5) Database and Network Security
- [ ] PostgreSQL is not publicly open; access is restricted by network controls.
- [ ] TLS/in-transit encryption is enabled where supported.
- [ ] Inbound rules allow only required sources.
- [ ] Outbound egress from app is limited to required destinations (DB, SMTP, APIs).

Evidence:

## 6) Application-Level Production Safety
- [ ] Production JWT secrets are strong and not using defaults.
- [ ] CORS origins are limited to trusted frontend domains.
- [ ] Debug/verbose logging is reviewed to avoid sensitive data leakage.
- [ ] Health endpoints and admin endpoints are reviewed for exposure.

Evidence:

## 7) Verification and Sign-Off
- [ ] Container starts successfully with production-style env configuration.
- [ ] Smoke tests pass (auth, protected API, DB connectivity).
- [ ] Monitoring/alerts are enabled for startup errors and auth failures.
- [ ] Final approval recorded.

Approver Name:
Approver Signature/Initials:
Approval Date:

## Suggested Minimum Commands (manual runbook)
```powershell
# Build image
docker build -t ibbackend:1.0.0 .

# Example local hardened run
docker run --rm -p 8080:8080 `
  --read-only `
  --tmpfs /tmp:rw,noexec,nosuid,size=64m `
  --cap-drop=ALL `
  --security-opt=no-new-privileges:true `
  --env-file .env.local `
  ibbackend:1.0.0
```

