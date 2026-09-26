export interface HttpRequest {
  readonly body?: BodyInit | null;
  readonly credentials?: RequestCredentials;
  readonly headers?: HeadersInit;
  readonly method?: string;
  readonly signal?: AbortSignal;
  readonly target: string | URL;
}

export type HttpResponseBody =
  | { readonly kind: "empty" }
  | { readonly kind: "json"; readonly value: unknown }
  | { readonly kind: "text"; readonly value: string };

export interface HttpResponse {
  readonly body: HttpResponseBody;
  readonly headers: Headers;
  readonly ok: boolean;
  readonly status: number;
}

export type HttpTransportErrorKind = "aborted" | "invalid-response" | "network";

export interface HttpTransportError {
  readonly kind: HttpTransportErrorKind;
  readonly cause?: unknown;
}

export type HttpTransportResult =
  | { readonly kind: "response"; readonly response: HttpResponse }
  | { readonly kind: "transport-error"; readonly error: HttpTransportError };

export interface HttpTransport {
  execute(request: HttpRequest): Promise<HttpTransportResult>;
}

export function abortedTransportResult(cause?: unknown): HttpTransportResult {
  return {
    kind: "transport-error",
    error: { kind: "aborted", cause },
  };
}

function isJsonContentType(contentType: string | null): boolean {
  if (contentType === null) {
    return false;
  }

  const mediaType = contentType.split(";", 1)[0]?.trim().toLowerCase() ?? "";
  return mediaType === "application/json" || mediaType.endsWith("+json");
}

async function readResponseBody(response: Response): Promise<HttpResponseBody> {
  if (response.status === 204 || response.status === 205) {
    return { kind: "empty" };
  }

  const text = await response.text();
  if (text.length === 0) {
    return { kind: "empty" };
  }

  if (isJsonContentType(response.headers.get("content-type"))) {
    return { kind: "json", value: JSON.parse(text) as unknown };
  }

  return { kind: "text", value: text };
}

function transportError(
  cause: unknown,
  signal?: AbortSignal,
): HttpTransportResult {
  if (signal?.aborted) {
    return abortedTransportResult(signal.reason);
  }

  const kind =
    cause instanceof DOMException && cause.name === "AbortError"
      ? "aborted"
      : "network";

  return { kind: "transport-error", error: { kind, cause } };
}

export function createFetchHttpTransport(
  fetchImplementation: typeof fetch = globalThis.fetch,
): HttpTransport {
  const transport: HttpTransport = {
    async execute(request: HttpRequest): Promise<HttpTransportResult> {
      let response: Response;
      try {
        response = await fetchImplementation(request.target, {
          body: request.body,
          credentials: request.credentials,
          headers:
            request.headers === undefined
              ? undefined
              : new Headers(request.headers),
          method: request.method,
          signal: request.signal,
        });
      } catch (cause) {
        return transportError(cause, request.signal);
      }

      try {
        return {
          kind: "response",
          response: {
            body: await readResponseBody(response),
            headers: new Headers(response.headers),
            ok: response.ok,
            status: response.status,
          },
        };
      } catch (cause) {
        return {
          kind: "transport-error",
          error: { kind: "invalid-response", cause },
        };
      }
    },
  };

  return Object.freeze(transport);
}
