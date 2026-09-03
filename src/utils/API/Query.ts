import Defaults from "../../components/Global/Defaults.ts";
import { SpicyLyricsApiVersion } from "../../../project/config.ts";
import Logger from "../Logger.ts";

export type Query = {
  operation: string;
  variables?: any;
};

export type QueryObjectResult = {
  data: any;
  httpStatus: number;
  format: "text" | "json";
};

export type QueryObject = {
  operation: string;
  operationId: string;
  result: QueryObjectResult;
};

export interface QueryResultGetter {
  get(operationId: string): QueryObjectResult | undefined;
}

const queryLogger = new Logger("API Query");
export const API_COMPATIBILITY_VERSION = SpicyLyricsApiVersion;

function redactHeaders(headers: Record<string, string>): Record<string, string> {
  return Object.fromEntries(
    Object.entries(headers).map(([key, value]) => [
      key,
      /auth|authorization|token|cookie/i.test(key) ? "[redacted]" : value,
    ])
  );
}

export function buildQueryHeaders(headers: Record<string, string> = {}): Record<string, string> {
  return {
    "Content-Type": "application/json",
    ...headers,
    "SpicyLyrics-Version": API_COMPATIBILITY_VERSION,
    "X-mode": "2",
  };
}

export function buildQueryBody(queries: Query[]) {
  return {
    queries,
    client: {
      version: API_COMPATIBILITY_VERSION,
    },
  };
}

export async function Query(
  queries: Query[],
  headers: Record<string, string> = {}
): Promise<QueryResultGetter> {
  const host = Defaults.lyrics.api.url;

  queryLogger.info("Sending API query request", {
    queries,
    host,
    clientVersion: API_COMPATIBILITY_VERSION,
    headers: redactHeaders(headers),
  });

  try {
    const res = await fetch(`${host}/query`, {
      method: "POST",
      headers: buildQueryHeaders(headers),
      body: JSON.stringify(buildQueryBody(queries)),
    });

    queryLogger.info("Received response", { status: res.status });

    if (!res.ok) {
      queryLogger.error(`Request failed with status ${res.status}`);
      throw new Error(`Request failed with status ${res.status}`);
    }

    const data = await res.json();
    if (!data || !Array.isArray(data.queries)) {
      throw new Error("API returned an invalid query response");
    }
    queryLogger.debug("Response data", data);
    const results: Map<string, QueryObjectResult> = new Map();

    for (const job of data.queries) {
      results.set(job.operationId, job.result);
      queryLogger.debug("Query result set", { operationId: job.operationId, result: job.result });
    }

    return {
      get(operationId: string): QueryObjectResult | undefined {
        queryLogger.debug("Attempting to retrieve query result for operationId", operationId);
        const result = results.get(operationId);
        if (!result) {
          queryLogger.warn("Query result not found for operationId", operationId, Array.from(results.keys()));
        } else {
          queryLogger.debug("Query result retrieved for operationId", operationId, result);
        }
        return result;
      },
    };
  } catch (error) {
    queryLogger.error("Query error", error);
    throw error;
  }
}
