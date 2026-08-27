"use client";

import { createClient, type RealtimeChannel } from "@supabase/supabase-js";
import type { GraphEvent } from "@/lib/contracts";
import { isMockMode } from "@/lib/api-client";
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

  const url = process.env.NEXT_PUBLIC_SUPABASE_URL;
  const key = process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY;
  if (!url || !key) {
    onStatus("degraded");
    return () => undefined;
  }

  const client = createClient(url, key);
  const channel: RealtimeChannel = client
    .channel(`sovereignty:${sessionSlug}:round:${round}`)
    .on("broadcast", { event: "dependency.created" }, ({ payload }) => onEvent(payload))
    .on("broadcast", { event: "graph.invalidated" }, ({ payload }) => onEvent(payload))
    .subscribe((status) => {
      onStatus(status === "SUBSCRIBED" ? "live" : status === "CHANNEL_ERROR" || status === "TIMED_OUT" ? "degraded" : "connecting");
    });

  return () => {
    if (channel) void client.removeChannel(channel);
  };
}
