# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **Documentación completa:** https://github.com/criscubillos/crypto-state-docs — consultar antes de tomar decisiones de diseño.

## Comandos de desarrollo

```bash
# Levantar (requiere variables de entorno)
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Build
mvn clean package -DskipTests

# Tests
mvn test
mvn test -Dtest=NombreDelTest   # test individual

# Verificar compilación sin empaquetar
mvn compile
```

El servidor corre en **puerto 9001**. PostgreSQL en `localhost:5432`, Memcached en `localhost:11211`.

## Variables de entorno (desarrollo)

Copiar `.env.example` a `.env` y completar. Las variables mínimas para levantar en dev son:
- `DB_USER` / `DB_PASSWORD`
- `JWT_SECRET` (mínimo 32 caracteres)
- `ENCRYPTION_KEY` (32 bytes en base64)

Las demás (Google OAuth, Cloudflare, Resend) pueden dejarse con valores ficticios en dev.

## Estructura de paquetes

```
com.cryptostate.backend/
├── auth/           Autenticación: User, JWT, Google OAuth, email verification
├── exchange/       Conexiones a exchanges, SyncJob, adaptadores (Binance/BingX/Bybit)
├── transaction/    NormalizedTransaction, TransactionService
├── taxes/          TaxCalculator (Chile FIFO), TaxCalculatorRegistry
├── admin/          AdminController (requiere ROLE_ADMIN)
├── dashboard/      Dashboard (PRO) y consultas LLM Ollama
├── common/
│   ├── annotation/ @RequiresPlan — AOP para restricción por plan
│   ├── aspect/     PlanCheckAspect
│   ├── exception/  ApiException, GlobalExceptionHandler
│   ├── filter/     JwtAuthFilter, RateLimitFilter (Bucket4j)
│   └── util/       MemcachedService, EncryptionService (AES-256-GCM)
└── config/         SecurityConfig, CorsConfig, MemcachedConfig, AppProperties
```

## Convenciones críticas

- **API keys de exchanges:** siempre encriptar con `EncryptionService` antes de persistir; nunca retornarlas en responses
- **Logout efectivo:** `MemcachedService.deleteUserSessions(userId)` invalida sesión y cache
- **Cache:** usar `MemcachedService` (no inyectar `MemcachedClient` directamente); TTL corto para datos de mercado
- **Plan check:** usar `@RequiresPlan(Plan.PRO)` en endpoints PRO — el aspecto valida la authority `PLAN_*` del JWT
- **Cloudflare Queue:** mensajes mínimos `{uid, eid, t}` — sin payloads grandes
- **Agregar exchange:** implementar `ExchangeAdapter` con `@Component` — el `ExchangeAdapterRegistry` lo detecta automáticamente
- **Agregar país (impuestos):** implementar `TaxCalculator` con `@Component` — el `TaxCalculatorRegistry` lo detecta
