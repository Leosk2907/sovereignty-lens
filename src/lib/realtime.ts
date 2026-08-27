"use client";

import type { GraphEvent } from "@/lib/contracts";
import { apiUrl, isMockMode } from "@/lib/api-client";
import { mockSubscribe } from "@/lib/mock-store";

export type ConnectionState = "connecting" | "live" | "degraded";

export function subscribeToGraph(
  sessionSlug: string,
  round: number,
  onEvent: (event: unknown) => void,
  onStatus: (status: ConnectionState) => void,
): () => void {
  if (isMockMode) {
    onStatus("live");
    return mockSubscribe(onEvent as (event: GraphEvent) => void);
  }

  void round;
  onStatus("connecting");
  const stream = new EventSource(apiUrl(`/api/sessions/${sessionSlug}/events`), {
    withCredentials: true,
  });
  const receive = (event: Event) => {
    try {
      onEvent(JSON.parse((event as MessageEvent<string>).data) as unknown);
    } catch {
      onEvent(null);
    }
  };
  stream.addEventListener("dependency.created", receive);
  stream.addEventListener("graph.invalidated", receive);
  stream.onopen = () => onStatus("live");
  stream.onerror = () => onStatus("degraded");

  return () => {
    stream.close();
  };
}
