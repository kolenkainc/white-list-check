/// <reference types="@cloudflare/workers-types" />

import openapi from "../openapi.json";

export interface Env {
  DB: D1Database;
  INGEST_TOKEN?: string;
}

type IpStatus = "pending" | "reachable" | "unreachable";

function corsHeaders(): HeadersInit {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, POST, PUT, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, Authorization",
    "Access-Control-Max-Age": "86400",
  };
}

function json(data: unknown, init: ResponseInit = {}): Response {
  const headers = new Headers(init.headers);
  headers.set("Content-Type", "application/json; charset=utf-8");
  Object.entries(corsHeaders()).forEach(([k, v]) => headers.set(k, v));
  return new Response(JSON.stringify(data), { ...init, headers });
}

function isIpv4(ip: string): boolean {
  const parts = ip.split(".");
  if (parts.length !== 4) return false;
  return parts.every((p) => {
    if (!/^\d{1,3}$/.test(p)) return false;
    const n = Number(p);
    return n >= 0 && n <= 255;
  });
}

export default {
  async fetch(request: Request, env: Env, _ctx: ExecutionContext): Promise<Response> {
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders() });
    }

    const url = new URL(request.url);
    const path = url.pathname.replace(/\/$/, "") || "/";

    if (path === "/openapi.json" && request.method === "GET") {
      return json(openapi);
    }

    if (path === "/api/v1/next" && request.method === "GET") {
      try {
        const row = await env.DB.prepare(
          `SELECT ip FROM ip_checks WHERE status = 'pending' ORDER BY created_at ASC LIMIT 1`,
        ).first<{ ip: string }>();
        return json({ ip: row?.ip ?? null });
      } catch (e) {
        return json({ error: String(e) }, { status: 500 });
      }
    }

    if (path === "/api/v1/ips" && request.method === "POST") {
      const token = env.INGEST_TOKEN;
      if (token) {
        const auth = request.headers.get("Authorization") || "";
        const expected = `Bearer ${token}`;
        if (auth !== expected) {
          return json({ error: "unauthorized" }, { status: 401 });
        }
      }
      let body: { ip?: string };
      try {
        body = (await request.json()) as { ip?: string };
      } catch {
        return json({ error: "invalid_json" }, { status: 400 });
      }
      const raw = body.ip?.trim() ?? "";
      if (!isIpv4(raw)) {
        return json({ error: "invalid_ipv4" }, { status: 400 });
      }
      try {
        const now = new Date().toISOString();
        await env.DB.prepare(
          `INSERT OR IGNORE INTO ip_checks (ip, status, created_at, updated_at) VALUES (?, 'pending', ?, ?)`,
        )
          .bind(raw, now, now)
          .run();

        const row = await env.DB.prepare(`SELECT status FROM ip_checks WHERE ip = ?`)
          .bind(raw)
          .first<{ status: string }>();

        const status = (row?.status as IpStatus) ?? "pending";
        return json({ ip: raw, status }, { status: 201 });
      } catch (e) {
        return json({ error: String(e) }, { status: 500 });
      }
    }

    const putMatch = /^\/api\/v1\/ips\/([^/]+)$/.exec(path);
    if (putMatch && request.method === "PUT") {
      const ip = decodeURIComponent(putMatch[1]);
      if (!isIpv4(ip)) {
        return json({ error: "invalid_ipv4" }, { status: 400 });
      }
      let body: { reachable?: boolean };
      try {
        body = (await request.json()) as { reachable?: boolean };
      } catch {
        return json({ error: "invalid_json" }, { status: 400 });
      }
      if (typeof body.reachable !== "boolean") {
        return json({ error: "reachable_required" }, { status: 400 });
      }
      const status: IpStatus = body.reachable ? "reachable" : "unreachable";
      const updatedAt = new Date().toISOString();
      try {
        const result = await env.DB.prepare(
          `UPDATE ip_checks SET status = ?, updated_at = ? WHERE ip = ?`,
        )
          .bind(status, updatedAt, ip)
          .run();

        const changes = result.meta?.changes ?? 0;
        if (changes === 0) {
          return json({ error: "not_found" }, { status: 404 });
        }
        return json({ ip, status, updatedAt });
      } catch (e) {
        return json({ error: String(e) }, { status: 500 });
      }
    }

    if (path === "/" && request.method === "GET") {
      return json({
        service: "white-list-check-api",
        storage: "d1",
        docs: "/openapi.json",
      });
    }

    return json({ error: "not_found" }, { status: 404 });
  },
};
