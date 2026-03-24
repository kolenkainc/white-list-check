/// <reference types="@cloudflare/workers-types" />

import { swaggerUI } from "@hono/swagger-ui";
import { OpenAPIHono, createRoute, z } from "@hono/zod-openapi";
import type { Context } from "hono";
import { cors } from "hono/cors";

/** Секрет из Account Secrets Store (`secrets_store_secrets` в wrangler). */
type SecretsStoreBinding = { get(): Promise<string> };

export type Bindings = {
  DB: D1Database;
  /** Локально / без Secrets Store: `wrangler secret put INGEST_TOKEN` или `.dev.vars`. */
  INGEST_TOKEN?: string;
  /** Прод: привязка к секрету в Cloudflare Secrets Store (см. wrangler.toml). */
  INGEST_TOKEN_SECRET?: SecretsStoreBinding;
};

type IpStatus = "pending" | "reachable" | "unreachable";

const Ipv4Schema = z
  .string()
  .regex(
    /^(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$/,
    { message: "invalid_ipv4" },
  )
  .openapi({
    description: "IPv4-адрес",
    example: "198.51.100.1",
  });

const EnqueueBodySchema = z
  .object({
    ip: Ipv4Schema,
  })
  .openapi("EnqueueIpRequest");

const NextIpResponseSchema = z
  .object({
    ip: z.union([Ipv4Schema, z.null()]).openapi({
      description: "Следующий pending IP или null, если очередь пуста",
      example: "203.0.113.10",
    }),
  })
  .openapi("NextIpResponse");

const IpStatusSchema = z.enum(["pending", "reachable", "unreachable"]).openapi("IpStatus");

const EnqueueResponseSchema = z
  .object({
    ip: Ipv4Schema,
    status: IpStatusSchema,
  })
  .openapi("EnqueueIpResponse");

const PutStatusBodySchema = z
  .object({
    reachable: z.boolean().openapi({ example: true }),
  })
  .openapi("PutIpStatusRequest");

const PutStatusResponseSchema = z
  .object({
    ip: Ipv4Schema,
    status: IpStatusSchema,
    updatedAt: z.string().openapi({ example: "2025-03-23T12:00:00.000Z" }),
  })
  .openapi("PutIpStatusResponse");

const ErrorSchema = z
  .object({
    error: z.string(),
  })
  .openapi("Error");

const IpPathParamsSchema = z.object({
  ip: Ipv4Schema.openapi({
    param: {
      name: "ip",
      in: "path",
    },
    example: "198.51.100.1",
  }),
});

type AppContext = Context<{ Bindings: Bindings }>;

const LOG_PREFIX = "[white-list-check-api]";

function logInfo(message: string, data?: Record<string, unknown>) {
  if (data !== undefined) {
    console.log(LOG_PREFIX, message, JSON.stringify(data));
  } else {
    console.log(LOG_PREFIX, message);
  }
}

function logWarn(message: string, data?: Record<string, unknown>) {
  if (data !== undefined) {
    console.warn(LOG_PREFIX, message, JSON.stringify(data));
  } else {
    console.warn(LOG_PREFIX, message);
  }
}

function logError(message: string, err: unknown) {
  const detail = err instanceof Error ? err.message : String(err);
  console.error(LOG_PREFIX, message, detail);
}

async function resolveIngestToken(c: AppContext): Promise<string | undefined> {
  const fromStore = c.env.INGEST_TOKEN_SECRET;
  if (fromStore) {
    const v = await fromStore.get();
    if (typeof v === "string" && v.length > 0) return v;
  }
  const plain = c.env.INGEST_TOKEN;
  if (typeof plain === "string" && plain.length > 0) return plain;
  return undefined;
}

async function checkIngestAuth(c: AppContext): Promise<boolean> {
  const token = await resolveIngestToken(c);
  if (!token) return true;
  const auth = c.req.header("Authorization") ?? "";
  return auth === `Bearer ${token}`;
}

const app = new OpenAPIHono<{ Bindings: Bindings }>({
  defaultHook: (result, c) => {
    if (!result.success) {
      return c.json(
        {
          error: "validation_error",
          issues: result.error.issues.map((i) => ({
            path: i.path.join("."),
            message: i.message,
          })),
        },
        400,
      );
    }
  },
});

app.openAPIRegistry.registerComponent("securitySchemes", "BearerAuth", {
  type: "http",
  scheme: "bearer",
  description:
    "Если задан INGEST_TOKEN (wrangler secret / Secrets Store), передайте `Authorization: Bearer <токен>` на защищённые методы",
});

app.use(
  "*",
  cors({
    origin: "*",
    allowMethods: ["GET", "POST", "PUT", "OPTIONS"],
    allowHeaders: ["Content-Type", "Authorization"],
    maxAge: 86400,
  }),
);

app.use("*", async (c, next) => {
  const path = new URL(c.req.url).pathname;
  const start = Date.now();
  try {
    await next();
  } finally {
    const ms = Date.now() - start;
    logInfo("request", {
      method: c.req.method,
      path,
      status: c.res.status,
      ms,
    });
  }
});

const getNextRoute = createRoute({
  method: "get",
  path: "/api/v1/next",
  tags: ["queue"],
  summary: "Следующий непроверенный IP",
  description: "Возвращает один IP со статусом `pending` (самый ранний по created_at) или `ip: null`.",
  responses: {
    200: {
      description: "OK",
      content: { "application/json": { schema: NextIpResponseSchema } },
    },
    500: {
      description: "Ошибка D1",
      content: { "application/json": { schema: ErrorSchema } },
    },
  },
});

app.openapi(getNextRoute, async (c) => {
  try {
    const row = await c.env.DB.prepare(
      `SELECT ip FROM ip_checks WHERE status = 'pending' ORDER BY created_at ASC LIMIT 1`,
    ).first<{ ip: string }>();
    const ip = row?.ip ?? null;
    logInfo("next_ip", { ip, empty: ip === null });
    return c.json({ ip }, 200);
  } catch (e) {
    logError("next_ip_failed", e);
    return c.json({ error: String(e) }, 500);
  }
});

const enqueueResponses = {
  201: {
    description: "IP добавлен или уже был в таблице",
    content: { "application/json": { schema: EnqueueResponseSchema } },
  },
  401: {
    description: "Неверный или отсутствующий Bearer при заданном INGEST_TOKEN",
    content: { "application/json": { schema: ErrorSchema } },
  },
  500: {
    description: "Ошибка D1",
    content: { "application/json": { schema: ErrorSchema } },
  },
} as const;

const postIpsRoute = createRoute({
  method: "post",
  path: "/api/v1/ips",
  tags: ["queue"],
  summary: "Добавить IP в очередь проверки",
  description: "Вставляет IPv4 со статусом `pending` (INSERT OR IGNORE). Дубликат по `ip` не меняет строку.",
  security: [{ BearerAuth: [] }],
  request: {
    body: {
      content: { "application/json": { schema: EnqueueBodySchema } },
      required: true,
    },
  },
  responses: enqueueResponses,
});

const postCheckQueueRoute = createRoute({
  method: "post",
  path: "/api/v1/check-queue",
  tags: ["queue"],
  summary: "Добавить IP в список на проверку",
  description:
    "То же, что `POST /api/v1/ips`: поставить адрес в очередь проверки (pending). Удобный отдельный URL для интеграций.",
  security: [{ BearerAuth: [] }],
  request: {
    body: {
      content: { "application/json": { schema: EnqueueBodySchema } },
      required: true,
    },
  },
  responses: enqueueResponses,
});

const enqueueRoutes = [postIpsRoute, postCheckQueueRoute] as const;
for (const route of enqueueRoutes) {
  app.openapi(route, async (c) => {
    if (!(await checkIngestAuth(c))) {
      logWarn("enqueue_unauthorized", { path: new URL(c.req.url).pathname });
      return c.json({ error: "unauthorized" }, 401);
    }
    const { ip } = c.req.valid("json");
    const now = new Date().toISOString();
    try {
      const insertResult = await c.env.DB.prepare(
        `INSERT OR IGNORE INTO ip_checks (ip, status, created_at, updated_at) VALUES (?, 'pending', ?, ?)`,
      )
        .bind(ip, now, now)
        .run();

      const row = await c.env.DB.prepare(`SELECT status FROM ip_checks WHERE ip = ?`)
        .bind(ip)
        .first<{ status: string }>();

      const status = (row?.status as IpStatus) ?? "pending";
      const inserted = (insertResult.meta?.changes ?? 0) > 0;
      logInfo("enqueue", {
        ip,
        inserted,
        status,
        path: new URL(c.req.url).pathname,
      });
      return c.json({ ip, status }, 201);
    } catch (e) {
      logError("enqueue_failed", e);
      return c.json({ error: String(e) }, 500);
    }
  });
}

const putIpRoute = createRoute({
  method: "put",
  path: "/api/v1/ips/{ip}",
  tags: ["queue"],
  summary: "Записать результат проверки IP",
  description: "Обновляет `reachable` / `unreachable` для существующей строки.",
  security: [{ BearerAuth: [] }],
  request: {
    params: IpPathParamsSchema,
    body: {
      content: { "application/json": { schema: PutStatusBodySchema } },
      required: true,
    },
  },
  responses: {
    200: {
      description: "OK",
      content: { "application/json": { schema: PutStatusResponseSchema } },
    },
    400: {
      description: "Некорректный IP в пути (редко — после валидации Zod)",
      content: { "application/json": { schema: ErrorSchema } },
    },
    404: {
      description: "IP не найден",
      content: { "application/json": { schema: ErrorSchema } },
    },
    401: {
      description: "Неверный или отсутствующий Bearer при заданном INGEST_TOKEN",
      content: { "application/json": { schema: ErrorSchema } },
    },
    500: {
      description: "Ошибка D1",
      content: { "application/json": { schema: ErrorSchema } },
    },
  },
});

app.openapi(putIpRoute, async (c) => {
  if (!(await checkIngestAuth(c))) {
    logWarn("put_status_unauthorized", { path: new URL(c.req.url).pathname });
    return c.json({ error: "unauthorized" }, 401);
  }
  const { ip } = c.req.valid("param");
  const { reachable } = c.req.valid("json");
  const status: IpStatus = reachable ? "reachable" : "unreachable";
  const updatedAt = new Date().toISOString();
  try {
    const result = await c.env.DB.prepare(`UPDATE ip_checks SET status = ?, updated_at = ? WHERE ip = ?`)
      .bind(status, updatedAt, ip)
      .run();

    const changes = result.meta?.changes ?? 0;
    if (changes === 0) {
      logWarn("put_status_not_found", { ip });
      return c.json({ error: "not_found" }, 404);
    }
    logInfo("put_status", { ip, status, updatedAt });
    return c.json({ ip, status, updatedAt }, 200);
  } catch (e) {
    logError("put_status_failed", e);
    return c.json({ error: String(e) }, 500);
  }
});

const ROBOTS_TXT_DENY_ALL = "User-agent: *\nDisallow: /\n";

app.get("/robots.txt", (c) =>
  c.text(ROBOTS_TXT_DENY_ALL, 200, {
    "Content-Type": "text/plain; charset=utf-8",
    "Cache-Control": "public, max-age=86400",
  }),
);

app.get("/", (c) =>
  c.json({
    service: "white-list-check-api",
    storage: "d1",
    openapi: "/openapi.json",
    swagger: "/swagger",
  }),
);

app.get("/swagger", swaggerUI({ url: "/openapi.json" }));

app.doc31("/openapi.json", {
  openapi: "3.1.0",
  info: {
    title: "White list IP registry API",
    version: "0.2.0",
    description:
      "Очередь IPv4 для проверки с мобильного клиента (D1). Спецификация и валидация запросов через Zod + @hono/zod-openapi.",
  },
  servers: [{ url: "/", description: "Текущий Worker" }],
});

app.notFound((c) => {
  logWarn("not_found", { path: new URL(c.req.url).pathname });
  return c.json({ error: "not_found" }, 404);
});

export default app;

