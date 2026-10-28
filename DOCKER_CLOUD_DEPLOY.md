# Docker and Cloud Deployment

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
```

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
  --set-secrets SPRING_DATASOURCE_URL=spring-datasource-url:latest,SPRING_DATASOURCE_USERNAME=spring-datasource-username:latest,SPRING_DATASOURCE_PASSWORD=spring-datasource-password:latest,JWT_ACCESS_SECRET=jwt-access-secret:latest,JWT_REFRESH_SECRET=jwt-refresh-secret:latest
```

## Notes
- Grant minimal IAM roles to the Cloud Run service account (`roles/secretmanager.secretAccessor` only as needed).
- Keep PostgreSQL private (private IP/VPC connector or restricted network path), never open it publicly.
- Rotate JWT and DB secrets regularly and roll new versions in Secret Manager.
- Optionally set `JAVA_TOOL_OPTIONS` for memory tuning (for example `-Xms256m -Xmx512m`).
- Run the pre-ship checklist in `SECURITY_CONTAINER_CHECKLIST.md` before every production rollout.
