# Optional TG Drive copy-sharing service

This is an optional backend for public links, **not a desktop client**. It stores only explicitly exported file copies. It never receives Telegram sessions or signs in to Telegram. The app's normal storage features do not require this server.

Requires Node.js 22+; there are no npm dependencies:

```sh
npm test
OWNER_TOKEN='<generate at least 32 random characters securely>' \
PUBLIC_ORIGIN='https://share.your-domain.example/' \
DATA_DIR='/private/tgdrive-shares' npm start
```

Supply a real owner token through your deployment secret manager, not source control. The example is a placeholder, not a usable credential. The listener defaults to `127.0.0.1:8080`; put a trusted TLS reverse proxy in front. Example Caddy configuration after configuring a real domain:

```caddyfile
share.your-domain.example {
    request_body { max_size 512MB }
    reverse_proxy 127.0.0.1:8080
}
```

Enter the HTTPS origin and owner token in Android Settings → Connections. HTTPS certificates are verified; there is no trust-bypass option. Set filesystem permissions and persistent storage backups appropriate to your deployment. `MAX_UPLOAD_BYTES` defaults to 512 MiB, `maxShares` to 1000, and links expire between five minutes and seven days. Expired files are removed when the service handles cleanup on API use/access; add a scheduled operational cleanup if strict unattended disk reclamation is required.

Android exports a decrypted copy when sharing a vault item, only after explicit confirmation. This service's stored copy is **not end-to-end encrypted**. Link passwords are scrypt-hashed, not stored as plaintext. Revoking a link prevents future downloads; it cannot recall a copy already downloaded or terminate every existing open download. URLs should be treated as bearer secrets. Do not publish private files by default.

POST `/api/shares` requires owner authorization and bounded content length, verifies SHA-256, and atomically commits a private file. DELETE `/api/shares/:id` revokes access. Public landing pages escape filenames and downloads use attachment disposition. There is no URL-fetch endpoint or access to arbitrary server paths. Use deployment-level abuse controls, monitoring and storage quotas before public production exposure.

Local tests use loopback HTTP to exercise the server. They do not establish deployment, DNS, a real TLS certificate, scalability or availability. No public endpoint has been deployed by the development task.
